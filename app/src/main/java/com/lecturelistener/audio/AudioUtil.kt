package com.lecturelistener.audio

import kotlin.math.sqrt

object AudioUtil {

    /** 计算 16-bit PCM 帧的 RMS 能量值 */
    fun rms(data: ByteArray, len: Int): Double {
        if (len < 2) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val s = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        return sqrt(sum / (len / 2))
    }
}
