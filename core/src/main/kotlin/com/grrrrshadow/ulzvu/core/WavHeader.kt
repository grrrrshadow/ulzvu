package com.grrrrshadow.ulzvu.core

object WavHeader {
    /** Standard 44-byte PCM WAV header for mono 16-bit audio. */
    fun build(sampleRateHz: Int, pcmDataBytes: Int): ByteArray {
        val byteRate = sampleRateHz * 2 // mono * 16-bit
        val header = ByteArray(44)

        fun writeStr(offset: Int, s: String) { s.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() } }
        fun writeIntLE(offset: Int, v: Int) {
            header[offset] = (v and 0xff).toByte()
            header[offset + 1] = (v shr 8 and 0xff).toByte()
            header[offset + 2] = (v shr 16 and 0xff).toByte()
            header[offset + 3] = (v shr 24 and 0xff).toByte()
        }
        fun writeShortLE(offset: Int, v: Int) {
            header[offset] = (v and 0xff).toByte()
            header[offset + 1] = (v shr 8 and 0xff).toByte()
        }

        writeStr(0, "RIFF")
        writeIntLE(4, 36 + pcmDataBytes)
        writeStr(8, "WAVE")
        writeStr(12, "fmt ")
        writeIntLE(16, 16)
        writeShortLE(20, 1)
        writeShortLE(22, 1)
        writeIntLE(24, sampleRateHz)
        writeIntLE(28, byteRate)
        writeShortLE(32, 2)
        writeShortLE(34, 16)
        writeStr(36, "data")
        writeIntLE(40, pcmDataBytes)
        return header
    }
}
