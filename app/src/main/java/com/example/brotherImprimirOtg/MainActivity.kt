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
                            val name = try { it.productName ?: "Brother HL-L2310D" } catch (e: Exception) { "Impresora Brother" }
                            tvStatus.text = "Conectado a:\n$name"
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
            text = "2. Imprimir Página de Prueba (NDK brlaser)"
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
            text = "--- CONSOLA BRLASER C++ (NDK JNI) ---\n"
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
                log("No se detecta ningún dispositivo conectado al puerto OTG.")
                return
            }

            for (device in deviceList.values) {
                if (device.vendorId == 1115 || device.deviceClass == UsbConstants.USB_CLASS_PRINTER) {
                    printerDevice = device
                    log("-> Impresora Brother detectada (V:${device.vendorId} P:${device.productId})")
                    break
                }
                for (i in 0 until device.interfaceCount) {
                    if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                        printerDevice = device
                        log("-> Dispositivo con interfaz de impresora detectado (V:${device.vendorId})")
                        break
                    }
                }
            }

            if (printerDevice == null && deviceList.size == 1) {
                printerDevice = deviceList.values.first()
                log("-> Seleccionado el único USB disponible por descarte.")
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
                    log("Permiso USB ya estaba concedido.")
                    val name = try { device.productName ?: "Brother HL-L2310D" } catch (e: Exception) { "Impresora Brother" }
                    tvStatus.text = "Impresora lista:\n$name"
                    btnPrintTest.isEnabled = true
                }
            } else {
                log("No se encontró ningún dispositivo compatible.")
            }
        } catch (e: Throwable) {
            log("Error en findPrinter: ${e.message}")
        }
    }

    private fun printTestPage() {
        val device = printerDevice ?: return
        log("Generando lienzo gráfico en Android (RGB_565)...")

        thread {
            try {
                // 1. Localizar interfaz y Endpoint BULK OUT
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
                    log("ERROR: No se pudo abrir la conexión con el dispositivo USB.")
                    return@thread
                }

                connection.claimInterface(usbInterface, true)

                // 2. Crear Bitmap en formato RGB_565 (requerido por el decodificador nativo C++)
                val width = 4736        // Ancho imprimible A4 a 600 DPI (592 bytes por línea)
                val totalHeight = 1500  // Altura del bloque de prueba
                val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.RGB_565)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                val paint = Paint().apply {
                    color = Color.BLACK
                    isAntiAlias = false
                }

                // Dibujar bloque negro macizo superior
                canvas.drawRect(200f, 150f, 4500f, 420f, paint)

                // Texto en blanco dentro del bloque negro
                paint.color = Color.WHITE
                paint.textSize = 140f
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("PANADERÍA Y PASTELERÍA JOGARI", 300f, 340f, paint)

                // Bloque de texto en negro
                paint.color = Color.BLACK
                paint.textSize = 100f
                canvas.drawText("Prueba con motor nativo C++ brlaser (NDK JNI)", 200f, 600f, paint)
                paint.textSize = 80f
                canvas.drawText("Brother HL-L2310D - FacturaScripts OTG", 200f, 740f, paint)

                // Línea divisoria
                canvas.drawRect(200f, 820f, 4500f, 860f, paint)

                log("Ejecutando algoritmo de compresión nativo en C++ (BrlaserBridge)...")
                val startTimeRender = System.currentTimeMillis()
                
                // Llamada a la función externa C++ mediante JNI
                val printData = BrlaserBridge.renderPage(bitmap)
                bitmap.recycle()

                val renderTime = System.currentTimeMillis() - startTimeRender
                log("C++ completado en ${renderTime} ms. Tamaño del flujo: ${printData.size} bytes (${printData.size / 1024} KB).")

                // 3. Transmisión del flujo binario a través de USB OTG
                val chunkSize = 16384
                var offset = 0
                val startTimeTransfer = System.currentTimeMillis()

                log("--- TRANSMITIENDO FLUJO NATIVO A LA BROTHER ---")

                while (offset < printData.size) {
                    val length = Math.min(chunkSize, printData.size - offset)
                    val buffer = ByteArray(length)
                    System.arraycopy(printData, offset, buffer, 0, length)

                    val transferred = connection.bulkTransfer(outEndpoint, buffer, length, 5000)
                    if (transferred < 0) {
                        log("ERROR: bulkTransfer devolvió $transferred en el byte $offset.")
                        break
                    }
                    offset += transferred
                }

                val transferTime = System.currentTimeMillis() - startTimeTransfer
                log("--- TRANSMISIÓN FINALIZADA (${transferTime} ms) ---")
                log("Total transferido: $offset bytes.")

                connection.releaseInterface(usbInterface)
                connection.close()
                log("Puerto USB cerrado. Comprobando salida en la impresora...")

            } catch (e: Throwable) {
                log("EXCEPCIÓN CRÍTICA: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
