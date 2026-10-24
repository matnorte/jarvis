package com.openclawchip.jarvis.audio

interface WakeWord : AutoCloseable {
    /** Feed one frame (512 samples @ 16 kHz); true when the wake word was detected. */
    fun process(frame: ShortArray): Boolean
}

/** Wake-word engine could not start. Never includes credentials or native exception details. */
class WakeWordUnavailable(reason: String) : Exception(reason)
