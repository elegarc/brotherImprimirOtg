package com.example.brotherotgprint

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.usb.*
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.example.brotherotgprint.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private var printerDevice: UsbDevice? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
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
                            log("PERMISO CONCEDIDO para USB ID: ${it.vendorId}:${it.productId}")
                            tvStatus.text = "Conectado a:\n${it.productName ?: "Brother HL-L2310D"}"
                            btnPrintTest.isEnabled = true
                        }
                    } else {
                        log("ERROR: Permiso USB denegado.")
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
            setPadding(32, 32, 32, 32)
        }

        tvStatus = TextView(this).apply {
            text = "Conecta la impresora por OTG y pulsa Detectar"
            textSize = 16f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 32)
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

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f
            )
            setPadding(0, 32, 0, 0)
        }

        tvLog = TextView(this).apply {
            text = "--- CONSOLA BROTHER MODO 1027M ---\n"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00FF00"))
            setBackgroundColor(Color.BLACK)
            setPadding(16, 16, 16, 16)
        }

        scrollView.addView(tvLog)
        layout.addView(scrollView)

        setContentView(layout)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(usbReceiver, filter, flags)
    }

    private fun log(message: String) {
        runOnUiThread {
            tvLog.append("$message\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun findPrinter() {
        log("Escaneando puerto USB OTG...")
        try {
            val deviceList = usbManager.deviceList
            printerDevice = null

            if (deviceList.isEmpty()) {
                log("No se detecta ningún dispositivo USB.")
                return
            }

            for (device in deviceList.values) {
                if (device.vendorId == 1115 || device.deviceClass == UsbConstants.USB_CLASS_PRINTER) {
                    printerDevice = device
                    log("-> Impresora Brother detectada (V:${device.vendorId} P:${device.productId})")
                    break
                }
            }

            if (printerDevice == null && deviceList.size == 1) {
                printerDevice = deviceList.values.first()
            }

            val device = printerDevice
            if (device != null) {
                if (!usbManager.hasPermission(device)) {
                    log("Solicitando permiso USB...")
                    val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
                    usbManager.requestPermission(device, permissionIntent)
                } else {
                    log("Permiso USB listo.")
                    tvStatus.text = "Impresora lista:\n${device.productName ?: "Brother HL-L2310D"}"
                    btnPrintTest.isEnabled = true
                }
            }
        } catch (e: Throwable) {
            log("Error en findPrinter: ${e.message}")
        }
    }

    private fun printTestPage() {
        val device = printerDevice ?: return
        log("Generando flujo con modo propietario 1027M...")

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
                    log("ERROR: Endpoint BULK OUT no encontrado.")
                    return@thread
                }

                val connection = usbManager.openDevice(device) ?: run {
                    log("ERROR: No se pudo abrir el dispositivo USB.")
                    return@thread
                }

                connection.claimInterface(usbInterface, true)

                val printData = generateBrother1027MStream()
                log("Flujo 1027M generado: ${printData.size} bytes.")

                val chunkSize = 16384
                var offset = 0
                val startTime = System.currentTimeMillis()

                log("--- ENVIANDO A LA IMPRESORA ---")

                while (offset < printData.size) {
                    val length = Math.min(chunkSize, printData.size - offset)
                    val buffer = ByteArray(length)
                    System.arraycopy(printData, offset, buffer, 0, length)

                    val transferred = connection.bulkTransfer(outEndpoint, buffer, length, 5000)
                    if (transferred < 0) {
                        log("ERROR: bulkTransfer falló en byte $offset.")
                        break
                    }
                    offset += transferred
                }

                val endTime = System.currentTimeMillis()
                log("--- TRANSFERENCIA COMPLETADA (${endTime - startTime} ms) ---")

                connection.releaseInterface(usbInterface)
                connection.close()
                log("Puerto cerrado. Comprueba el texto en la página impresa.")

            } catch (e: Throwable) {
                log("EXCEPCIÓN: ${e.message}")
            }
        }
    }

    /**
     * Empaqueta una línea de 592 bytes en formato literal PackBits (bloques de hasta 128 bytes)
     */
    private fun packBitsLiteral(lineBuffer: ByteArray): ByteArray {
        val packed = ByteArrayOutputStream()
        var pos = 0
        while (pos < lineBuffer.size) {
            val chunk = Math.min(128, lineBuffer.size - pos)
            packed.write(chunk - 1) // 0..127 indica N+1 bytes literales en PackBits
            packed.write(lineBuffer, pos, chunk)
            pos += chunk
        }
        return packed.toByteArray()
    }

    /**
     * Genera el flujo exacto del driver brlaser usando el modo propietario Brother 1027M
     */
    private fun generateBrother1027MStream(): ByteArray {
        val stream = ByteArrayOutputStream()

        // 1. Cabecera PJL que ya activa correctamente tu impresora y saca 1 sola hoja
        val pjlHeader = "\u001b%-12345X@PJL JOB NAME=\"JOGARI\"\r\n" +
                "@PJL SET RAS1200MODE = OFF\r\n" +
                "@PJL SET RESOLUTION = 600\r\n" +
                "@PJL SET ECONOMODE = OFF\r\n" +
                "@PJL SET SOURCETRAY = TRAY1\r\n" +
                "@PJL SET MEDIATYPE = REGULAR\r\n" +
                "@PJL SET PAGEPROTECT = AUTO\r\n" +
                "@PJL SET ORIENTATION = PORTRAIT\r\n" +
                "@PJL SET PAPER = A4\r\n" +
                "@PJL ENTER LANGUAGE = PCL\r\n"
        stream.write(pjlHeader.toByteArray(Charsets.US_ASCII))

        // 2. Activación del motor gráfico propietario de Brother:
        // \u001bE        -> Reset PCL
        // \u001b&l26a0o0E -> A4, Portrait, Top margin 0
        // \u001b*t600R   -> 600 DPI
        // \u001b*b1027M  -> MODO PROPIETARIO BROTHER RASTER (Clave en brlaser)
        val brotherInit = "\u001bE\u001b&l26a0o0E\u001b*t600R\u001b*b1027M"
        stream.write(brotherInit.toByteArray(Charsets.US_ASCII))

        val width = 4736        // 592 bytes por fila
        val textHeight = 700
        val bytesPerLine = width / 8

        val bitmap = Bitmap.createBitmap(width, textHeight, Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 130f
            isAntiAlias = true
        }

        canvas.drawText("PANADERÍA Y PASTELERÍA JOGARI", 250f, 250f, paint)
        paint.textSize = 90f
        canvas.drawText("Brother HL-L2310D - Modo 1027M OK", 250f, 420f, paint)
        paint.strokeWidth = 15f
        canvas.drawLine(250f, 520f, 4400f, 520f, paint)

        val pixels = IntArray(width)

        // Margen superior: 150 líneas vacías usando 'y' (0x79)
        for (i in 0 until 150) {
            stream.write(0x79)
        }

        // 3. BLOQUE 1: Envío usando el formato brlaser de paquete con cabecera '@' (0x40)
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
                stream.write(0x79) // 'y' = 1 línea en blanco
            } else {
                val packed = packBitsLiteral(lineBuffer)
                // En modo 1027M: '@' (0x40) + longitud en Big Endian + datos PackBits
                stream.write(0x40) // '@'
                stream.write((packed.size shr 8) and 0xFF)
                stream.write(packed.size and 0xFF)
                stream.write(packed)
            }
        }

        // Separación de 100 líneas en blanco
        for (i in 0 until 100) {
            stream.write(0x79)
        }

        // 4. BLOQUE 2: Envío del mismo bloque usando 'w' (0x77) dentro de 1027M por compatibilidad
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
                stream.write(0x79)
            } else {
                val packed = packBitsLiteral(lineBuffer)
                // 'w' (0x77) + longitud en Big Endian + datos PackBits
                stream.write(0x77)
                stream.write((packed.size shr 8) and 0xFF)
                stream.write(packed.size and 0xFF)
                stream.write(packed)
            }
        }

        bitmap.recycle()

        // 5. Salir de modo 1027M (\u001b*b0M), expulsar la hoja (\u000c), reset (\u001bE) y cerrar PJL
        val footer = "\u001b*b0M\u000c\u001bE\u001b%-12345X@PJL EOJ\r\n\u001b%-12345X"
        stream.write(footer.toByteArray(Charsets.US_ASCII))

        return stream.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
