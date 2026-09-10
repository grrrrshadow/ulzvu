package com.grrrrshadow.ulzvu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

private const val MYNOISE_PACKAGE = "com.mynoise.mynoise"
const val PLAYBACK_SAMPLE_RATE_HZ = 48000
private const val PLAYBACK_BUFFER_SECONDS = 30

private const val NOTIFICATION_CHANNEL_ID = "ulzvu_running"
private const val NOTIFICATION_ID = 2

const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
const val EXTRA_PROJECTION_RESULT_DATA = "projection_result_data"

/**
 * A foreground service dedicated to AudioPlaybackCapture, kept fully separate from
 * UlzvuService (which only ever declares "microphone"). Android requires user consent
 * (via MediaProjectionManager.createScreenCaptureIntent()) to exist BEFORE a service
 * declaring the "mediaProjection" type is even started -- folding that type into the
 * always-on mic service made every "Spustit analýzu" press crash, since no consent had
 * been requested yet at that point.
 */
class PlaybackCaptureService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackCaptureService = this@PlaybackCaptureService
    }

    private val binder = LocalBinder()

    @Volatile var playbackCaptureActive: Boolean = false
        private set
    @Volatile var playbackCaptureErrorText: String? = null
        private set

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var buffer: ShortArray? = null
    private var writePos = 0
    private var filledCount = 0
    private val bufferLock = Any()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            disableCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_PROJECTION_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_PROJECTION_RESULT_DATA)
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (resultData == null) {
            EventLog.log(LogLevel.ERROR, "Playback", "PlaybackCaptureService spuštěna bez MediaProjection dat")
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, resultData)
        startCapture(projection)
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.mynoise_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun startCapture(projection: MediaProjection) {
        val uid = try {
            packageManager.getApplicationInfo(MYNOISE_PACKAGE, 0).uid
        } catch (e: PackageManager.NameNotFoundException) {
            playbackCaptureErrorText = getString(R.string.mynoise_not_installed)
            EventLog.log(LogLevel.ERROR, "Playback", "My Noise ($MYNOISE_PACKAGE) není nainstalovaná")
            projection.stop()
            stopSelfCleanly()
            return
        }

        val captureConfig = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUid(uid)
                .build()
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Playback", "AudioPlaybackCaptureConfiguration selhala", e)
            playbackCaptureErrorText = getString(R.string.mynoise_capture_failed)
            projection.stop()
            stopSelfCleanly()
            return
        }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(PLAYBACK_SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            EventLog.log(LogLevel.ERROR, "Playback", "getMinBufferSize selhal pro playback capture")
            playbackCaptureErrorText = getString(R.string.mynoise_capture_failed)
            projection.stop()
            stopSelfCleanly()
            return
        }

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Playback", "AudioRecord (playback capture) selhal", e)
            playbackCaptureErrorText = getString(R.string.mynoise_capture_failed)
            projection.stop()
            stopSelfCleanly()
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            EventLog.log(LogLevel.ERROR, "Playback", "AudioRecord (playback) se neinicializoval (state=${record.state})")
            playbackCaptureErrorText = getString(R.string.mynoise_capture_failed)
            projection.stop()
            stopSelfCleanly()
            return
        }

        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        synchronized(bufferLock) {
            buffer = ShortArray(PLAYBACK_SAMPLE_RATE_HZ * PLAYBACK_BUFFER_SECONDS)
            writePos = 0
            filledCount = 0
        }

        audioRecord = record
        playbackCaptureActive = true
        record.startRecording()

        captureThread = thread(name = "ulzvu-playback") {
            val buf = ShortArray(2048)
            while (playbackCaptureActive) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    synchronized(bufferLock) {
                        val b = buffer ?: return@synchronized
                        for (i in 0 until read) {
                            b[writePos] = buf[i]
                            writePos = (writePos + 1) % b.size
                            if (filledCount < b.size) filledCount++
                        }
                    }
                }
            }
        }

        EventLog.log(LogLevel.INFO, "Playback", "Záznam My Noise spuštěn (uid=$uid)")
    }

    fun consumePlaybackError(): String? {
        val s = playbackCaptureErrorText
        playbackCaptureErrorText = null
        return s
    }

    /** Copies the ring buffer's valid samples out in chronological order, for saving. */
    fun extractCurrentBuffer(): ShortArray? {
        return synchronized(bufferLock) {
            val b = buffer ?: return@synchronized null
            if (filledCount == 0) return@synchronized null
            val out = ShortArray(filledCount)
            if (filledCount < b.size) {
                System.arraycopy(b, 0, out, 0, filledCount)
            } else {
                val tail = b.size - writePos
                System.arraycopy(b, writePos, out, 0, tail)
                System.arraycopy(b, 0, out, tail, writePos)
            }
            out
        }
    }

    fun disableCapture() {
        if (!playbackCaptureActive) {
            stopSelfCleanly()
            return
        }
        playbackCaptureActive = false
        captureThread?.join(500)
        captureThread = null
        try {
            audioRecord?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Playback", "Chyba při zastavování playback AudioRecord", e)
        }
        audioRecord = null
        synchronized(bufferLock) { buffer = null }
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            // best effort -- projection may already be gone
        }
        mediaProjection = null
        EventLog.log(LogLevel.INFO, "Playback", "Záznam My Noise zastaven")
        stopSelfCleanly()
    }

    private fun stopSelfCleanly() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (playbackCaptureActive) disableCapture()
    }
}
