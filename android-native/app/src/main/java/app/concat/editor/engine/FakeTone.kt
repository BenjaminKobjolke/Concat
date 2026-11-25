// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.engine

import java.io.File

internal object FakeTone {
    /** A quiet hum, as a 16-bit mono WAV, standing in for a voice. */
    fun write(file: File, seconds: Double) {
        val rate = 16_000
        val samples = (seconds * rate).toInt()
        val data = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(samples * 2)
        for (i in 0 until samples) {
            val t = i.toDouble() / rate
            val envelope = 0.5 + 0.5 * kotlin.math.sin(2 * Math.PI * 3 * t)
            data.putShort((kotlin.math.sin(2 * Math.PI * 180 * t) * 3000 * envelope).toInt().toShort())
        }
        file.parentFile?.mkdirs()
        file.writeBytes(data.array())
    }
}
