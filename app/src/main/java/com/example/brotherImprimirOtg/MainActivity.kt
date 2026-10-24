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
    private var brotherDevice: UsbDevice? = null

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
                            brotherDevice = it
                            tvStatus.text = "Conectado a: ${it.productName ?: "Brother HL-L2310D"}"
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
            text = "Conecta la Brother HL-L2310D por OTG"
            textSize = 18f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 48)
        }
        layout.addView(tvStatus)

        btnConnect = Button(this).apply {
            text = "1. Detectar Impresora USB"
            setOnClickListener { findBrotherPrinter() }
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

    private fun findBrotherPrinter() {
        val deviceList = usbManager.deviceList
        brotherDevice = null

        for (device in deviceList.values) {
            if (device.vendorId == 1115) {
                brotherDevice = device
                break
            }
        }

        if (brotherDevice != null) {
            if (!usbManager.hasPermission(brotherDevice)) {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                usbManager.requestPermission(brotherDevice, permissionIntent)
            } else {
                tvStatus.text = "Impresora lista: ${brotherDevice?.productName ?: "Brother"}"
                btnPrintTest.isEnabled = true
            }
        } else {
            tvStatus.text = "No se detectó la impresora. Verifica el cable OTG."
            Toast.makeText(this, "Asegúrate de que la impresora está encendida", Toast.LENGTH_SHORT).show()
        }
    }

    private fun printTestPage() {
        val device = brotherDevice ?: return
        tvStatus.text = "Generando y enviando datos..."

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
                    runOnUiThread { tvStatus.text = "Error al abrir conexión USB." }
                    return@thread
                }

                connection.claimInterface(usbInterface, true)

                val bitmap = createTestPageBitmap()
                val printData = encodeBitmapToBrotherRaster(bitmap)

                val chunkSize = 4096
                var offset = 0
                while (offset < printData.size) {
                    val length = Math.min(chunkSize, printData.size - offset)
                    val buffer = ByteArray(length)
                    System.arraycopy(printData, offset, buffer, 0, length)

                    val transferred = connection.bulkTransfer(outEndpoint, buffer, length, 5000)
                    if (transferred < 0) {
                        runOnUiThread { tvStatus.text = "Fallo durante la transferencia USB." }
                        break
                    }
                    offset += length
                }

                connection.releaseInterface(usbInterface)
                connection.close()

                runOnUiThread {
                    tvStatus.text = "¡Página enviada con éxito!"
                    Toast.makeText(this@MainActivity, "Impresión completada", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "Error: ${e.message}" }
            }
        }
    }

    private fun createTestPageBitmap(): Bitmap {
        val width = 1200
        val height = 1600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 46f
            isAntiAlias = true
        }

        canvas.drawText("IMPRESIÓN DIRECTA OTG", 80f, 180f, paint)
        paint.textSize = 30f
        canvas.drawText("Impresora: Brother HL-L2310D", 80f, 260f, paint)
        canvas.drawText("FacturaScripts / Android OTG OK", 80f, 320f, paint)
        return bitmap
    }

    private fun encodeBitmapToBrotherRaster(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        stream.write(byteArrayOf(0x1B, 0x40)) // ESC @ (Reset)
        stream.write(byteArrayOf(0x1B, 0x69, 0x61, 0x01)) // Entrar en modo Raster

        val width = bitmap.width
        val height = bitmap.height
        val bytesPerLine = (width + 7) / 8

        for (y in 0 until height) {
            val lineBuffer = ByteArray(bytesPerLine)
            var currentByte = 0
            var bitIndex = 7

            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val lum = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()

                if (lum < 128) {
                    currentByte = currentByte or (1 shl bitIndex)
                }

                bitIndex--
                if (bitIndex < 0) {
                    lineBuffer[x / 8] = currentByte.toByte()
                    currentByte = 0
                    bitIndex = 7
                }
            }
            if (bitIndex != 7) {
                lineBuffer[bytesPerLine - 1] = currentByte.toByte()
            }

            stream.write(0x67) // 'g'
            stream.write(0x00)
            stream.write(bytesPerLine and 0xFF)
            stream.write((bytesPerLine shr 8) and 0xFF)
            stream.write(lineBuffer)
        }

        stream.write(0x0C) // Form Feed (expulsar hoja)
        stream.write(byteArrayOf(0x1B, 0x69, 0x61, 0x00)) // Salir de modo Raster
        return stream.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
