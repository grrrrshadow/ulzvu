package com.grrrrshadow.ulzvu.core

class SpectrumAnalyzer(private val fftSize: Int, sampleRateHz: Int) {

    init {
        require(fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
    }

    private val hannWindow = DoubleArray(fftSize) { i ->
        0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (fftSize - 1))
    }
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)

    val binCount = fftSize / 2
    val binWidthHz: Double = sampleRateHz.toDouble() / fftSize

    fun frequencyOfBin(bin: Int): Double = bin * binWidthHz

    /** samples: PCM16 mono, length must equal fftSize. Writes magnitude spectrum in dB into outDb. */
    fun analyze(samples: ShortArray, outDb: DoubleArray) {
        require(samples.size == fftSize)
        for (i in 0 until fftSize) {
            re[i] = (samples[i] / 32768.0) * hannWindow[i]
            im[i] = 0.0
        }
        computeDb(outDb)
    }

    /** samples: any already-centered signal (roughly -1..1), length must equal fftSize. */
    fun analyze(samples: DoubleArray, outDb: DoubleArray) {
        require(samples.size == fftSize)
        for (i in 0 until fftSize) {
            re[i] = samples[i] * hannWindow[i]
            im[i] = 0.0
        }
        computeDb(outDb)
    }

    private fun computeDb(outDb: DoubleArray) {
        require(outDb.size == binCount)
        FFT.transform(re, im)
        for (bin in 0 until binCount) {
            val mag = Math.hypot(re[bin], im[bin]) / fftSize
            outDb[bin] = 20.0 * Math.log10(mag.coerceAtLeast(1e-12))
        }
    }

    fun peakInRange(db: DoubleArray, minHz: Double, maxHz: Double): Pair<Double, Double> {
        val loBin = (minHz / binWidthHz).toInt().coerceIn(0, db.size - 1)
        val hiBin = (maxHz / binWidthHz).toInt().coerceIn(0, db.size - 1)
        var bestBin = loBin
        for (b in loBin..hiBin) if (db[b] > db[bestBin]) bestBin = b
        return frequencyOfBin(bestBin) to db[bestBin]
    }
}
