package com.grrrrshadow.ulzvu.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectrumAnalyzerTest {

    private fun sineWave(freqHz: Double, sampleRateHz: Int, n: Int): ShortArray =
        ShortArray(n) { i -> (sin(2.0 * PI * freqHz * i / sampleRateHz) * 20000).toInt().toShort() }

    @Test
    fun `detects peak of a 21 kHz tone near the Nyquist edge of a 48 kHz recording`() {
        val sampleRate = 48000
        val fftSize = 2048
        val analyzer = SpectrumAnalyzer(fftSize, sampleRate)
        val samples = sineWave(21000.0, sampleRate, fftSize)
        val db = DoubleArray(analyzer.binCount)
        analyzer.analyze(samples, db)

        val (peakFreq, peakDb) = analyzer.peakInRange(db, 0.0, sampleRate / 2.0)

        assertTrue(
            abs(peakFreq - 21000.0) <= analyzer.binWidthHz,
            "expected peak near 21000 Hz, got $peakFreq Hz (bin width ${analyzer.binWidthHz})"
        )
        assertTrue(peakDb > -20.0, "peak should be strong, got $peakDb dB")
    }

    @Test
    fun `detects peak of a 1 kHz audible tone`() {
        val sampleRate = 48000
        val fftSize = 2048
        val analyzer = SpectrumAnalyzer(fftSize, sampleRate)
        val samples = sineWave(1000.0, sampleRate, fftSize)
        val db = DoubleArray(analyzer.binCount)
        analyzer.analyze(samples, db)

        val (peakFreq, _) = analyzer.peakInRange(db, 0.0, sampleRate / 2.0)
        assertTrue(abs(peakFreq - 1000.0) <= analyzer.binWidthHz)
    }

    @Test
    fun `wav header has correct RIFF and data chunk sizes`() {
        val header = WavHeader.build(sampleRateHz = 48000, pcmDataBytes = 1000)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4))
        assertEquals("WAVE", String(header, 8, 4))
        assertEquals("data", String(header, 36, 4))

        val riffSize = (header[4].toInt() and 0xff) or
            ((header[5].toInt() and 0xff) shl 8) or
            ((header[6].toInt() and 0xff) shl 16) or
            ((header[7].toInt() and 0xff) shl 24)
        assertEquals(1036, riffSize)
    }
}
