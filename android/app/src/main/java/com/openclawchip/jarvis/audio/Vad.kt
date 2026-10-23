package com.openclawchip.jarvis.audio

interface Vad : AutoCloseable {
    /** Frame of 512 samples @ 16 kHz. */
    fun isSpeech(frame: ShortArray): Boolean
}
