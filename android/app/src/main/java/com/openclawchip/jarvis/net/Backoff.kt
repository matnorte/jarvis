package com.openclawchip.jarvis.net

class Backoff(private val steps: List<Long> = listOf(1000, 2000, 4000, 8000, 16000, 30000)) {
    private var index = 0

    fun next(): Long {
        val v = steps[minOf(index, steps.lastIndex)]
        if (index < steps.size) index++
        return v
    }

    fun reset() {
        index = 0
    }
}
