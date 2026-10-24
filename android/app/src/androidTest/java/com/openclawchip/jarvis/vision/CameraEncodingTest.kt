package com.openclawchip.jarvis.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test

class CameraEncodingTest {
    @Test fun cameraJpegIsUprightSmallAndBounded() {
        val source = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.RED)
        try {
            val jpeg = CameraFrameEncoder.encode(source, rotationDegrees = 90)
            assertTrue(jpeg.size <= 128 * 1024)
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            assertTrue(decoded.height <= 640 && decoded.width < decoded.height)
            assertTrue(decoded.height > 300)
            decoded.recycle()
        } finally { source.recycle() }
    }
}
