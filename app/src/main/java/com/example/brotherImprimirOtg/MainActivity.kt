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
            text = "--- CONSOLA BRLASER 1027M (BLOQUES 0x40) ---\n"
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
        log("Construyendo bloques 0x40 de 4 bytes (brlaser)...")

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

                val printData = generateExactBrlaser1027MStream()
                log("Flujo generado: ${printData.size} bytes (${printData.size / 1024} KB).")

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
                log("Puerto cerrado. Esperando hoja impresa...")

            } catch (e: Throwable) {
                log("EXCEPCIÓN: ${e.message}")
            }
        }
    }

    // Codifica una linea usando fragmentos literales de maximo 64 bytes (evita overflow de brlaser)
    private fun encodeLineLiteralSafe(lineBuffer: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pos < lineBuffer.size) {
            val chunk = Math.min(64, lineBuffer.size - pos)
            out.write(chunk - 1) // 0..63
            out.write(lineBuffer, pos, chunk)
            pos += chunk
        }
        return out.toByteArray()
    }

    // Escribe un bloque de lineas con cabecera de 4 bytes de brlaser: 0x40, size high, size low, num_lines
    private fun flushBlock(stream: ByteArrayOutputStream, blockBuffer: ByteArrayOutputStream, numLines: Int) {
        if (numLines <= 0) return
        val data = blockBuffer.toByteArray()
        stream.write(0x40) // '@'
        stream.write((data.size shr 8) and 0xFF)
        stream.write(data.size and 0xFF)
        stream.write(numLines and 0xFF) // 4o byte obligatorio: numero de lineas
        stream.write(data)
        blockBuffer.reset()
    }

    private fun generateExactBrlaser1027MStream(): ByteArray {
        val stream = ByteArrayOutputStream()

        // 1. Cabecera de trabajo PJL + Reset PCL inicial (identico a job.cc de brlaser)
        val jobHeader = "\u001b%-12345X@PJL\n" +
                "@PJL JOB NAME=\"brlaser\"\n" +
                "@PJL SET RAS1200MODE = OFF\n" +
                "@PJL SET RESOLUTION = 600\n" +
                "@PJL SET ECONOMODE = OFF\n" +
                "@PJL SET SOURCETRAY = AUTO\n" +
                "@PJL SET MEDIATYPE = REGULAR\n" +
                "@PJL SET PAGEPROTECT = AUTO\n" +
                "@PJL SET ORIENTATION = PORTRAIT\n" +
                "@PJL SET PAPER = A4\n" +
                "@PJL ENTER LANGUAGE = PCL\n" +
                "\u001bE"
        stream.write(jobHeader.toByteArray(Charsets.US_ASCII))

        // 2. Cabecera de pagina PCL + entrada en modo 1027M (write_page_header en job.cc)
        val pageHeader = "\u001b&l1X" +
                "\u001b&l0O" +
                "\u001b&l0E" +
                "\u001b&l26A" +
                "\u001b*t600R" +
                "\u001b*b1027M"
        stream.write(pageHeader.toByteArray(Charsets.US_ASCII))

        val width = 4736        // 592 bytes por fila
        val totalHeight = 1200  // Margen superior + texto
        val bytesPerLine = width / 8

        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 140f
            isAntiAlias = true
        }

        canvas.drawText("PANADERÍA Y PASTELERÍA JOGARI", 250f, 450f, paint)
        paint.textSize = 100f
        canvas.drawText("Impresión OTG Directa - FacturaScripts", 250f, 650f, paint)
        paint.textSize = 85f
        canvas.drawText("Brother HL-L2310D (Bloques 0x40 de 4 bytes)", 250f, 820f, paint)
        paint.strokeWidth = 16f
        canvas.drawLine(250f, 950f, 4400f, 950f, paint)

        val pixels = IntArray(width)
        val blockBuffer = ByteArrayOutputStream()
        var linesInBlock = 0

        // 3. Empaquetar lineas en bloques 0x40 (25 lineas por bloque)
        for (y in 0 until totalHeight) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

            val lineBuffer = ByteArray(bytesPerLine)
            var currentByte = 0
            var bitIndex = 7
            var bytePos = 0

            for (x in 0 until width) {
                val pixel = pixels[x]
                val lum = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
                if (lum < 128) {
                    currentByte = currentByte or (1 shl bitIndex)
                }
                bitIndex--
                if (bitIndex < 0) {
                    lineBuffer[bytePos++] = currentByte.toByte()
                    currentByte = 0
                    bitIndex = 7
                }
            }

            val encodedLine = encodeLineLiteralSafe(lineBuffer)
            blockBuffer.write(encodedLine)
            linesInBlock++

            if (linesInBlock == 25) {
                flushBlock(stream, blockBuffer, linesInBlock)
                linesInBlock = 0
            }
        }

        flushBlock(stream, blockBuffer, linesInBlock)
        bitmap.recycle()

        // 4. Fin de pagina: primero Form Feed (0x0C) y despues salir con \u001b*b0M
        stream.write(0x0C)
        stream.write("\u001b*b0M".toByteArray(Charsets.US_ASCII))

        // 5. Cierre de trabajo PJL
        val jobFooter = "\u001bE\u001b%-12345X@PJL EOJ\n\u001b%-12345X"
        stream.write(jobFooter.toByteArray(Charsets.US_ASCII))

        return stream.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
