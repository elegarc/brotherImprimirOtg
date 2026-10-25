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
                            tvStatus.text = "Conectado a:\n${it.productName ?: "Impresora"}"
                            btnPrintTest.isEnabled = true
                        }
                    } else {
                        log("ERROR: Permiso USB denegado por el usuario.")
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

        // Consola de Logs
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f // Ocupa el espacio restante
            )
            setPadding(0, 32, 0, 0)
        }
        
        tvLog = TextView(this).apply {
            text = "--- CONSOLA DE DIAGNÓSTICO ---\n"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00FF00")) // Verde terminal
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
        log("Buscando dispositivos USB...")
        try {
            val deviceList = usbManager.deviceList
            printerDevice = null

            if (deviceList.isEmpty()) {
                log("No se detecta ningún dispositivo conectado al puerto OTG.")
                return
            }

            for (device in deviceList.values) {
                log("Detectado USB - Vendor: ${device.vendorId}, Product: ${device.productId}, Interfaces: ${device.interfaceCount}")
                if (device.vendorId == 1115 || device.deviceClass == UsbConstants.USB_CLASS_PRINTER) {
                    printerDevice = device
                    log("-> Dispositivo identificado como impresora Brother.")
                    break
                }
            }

            if (printerDevice == null && deviceList.size == 1) {
                printerDevice = deviceList.values.first()
                log("-> Seleccionado el único USB disponible por defecto.")
            }

            val device = printerDevice
            if (device != null) {
                if (!usbManager.hasPermission(device)) {
                    log("Solicitando permiso a Android para acceder al USB...")
                    val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
                    usbManager.requestPermission(device, permissionIntent)
                } else {
                    log("El permiso USB ya estaba concedido.")
                    tvStatus.text = "Impresora lista:\n${device.productName ?: "Impresora"}"
                    btnPrintTest.isEnabled = true
                }
            }
        } catch (e: Throwable) {
            log("Excepción en findPrinter: ${e.message}")
        }
    }

    private fun printTestPage() {
        val device = printerDevice ?: return
        log("Iniciando proceso de impresión...")

        thread {
            try {
                var usbInterface: UsbInterface? = null
                var outEndpoint: UsbEndpoint? = null

                log("Buscando Endpoint de salida (BULK OUT)...")
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    for (j in 0 until iface.endpointCount) {
                        val endpoint = iface.getEndpoint(j)
                        if (endpoint.direction == UsbConstants.USB_DIR_OUT &&
                            endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            usbInterface = iface
                            outEndpoint = endpoint
                            log("Endpoint encontrado en Interfaz $i, Endpoint $j")
                            break
                        }
                    }
                    if (outEndpoint != null) break
                }

                if (usbInterface == null || outEndpoint == null) {
                    log("ERROR CRÍTICO: No se encontró puerto de envío USB.")
                    return@thread
                }

                val connection = usbManager.openDevice(device) ?: run {
                    log("ERROR CRÍTICO: usbManager.openDevice devolvió null.")
                    return@thread
                }

                if (connection.claimInterface(usbInterface, true)) {
                    log("Interfaz USB reclamada con éxito.")
                } else {
                    log("ERROR: Falló claimInterface.")
                }

                log("Generando mapa de bits A4 (4736 píxeles / 592 bytes por línea)...")
                val printData = generateBrotherLaserStream()
                log("Tamaño total a enviar: ${printData.size} bytes (${printData.size / 1024} KB)")

                val chunkSize = 16384 // 16 KB por paquete para no ahogar el USB
                var offset = 0
                var failCount = 0

                log("--- INICIANDO TRANSFERENCIA ---")
                
                val startTime = System.currentTimeMillis()

                while (offset < printData.size) {
                    val length = Math.min(chunkSize, printData.size - offset)
                    val buffer = ByteArray(length)
                    System.arraycopy(printData, offset, buffer, 0, length)

                    // Timeout aumentado a 5000 ms por si la impresora tarda en digerir los datos
                    val transferred = connection.bulkTransfer(outEndpoint, buffer, length, 5000)
                    
                    if (transferred < 0) {
                        log("ERROR USB en el byte $offset. bulkTransfer devolvió $transferred")
                        failCount++
                        if (failCount > 3) {
                            log("ABORTADO: Demasiados fallos de transferencia consecutivos.")
                            break
                        }
                    } else {
                        offset += transferred
                        // Log cada 500 KB para no saturar la pantalla
                        if (offset % (16384 * 30) < 16384) {
                            log("Progreso: ${offset / 1024} KB / ${printData.size / 1024} KB")
                        }
                    }
                }

                val endTime = System.currentTimeMillis()

                log("--- TRANSFERENCIA FINALIZADA ---")
                log("Total enviado: $offset bytes.")
                log("Tiempo de transmisión: ${(endTime - startTime) / 1000} segundos.")

                connection.releaseInterface(usbInterface)
                connection.close()
                log("Conexión USB cerrada correctamente.")

            } catch (e: Throwable) {
                log("EXCEPCIÓN CRÍTICA: ${e.message}")
            }
        }
    }

    private fun generateBrotherLaserStream(): ByteArray {
        val stream = ByteArrayOutputStream()

        val pjlHeader = "\u001b%-12345X@PJL\r\n" +
                "@PJL JOB NAME = \"PRUEBA\"\r\n" +
                "@PJL SET RESOLUTION = 600\r\n" +
                "@PJL SET MEDIASIZE = A4\r\n" +
                "@PJL ENTER LANGUAGE = RASTER\r\n"
        stream.write(pjlHeader.toByteArray(Charsets.US_ASCII))

        // Inicialización específica Raster Brother
        stream.write(byteArrayOf(0x1B, 0x40)) // Reset
        stream.write(byteArrayOf(0x1B, 0x69, 0x61, 0x01)) // Activar Raster
        stream.write(byteArrayOf(0x1B, 0x69, 0x62, 0x00)) // 00 = Sin compresión

        val width = 4736
        val textHeight = 1000
        val bytesPerLine = width / 8 // 592

        val bitmap = Bitmap.createBitmap(width, textHeight, Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 120f
            isAntiAlias = true
        }

        canvas.drawText("LOGS ACTIVADOS - JOGARI", 200f, 350f, paint)
        paint.textSize = 80f
        canvas.drawText("Prueba de diagnóstico de transmisión OTG", 200f, 520f, paint)

        val pixels = IntArray(width)

        for (y in 0 until textHeight) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

            val lineBuffer = ByteArray(bytesPerLine)
            var currentByte = 0
            var bitIndex = 7
            var bytePos = 0

            for (x in 0 until width) {
                val pixel = pixels[x]
                val lum = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
                if (lum < 128) currentByte = currentByte or (1 shl bitIndex)
                bitIndex--
                if (bitIndex < 0) {
                    lineBuffer[bytePos++] = currentByte.toByte()
                    currentByte = 0
                    bitIndex = 7
                }
            }

            stream.write(0x77)
            stream.write(bytesPerLine and 0xFF)
            stream.write((bytesPerLine shr 8) and 0xFF)
            stream.write(lineBuffer)
        }
        bitmap.recycle()

        stream.write(0x0C) // Form Feed
        stream.write(byteArrayOf(0x1B, 0x69, 0x61, 0x00)) // Salir Raster
        stream.write("\u001b%-12345X@PJL EOJ\r\n\u001b%-12345X".toByteArray(Charsets.US_ASCII))

        return stream.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
