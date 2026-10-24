package com.openclawchip.jarvis.vision

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/** Foreground preview + at most two small JPEGs/second. Always closes ImageProxy, even on failure. */
class CameraStream(
    private val context: Context,
    private val lifecycle: LifecycleOwner,
    private val previewView: PreviewView,
    private val front: Boolean,
    private val onJpeg: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-camera") }
    private val capturePolicy = CameraFramePolicy(SystemClock::elapsedRealtime)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var closed = false

    @SuppressLint("MissingPermission")
    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                check(cameraProvider.hasCamera(selector))
                val viewfinder = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val images = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .build()
                preview = viewfinder; analysis = images
                images.setAnalyzer(executor) { image ->
                    try {
                        if (!closed && capturePolicy.allow(1, true, 0)) {
                            val bitmap = image.toBitmap()
                            val jpeg = try { CameraFrameEncoder.encode(bitmap, image.imageInfo.rotationDegrees) } finally { bitmap.recycle() }
                            if (!closed) onJpeg(jpeg)
                        }
                    } catch (_: Exception) {
                        if (!closed) context.mainExecutor.execute { if (!closed) onError("Camera frame unavailable. Try restarting the camera.") }
                    } finally { image.close() }
                }
                cameraProvider.bindToLifecycle(lifecycle, selector, viewfinder, images)
            } catch (_: Exception) {
                if (!closed) onError("Camera unavailable. Close other camera apps and try again.")
                close()
            }
        }, context.mainExecutor)
    }

    override fun close() {
        if (closed) return
        closed = true
        analysis?.clearAnalyzer()
        val uses = listOfNotNull(preview, analysis).toTypedArray()
        if (uses.isNotEmpty()) provider?.unbind(*uses)
        preview = null; analysis = null; provider = null
        executor.shutdown()
    }
}
