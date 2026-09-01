package com.grrrrshadow.ulzvu

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build

data class AudioConfig(
    val sampleRateHz: Int,
    val source: Int,
    val sourceName: String,
    val minBufferBytes: Int
)

/**
 * OS audio drivers, not just app code, decide the real sample-rate ceiling and whether
 * DSP source-processing (AGC, noise suppression, the anti-alias chain) can be bypassed.
 * This probes what the specific device/ROM actually honors instead of assuming a value.
 */
object AudioProber {

    private val CANDIDATE_SAMPLE_RATES = intArrayOf(192000, 96000, 48000, 44100)

    fun probe(context: Context): AudioConfig? {
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val supportsUnprocessed =
                    am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
                if (supportsUnprocessed) add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
            }
            add(MediaRecorder.AudioSource.MIC to "MIC")
        }

        for (rate in CANDIDATE_SAMPLE_RATES) {
            for ((source, name) in sources) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue

                val record = try {
                    AudioRecord(
                        source, rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
                    )
                } catch (_: SecurityException) {
                    return null
                } catch (_: IllegalArgumentException) {
                    continue
                }

                val initialized = record.state == AudioRecord.STATE_INITIALIZED
                record.release()

                if (initialized) {
                    return AudioConfig(rate, source, name, minBuf * 4)
                }
            }
        }
        return null
    }
}
