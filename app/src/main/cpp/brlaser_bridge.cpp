#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <vector>
#include <cstdint>
#include <cstring>
#include <string>

#define TAG "BrlaserNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Algoritmo de codificación por líneas original de brlaser
void encode_line(const uint8_t* in, int width_bytes, std::vector<uint8_t>& out) {
    int pos = 0;
    while (pos < width_bytes) {
        int run = 1;
        while (pos + run < width_bytes && run < 128 && in[pos + run] == in[pos]) {
            run++;
        }
        if (run >= 3) {
            // RLE byte repetido
            out.push_back(static_cast<uint8_t>(257 - run));
            out.push_back(in[pos]);
            pos += run;
        } else {
            // Bytes literales
            int literal_len = 0;
            while (pos + literal_len < width_bytes && literal_len < 128) {
                if (pos + literal_len + 2 < width_bytes &&
                    in[pos + literal_len] == in[pos + literal_len + 1] &&
                    in[pos + literal_len] == in[pos + literal_len + 2]) {
                    break;
                }
                literal_len++;
            }
            if (literal_len > 0) {
                out.push_back(static_cast<uint8_t>(literal_len - 1));
                for (int i = 0; i < literal_len; ++i) {
                    out.push_back(in[pos + i]);
                }
                pos += literal_len;
            }
        }
    }
}

void flush_block(std::vector<uint8_t>& stream, std::vector<uint8_t>& block_data, int num_lines) {
    if (num_lines <= 0) return;
    uint32_t size = block_data.size();
    stream.push_back(0x40); // Prefijo de bloque brlaser
    stream.push_back(static_cast<uint8_t>((size >> 8) & 0xFF));
    stream.push_back(static_cast<uint8_t>(size & 0xFF));
    stream.push_back(static_cast<uint8_t>(num_lines & 0xFF));
    stream.insert(stream.end(), block_data.begin(), block_data.end());
    block_data.clear();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_brotherotgprint_BrlaserBridge_renderPage(
    JNIEnv* env,
    jobject /* this */,
    jobject bitmap
) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Error al leer información del Bitmap.");
        return nullptr;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("No se pudieron bloquear los píxeles del Bitmap.");
        return nullptr;
    }

    std::vector<uint8_t> output;

    // 1. Cabecera PJL idéntica a brlaser job.cc
    std::string pjl_header = 
        "\x1b%-12345X@PJL\n"
        "@PJL JOB NAME=\"brlaser_android\"\n"
        "@PJL SET RESOLUTION = 600\n"
        "@PJL SET PAPER = A4\n"
        "@PJL ENTER LANGUAGE = PCL\n"
        "\x1bE"
        "\x1b&l26a0o0E"
        "\x1b*t600R"
        "\x1b*r1A"
        "\x1b*b1027M";
    output.insert(output.end(), pjl_header.begin(), pjl_header.end());

    int width = info.width;
    int height = info.height;
    int bytes_per_line = (width + 7) / 8;

    std::vector<uint8_t> block_buffer;
    int lines_in_block = 0;

    // 2. Procesamiento de filas
    for (int y = 0; y < height; ++y) {
        std::vector<uint8_t> raw_line(bytes_per_line, 0);
        const uint16_t* line_pixels = reinterpret_cast<const uint16_t*>(
            reinterpret_cast<const uint8_t*>(pixels) + y * info.stride
        );

        for (int x = 0; x < width; ++x) {
            uint16_t p = line_pixels[x];
            // Umbral RGB565 monocromo
            int r = (p >> 11) & 0x1F;
            int g = (p >> 5) & 0x3F;
            int b = p & 0x1F;
            int gray = (r * 299 + g * 587 + b * 114) / 1000;

            if (gray < 16) { // Píxel negro
                raw_line[x / 8] |= (1 << (7 - (x % 8)));
            }
        }

        encode_line(raw_line.data(), bytes_per_line, block_buffer);
        lines_in_block++;

        // brlaser envía paquetes en bloques de 25 a 30 líneas
        if (lines_in_block >= 25) {
            flush_block(output, block_buffer, lines_in_block);
            lines_in_block = 0;
        }
    }

    if (lines_in_block > 0) {
        flush_block(output, block_buffer, lines_in_block);
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    // 3. Cierre de página idéntico a brlaser
    std::string pjl_footer = 
        "\x0c"
        "\x1b*b0M"
        "\x1bE"
        "\x1b%-12345X@PJL EOJ\n"
        "\x1b%-12345X";
    output.insert(output.end(), pjl_footer.begin(), pjl_footer.end());

    // Crear el jbyteArray para Kotlin
    jbyteArray result = env->NewByteArray(output.size());
    env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte*>(output.data()));
    return result;
}
