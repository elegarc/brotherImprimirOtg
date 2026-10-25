package com.example.brotherotgprint

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.*
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.example.brotherotgprint.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private var printerDevice: UsbDevice? = null

    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnPrintTest: Button

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            printerDevice = it
                            val name = try { it.productName ?: "Brother HL-L2310D" } catch (e: Exception) { "Impresora USB" }
                            tvStatus.text = "Conectado a:\n$name"
                            btnPrintTest.isEnabled = true
                        }
                    } else {
                        tvStatus.text = "Permiso USB denegado"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = android.view.Gravity.CENTER
        }

        tvStatus = TextView(this).apply {
            text = "Conecta la impresora por OTG y pulsa 'Detectar'"
            textSize = 17f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 48)
        }
        layout.addView(tvStatus)

        btnConnect = Button(this).apply {
            text = "1. Detectar Impresora USB"
            setOnClickListener { findPrinter() }
        }
        layout.addView(btnConnect)

        btnPrintTest = Button(this).apply {
            text = "2. Imprimir Página de Prueba"
            isEnabled = false
            setOnClickListener { printTestPage() }
        }
        layout.addView(btnPrintTest)

        setContentView(layout)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(usbReceiver, filter, flags)
    }

    private fun findPrinter() {
        try {
            val deviceList = usbManager.deviceList
            printerDevice = null

            if (deviceList.isEmpty()) {
                tvStatus.text = "No se detecta ningún dispositivo USB.\nComprueba la conexión del cable OTG."
                return
            }

            for (device in deviceList.values) {
                if (device.vendorId == 1115 || device.deviceClass == UsbConstants.USB_CLASS_PRINTER) {
                    printerDevice = device
                    break
                }
                for (i in 0 until device.interfaceCount) {
                    if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                        printerDevice = device
                        break
                    }
                }
            }

            if (printerDevice == null && deviceList.size == 1) {
                printerDevice = deviceList.values.first()
            }

            val device = printerDevice
            if (device != null) {
                if (!usbManager.hasPermission(device)) {
                    val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
                    usbManager.requestPermission(device, permissionIntent)
                    tvStatus.text = "Acepta el mensaje emergente en pantalla..."
                } else {
                    val name = try { device.productName ?: "Brother" } catch (e: Exception) { "Impresora" }
                    tvStatus.text = "Impresora lista:\n$name"
                    btnPrintTest.isEnabled = true
                }
            } else {
                tvStatus.text = "No se reconoció ninguna impresora Brother."
            }
        } catch (e: Throwable) {
            tvStatus.text = "Error al detectar:\n${e.message}"
        }
    }

    private fun printTestPage() {
        val device = printerDevice ?: return
        tvStatus.text = "Generando flujo láser a 600 DPI con PackBits..."

        thread {
            try {
                var usbInterface: UsbInterface? = null
                var outEndpoint: UsbEndpoint? = null

                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    for (j in 0 until iface.endpointCount) {
                        val endpoint = iface.getEndpoint(j)
                        if (endpoint.direction == UsbConstants.USB_DIR_OUT &&
                            endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            usbInterface = iface
                            outEndpoint = endpoint
                            break
                        }
                    }
                    if (outEndpoint != null) break
                }

                if (usbInterface == null || outEndpoint == null) {
                    runOnUiThread { tvStatus.text = "Error: Endpoint USB de salida no encontrado." }
                    return@thread
                }

                val connection = usbManager.openDevice(device) ?: run {
                    runOnUiThread { tvStatus.text = "Error: No se pudo abrir la conexión USB." }
                    return@thread
                }

                connection.claimInterface(usbInterface, true)

                val printData = generateBrotherLaserStream()

                runOnUiThread { tvStatus.text = "Enviando datos al motor de impresión..." }

                val chunkSize = 4096
                var offset = 0
                while (offset < printData.size) {
                    val length = Math.min(chunkSize, printData.size - offset)
                    val buffer = ByteArray(length)
                    System.arraycopy(printData, offset, buffer, 0, length)

                    val transferred = connection.bulkTransfer(outEndpoint, buffer, length, 5000)
                    if (transferred < 0) {
                        runOnUiThread { tvStatus.text = "Fallo de transferencia USB." }
                        break
                    }
                    offset += length
                }

                connection.releaseInterface(usbInterface)
                connection.close()

                runOnUiThread {
                    tvStatus.text = "¡Trabajo enviado! La impresora debería comenzar a imprimir."
                    Toast.makeText(this@MainActivity, "Comando enviado", Toast.LENGTH_LONG).show()
                }

            } catch (e: Throwable) {
                runOnUiThread { tvStatus.text = "Error al imprimir:\n${e.message}" }
            }
        }
    }

    private fun generateBrotherLaserStream(): ByteArray {
        val stream = ByteArrayOutputStream()

        // 1. Cabecera PJL a 600 DPI nativos
        val pjlHeader = "\u001b%-12345X@PJL\r\n" +
                "@PJL SET REPLOCK = OFF\r\n" +
                "@PJL SET ECONOMODE = OFF\r\n" +
                "@PJL SET RESOLUTION = 600\r\n" +
                "@PJL SET MEDIASIZE = A4\r\n" +
                "@PJL ENTER LANGUAGE = RASTER\r\n"
        stream.write(pjlHeader.toByteArray(Charsets.US_ASCII))

        val width = 4960      // Ancho A4 exacto a 600 DPI
        val textHeight = 1200 // Cabecera con texto
        val totalLines = 7016 // Alto A4 total a 600 DPI
        val bytesPerLine = width / 8 // 620 bytes por fila

        // 2. Dibujar texto en imagen en memoria (usando RGB_565 para no saturar la RAM de la tablet)
        val bitmap = Bitmap.createBitmap(width, textHeight, Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 130f
            isAntiAlias = true
        }

        canvas.drawText("PANADERÍA Y PASTELERÍA JOGARI", 300f, 450f, paint)
        paint.textSize = 90f
        canvas.drawText("FacturaScripts - Conexión OTG OK", 300f, 650f, paint)
        paint.textSize = 75f
        canvas.drawText("Modelo: Brother HL-L2310D (600 DPI PackBits)", 300f, 820f, paint)

        paint.strokeWidth = 10f
        canvas.drawLine(300f, 950f, 4660f, 950f, paint)

        val pixels = IntArray(width)

        // 3. Procesar las líneas de la cabecera
        for (y in 0 until textHeight) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

            val lineBuffer = ByteArray(bytesPerLine)
            var currentByte = 0
            var bitIndex = 7
            var bytePos = 0
            var isBlank = true

            for (x in 0 until width) {
                val pixel = pixels[x]
                val lum = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()

                if (lum < 128) {
                    currentByte = currentByte or (1 shl bitIndex)
                    isBlank = false
                }

                bitIndex--
                if (bitIndex < 0) {
                    lineBuffer[bytePos++] = currentByte.toByte()
                    currentByte = 0
                    bitIndex = 7
                }
            }

            if (isBlank) {
                // Comando 'z' (0x7A): línea en blanco instantánea
                stream.write(0x7A)
            } else {
                // Compresión PackBits estándar en bloques de hasta 128 bytes
                val packbitsStream = ByteArrayOutputStream()
                var pos = 0
                while (pos < bytesPerLine) {
                    val chunkSize = Math.min(128, bytesPerLine - pos)
                    packbitsStream.write(chunkSize - 1) // Cabecera literal PackBits
                    packbitsStream.write(lineBuffer, pos, chunkSize)
                    pos += chunkSize
                }
                val packedData = packbitsStream.toByteArray()

                // Comando 'w' (0x77) + longitud (Little Endian) + datos PackBits
                stream.write(0x77)
                stream.write(packedData.size and 0xFF)
                stream.write((packedData.size shr 8) and 0xFF)
                stream.write(packedData)
            }
        }

        bitmap.recycle()

        // 4. Completar el resto del folio A4 con saltos de línea vacíos
        for (y in textHeight until totalLines) {
            stream.write(0x7A)
        }

        // 5. Expulsar papel (Form Feed) y cerrar trabajo
        stream.write(0x0C)
        val pjlFooter = "\u001b%-12345X"
        stream.write(pjlFooter.toByteArray(Charsets.US_ASCII))

        return stream.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
