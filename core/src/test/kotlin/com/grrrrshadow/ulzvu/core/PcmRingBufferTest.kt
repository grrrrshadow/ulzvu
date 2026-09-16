package com.grrrrshadow.ulzvu.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PcmRingBufferTest {

    @Test
    fun `empty buffer has no snapshot`() {
        val ring = PcmRingBuffer(16)
        assertNull(ring.snapshot())
        assertEquals(0, ring.filled)
    }

    @Test
    fun `partially filled buffer keeps write order`() {
        val ring = PcmRingBuffer(8)
        ring.write(shortArrayOf(1, 2, 3))
        assertEquals(3, ring.filled)
        assertContentEquals(shortArrayOf(1, 2, 3), ring.snapshot())
    }

    @Test
    fun `wrapped buffer returns oldest sample first`() {
        val ring = PcmRingBuffer(4)
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6))
        // capacity 4, so 1 and 2 were overwritten and 3..6 remain, oldest first
        assertEquals(4, ring.filled)
        assertContentEquals(shortArrayOf(3, 4, 5, 6), ring.snapshot())
    }

    @Test
    fun `write longer than capacity keeps only the tail`() {
        val ring = PcmRingBuffer(3)
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertContentEquals(shortArrayOf(5, 6, 7), ring.snapshot())
    }

    @Test
    fun `chunked chronological read matches snapshot`() {
        val ring = PcmRingBuffer(10)
        // fill past the wrap point so the read has to stitch two segments together
        ring.write(ShortArray(17) { (it + 1).toShort() })
        val expected = ring.snapshot()!!
        assertEquals(10, expected.size)

        val chunk = ShortArray(3)
        val collected = ArrayList<Short>()
        var pos = 0
        while (true) {
            val n = ring.readChronological(pos, chunk, chunk.size)
            if (n == 0) break
            for (i in 0 until n) collected.add(chunk[i])
            pos += n
        }
        assertContentEquals(expected, collected.toShortArray())
    }

    @Test
    fun `read past the end returns nothing`() {
        val ring = PcmRingBuffer(4)
        ring.write(shortArrayOf(1, 2))
        assertEquals(0, ring.readChronological(2, ShortArray(4), 4))
        assertEquals(0, ring.readChronological(99, ShortArray(4), 4))
    }

    @Test
    fun `partial write length is honoured`() {
        val ring = PcmRingBuffer(8)
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8), length = 3)
        assertContentEquals(shortArrayOf(1, 2, 3), ring.snapshot())
    }

    @Test
    fun `clear drops retained samples`() {
        val ring = PcmRingBuffer(4)
        ring.write(shortArrayOf(1, 2, 3, 4, 5))
        ring.clear()
        assertNull(ring.snapshot())
        ring.write(shortArrayOf(9))
        assertContentEquals(shortArrayOf(9), ring.snapshot())
    }

    @Test
    fun `five minute loop at 192 kHz reports its real footprint`() {
        val samples = 192_000 * 300
        val ring = PcmRingBuffer(samples)
        assertEquals(115_200_000L, ring.capacityBytes)
        assertEquals(samples.toLong() * 2, ring.capacityBytes)
        // 109.9 MiB -- comfortably under a 4 GB device's ~192 MB heap cap only because this
        // lives in native memory; the same size as a ShortArray would be asking for trouble
        assertEquals(110L, Math.round(ring.capacityBytes / (1024.0 * 1024.0)))
    }
}
