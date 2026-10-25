package com.example.brotherotgprint

import android.graphics.Bitmap

object BrlaserBridge {
    init {
        System.loadLibrary("brlaser_driver")
    }

    external fun renderPage(bitmap: Bitmap): ByteArray
}
