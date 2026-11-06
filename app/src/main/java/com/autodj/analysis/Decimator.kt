package com.autodj.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streaming low-pass + integer-factor downsampler used to bring decoded audio down to
 * ~11 kHz mono for analysis. Feed mono samples with [push]; read results from [output].
 */
class Decimator(inputRate: Int, targetRate: Int = 11025) {
    val factor: Int = maxOf(1, (inputRate.toDouble() / targetRate).roundToInt())
    val outputRate: Int = inputRate / factor
    private val taps: FloatArray
    private val history: FloatArray
    private var pos = 0
    private var phase = 0
    val output = FloatBuffer()

    init {
        val n = if (factor == 1) 1 else 16 * factor + 1
        taps = FloatArray(n)
        if (n == 1) {
            taps[0] = 1f
        } else {
            val fc = 0.45 / factor
            val mid = (n - 1) / 2
            var sum = 0.0
            for (i in 0 until n) {
                val k = i - mid
                val sinc = if (k == 0) 2 * fc else sin(2 * PI * fc * k) / (PI * k)
                val w = 0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1))
                taps[i] = (sinc * w).toFloat()
                sum += taps[i]
            }
            for (i in 0 until n) taps[i] = (taps[i] / sum).toFloat()
        }
        history = FloatArray(n)
    }

    fun push(sample: Float) {
        history[pos] = sample
        pos = (pos + 1) % history.size
        phase++
        if (phase == factor) {
            phase = 0
            var acc = 0f
            var idx = pos
            for (t in taps.indices) {
                acc += taps[t] * history[idx]
                idx++
                if (idx == history.size) idx = 0
            }
            output.add(acc)
        }
    }
}

/** Growable float array. */
class FloatBuffer(initial: Int = 1 shl 16) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
