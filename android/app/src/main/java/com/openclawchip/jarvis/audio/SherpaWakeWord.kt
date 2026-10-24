package com.openclawchip.jarvis.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/** Offline JARVIS keyword spotting. No access key, network or account. */
class SherpaWakeWord internal constructor(private val engine: Engine) : WakeWord {
    internal interface Engine : AutoCloseable { fun process(samples: FloatArray): Boolean }
    constructor(context: Context, sensitivity: Float) : this(nativeEngine(context, sensitivity))
    private var closed = false

    @Synchronized override fun process(frame: ShortArray): Boolean {
        if (closed) return false
        require(frame.size == 512)
        return engine.process(FloatArray(frame.size) { frame[it] / 32768f })
    }
    @Synchronized override fun close() {
        if (!closed) { closed = true; engine.close() }
    }
    private companion object {
        fun nativeEngine(context: Context, sensitivity: Float): Engine {
            require(sensitivity in 0f..1f)
            val spotter = try {
                KeywordSpotter(context.applicationContext.assets, KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "wake/encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx",
                            decoder = "wake/decoder-epoch-13-avg-2-chunk-8-left-64.onnx",
                            joiner = "wake/joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx"),
                        tokens = "wake/tokens.txt", numThreads = 1, provider = "cpu", debug = false,
                        modelType = "zipformer2", modelingUnit = "phone+ppinyin"),
                    keywordsFile = "wake/keywords.txt", keywordsThreshold = 0.55f - sensitivity * 0.5f,
                    keywordsScore = 1.5f, numTrailingBlanks = 1))
            } catch (_: Exception) { throw WakeWordUnavailable("Offline wake engine unavailable") }
            val stream = try { spotter.createStream() } catch (_: Exception) {
                spotter.release(); throw WakeWordUnavailable("Offline wake stream unavailable")
            }
            return object : Engine {
                override fun process(samples: FloatArray): Boolean {
                    stream.acceptWaveform(samples, sampleRate = 16000)
                    var wake = false
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        if (spotter.getResult(stream).keyword.isNotBlank()) { wake = true; spotter.reset(stream) }
                    }
                    return wake
                }
                override fun close() { stream.release(); spotter.release() }
            }
        }
    }
}
