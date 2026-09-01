package com.grrrrshadow.ulzvu.core

/** In-place iterative radix-2 Cooley-Tukey FFT. `re`/`im` length must be a power of two. */
object FFT {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }
        if (n <= 1) return

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = Math.cos(ang)
            val wIm = Math.sin(ang)
            var i = 0
            while (i < n) {
                var curWRe = 1.0
                var curWIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curWRe - im[i + k + len / 2] * curWIm
                    val vIm = re[i + k + len / 2] * curWIm + im[i + k + len / 2] * curWRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextWRe = curWRe * wRe - curWIm * wIm
                    val nextWIm = curWRe * wIm + curWIm * wRe
                    curWRe = nextWRe
                    curWIm = nextWIm
                }
                i += len
            }
            len = len shl 1
        }
    }
}
