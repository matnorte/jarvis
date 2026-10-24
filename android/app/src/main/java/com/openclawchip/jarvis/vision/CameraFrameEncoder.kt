package com.openclawchip.jarvis.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/** Rotate actual pixels and re-encode, with no camera EXIF/location metadata. No disk writes. */
object CameraFrameEncoder {
    fun encode(source: Bitmap, rotationDegrees: Int): ByteArray {
        var current = if (rotationDegrees % 360 == 0) source else Bitmap.createBitmap(source,
            0, 0, source.width, source.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
        try {
            val edge = maxOf(current.width, current.height)
            if (edge > 640) {
                val smaller = Bitmap.createScaledBitmap(current, maxOf(1, current.width * 640 / edge), maxOf(1, current.height * 640 / edge), true)
                if (current !== source) current.recycle()
                current = smaller
            }
            while (true) {
                for (quality in listOf(70, 55, 40)) {
                    val output = ByteArrayOutputStream()
                    current.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    if (output.size() <= CameraFramePolicy.MAX_JPEG_BYTES) return output.toByteArray()
                }
                val smaller = Bitmap.createScaledBitmap(current, maxOf(1, current.width * 3 / 4), maxOf(1, current.height * 3 / 4), true)
                if (current !== source) current.recycle()
                current = smaller
            }
        } finally { if (current !== source) current.recycle() }
    }
}
