package com.grrrrshadow.ulzvu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Ring buffer for mono 16-bit PCM, backed by a DIRECT ByteBuffer.
 *
 * The samples live in native memory instead of on the Java heap, which matters for the long
 * loop mode: 5 minutes at 192 kHz is 110 MB, while Android caps one app's heap at roughly
 * 192 MB on a 4 GB device and 256 MB on an 8 GB one (`dalvik.vm.heapgrowthlimit`). The same
 * amount held in a ShortArray would sit at well over half that cap and risk OutOfMemoryError
 * for no reason -- a direct buffer is not counted against it.
 *
 * Deliberately NOT internally synchronized: the caller serializes access (UlzvuService holds
 * one lock shared by the capture thread and the save thread), which keeps a whole save
 * streaming out of a consistent snapshot without copying the buffer a second time.
 */
class PcmRingBuffer(val capacitySamples: Int) {

    init {
        require(capacitySamples > 0) { "capacitySamples must be positive, got $capacitySamples" }
    }

    private val shorts: ShortBuffer = ByteBuffer
        .allocateDirect(capacitySamples * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()

    /** Where the next written sample goes. */
    var writePos = 0
        private set

    /** How many samples are currently retained (grows to capacitySamples, then stays). */
    var filled = 0
        private set

    val capacityBytes: Long get() = capacitySamples.toLong() * 2

    fun write(src: ShortArray, length: Int = src.size) {
        require(length <= src.size) { "length $length exceeds source size ${src.size}" }
        var offset = 0
        while (offset < length) {
            val n = minOf(length - offset, capacitySamples - writePos)
            shorts.position(writePos)
            shorts.put(src, offset, n)
            writePos = (writePos + n) % capacitySamples
            filled = minOf(capacitySamples, filled + n)
            offset += n
        }
    }

    /**
     * Copies out samples in chronological order, index 0 being the oldest sample still held.
     * Returns how many were actually copied (0 once `fromSample` is past the end).
     */
    fun readChronological(fromSample: Int, dest: ShortArray, count: Int): Int {
        if (fromSample < 0 || fromSample >= filled) return 0
        val total = minOf(count, dest.size, filled - fromSample)
        val oldest = if (filled < capacitySamples) 0 else writePos
        var read = 0
        while (read < total) {
            val pos = (oldest + fromSample + read) % capacitySamples
            val chunk = minOf(total - read, capacitySamples - pos)
            shorts.position(pos)
            shorts.get(dest, read, chunk)
            read += chunk
        }
        return total
    }

    /**
     * Whole contents as one array, oldest first, or null when nothing has been written yet.
     * Only for buffers small enough to sit on the heap -- the long loop is streamed to disk
     * with [readChronological] instead.
     */
    fun snapshot(): ShortArray? {
        if (filled == 0) return null
        val out = ShortArray(filled)
        readChronological(0, out, filled)
        return out
    }

    fun clear() {
        writePos = 0
        filled = 0
    }
}
