package com.grrrrshadow.ulzvu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.grrrrshadow.ulzvu.core.SpectrumAnalyzer
import com.grrrrshadow.ulzvu.core.WavHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

private const val FFT_SIZE = 2048
private const val ULTRASOUND_BAND_START_HZ = 17000.0
private const val DETECTION_MARGIN_DB = 12.0
private const val NOISE_FLOOR_ALPHA = 0.01
private const val DETECTION_HOLD_FRAMES = 5 // consecutive frames required before flipping the alert state

private const val HR_WINDOW_SIZE = 512 // ~10 s of samples at the ~50 Hz SENSOR_DELAY_GAME rate
private const val HR_MIN_HZ = 0.7 // 42 BPM
private const val HR_MAX_HZ = 3.5 // 210 BPM
private const val HR_MOTION_STDDEV_THRESHOLD = 1.2 // m/s^2 -- hand shake vs. pulse-scale vibration
private const val HR_UPDATE_INTERVAL_MS = 500L

private const val REWIND_BUFFER_SECONDS = 30

private const val NOTIFICATION_CHANNEL_ID = "ulzvu_running"
private const val NOTIFICATION_ID = 1

private const val MYNOISE_PACKAGE = "com.mynoise.mynoise"
private const val PLAYBACK_SAMPLE_RATE_HZ = 48000

/**
 * Android revokes microphone access from a plain background thread within a few seconds of
 * the app losing foreground focus -- switching to another app silently stops AudioRecord.
 * Running the capture inside a foreground service (with the required Android 14 "microphone"
 * service type) keeps it alive regardless of what's on screen; MainActivity just binds to
 * this while visible to mirror its state into the UI.
 */
class UlzvuService : Service(), SensorEventListener {

    inner class LocalBinder : Binder() {
        fun getService(): UlzvuService = this@UlzvuService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var running = false
        private set
    @Volatile var configText: String = ""
        private set
    @Volatile var latestSpectrumDb: DoubleArray? = null
        private set
    @Volatile var latestBinWidthHz: Double = 0.0
        private set
    @Volatile var peakText: String = ""
        private set
    @Volatile var alertActive: Boolean = false
        private set
    @Volatile var alertText: String = ""
        private set
    @Volatile var lastIncidentText: String? = null
        private set
    @Volatile var bpmText: String = ""
        private set
    @Volatile var motionWarningActive: Boolean = false
        private set
    @Volatile var lastSaveStatusText: String? = null
        private set
    @Volatile var playbackCaptureActive: Boolean = false
        private set
    @Volatile var playbackCaptureErrorText: String? = null
        private set

    private var config: AudioConfig? = null
    private var audioRecord: AudioRecord? = null
    private var analysisThread: Thread? = null

    private var rewindBuffer: ShortArray? = null
    private var rewindWritePos = 0
    private var rewindFilledCount = 0
    private val rewindLock = Any()

    // second, independent capture: what a specific app (My Noise) is playing to the
    // headphones, via AudioPlaybackCapture -- a separate 30 s ring buffer so it can be
    // compared against the room's mic pickup in the same saved bundle
    private var mediaProjection: MediaProjection? = null
    private var playbackAudioRecord: AudioRecord? = null
    private var playbackThread: Thread? = null
    private var playbackBuffer: ShortArray? = null
    private var playbackWritePos = 0
    private var playbackFilledCount = 0
    private val playbackLock = Any()
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            disablePlaybackCapture()
        }
    }

    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private val hrTimestampsNs = ArrayDeque<Long>()
    private val hrMagnitudes = ArrayDeque<Double>()
    private val hrBufferLock = Any()
    @Volatile private var lastBpmText: String = "–"
    private val hrUpdateRunnable = object : Runnable {
        override fun run() {
            computeAndDisplayBpm()
            if (running) mainHandler.postDelayed(this, HR_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        EventLog.init(applicationContext)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!running) startCapture()
        return START_NOT_STICKY
    }

    fun consumeSaveStatus(): String? {
        val s = lastSaveStatusText
        lastSaveStatusText = null
        return s
    }

    fun consumePlaybackError(): String? {
        val s = playbackCaptureErrorText
        playbackCaptureErrorText = null
        return s
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
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

    private fun startCapture() {
        val cfg = try {
            AudioProber.probe(this)
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Audio", "AudioProber.probe selhal", e)
            null
        }
        if (cfg == null) {
            EventLog.log(LogLevel.WARN, "Audio", "Žádná funkční kombinace vzorkování/zdroje nenalezena")
            stopSelfCleanly()
            return
        }
        config = cfg

        val nyquist = cfg.sampleRateHz / 2
        configText = getString(R.string.config_format, cfg.sampleRateHz, cfg.sourceName, nyquist)
        EventLog.log(LogLevel.INFO, "Audio", "Konfigurace: ${cfg.sampleRateHz} Hz, zdroj ${cfg.sourceName}, Nyquist $nyquist Hz")

        val record = try {
            AudioRecord(
                cfg.source, cfg.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                cfg.minBufferBytes
            )
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Audio", "AudioRecord() selhal", e)
            stopSelfCleanly()
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            EventLog.log(LogLevel.ERROR, "Audio", "AudioRecord se neinicializoval (state=${record.state})")
            stopSelfCleanly()
            return
        }
        audioRecord = record

        synchronized(rewindLock) {
            rewindBuffer = ShortArray(cfg.sampleRateHz * REWIND_BUFFER_SECONDS)
            rewindWritePos = 0
            rewindFilledCount = 0
        }

        running = true
        record.startRecording()

        analysisThread = thread(name = "ulzvu-analysis") {
            try {
                analysisLoop(record, cfg.sampleRateHz)
            } catch (e: Exception) {
                EventLog.log(LogLevel.ERROR, "Audio", "Analyzační smyčka spadla", e)
            }
        }

        startHeartRate()
    }

    fun stopCapture() {
        running = false
        analysisThread?.join(500)
        analysisThread = null
        try {
            audioRecord?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Audio", "Chyba při zastavování AudioRecord", e)
        }
        audioRecord = null
        synchronized(rewindLock) { rewindBuffer = null }
        EventLog.log(LogLevel.INFO, "Audio", "Analýza zastavena")

        stopHeartRate()
        disablePlaybackCapture()
        stopSelfCleanly()
    }

    private fun stopSelfCleanly() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun analysisLoop(record: AudioRecord, sampleRateHz: Int) {
        val analyzer = SpectrumAnalyzer(FFT_SIZE, sampleRateHz)
        val noiseFloor = DoubleArray(FFT_SIZE / 2) { -90.0 }
        val buffer = ShortArray(FFT_SIZE)
        val db = DoubleArray(FFT_SIZE / 2)

        var alertActiveLocal = false
        var consecutiveAbove = 0
        var consecutiveBelow = 0
        var incidentStartMs = 0L

        while (running) {
            var offset = 0
            while (offset < buffer.size && running) {
                val read = record.read(buffer, offset, buffer.size - offset)
                if (read <= 0) break
                offset += read
            }
            if (!running || offset < buffer.size) continue

            writeToRewindBuffer(buffer)

            analyzer.analyze(buffer, db)

            var frameAboveThreshold = false
            var frameFreq = 0.0
            var frameDb = -90.0
            for (b in db.indices) {
                val freq = analyzer.frequencyOfBin(b)
                if (freq >= ULTRASOUND_BAND_START_HZ && db[b] - noiseFloor[b] > DETECTION_MARGIN_DB) {
                    if (db[b] > frameDb) {
                        frameAboveThreshold = true
                        frameFreq = freq
                        frameDb = db[b]
                    }
                }
                noiseFloor[b] += (db[b] - noiseFloor[b]) * NOISE_FLOOR_ALPHA
            }

            if (frameAboveThreshold) {
                consecutiveAbove++
                consecutiveBelow = 0
            } else {
                consecutiveBelow++
                consecutiveAbove = 0
            }

            if (!alertActiveLocal && consecutiveAbove >= DETECTION_HOLD_FRAMES) {
                alertActiveLocal = true
                incidentStartMs = System.currentTimeMillis()
                val text = "ULTRAZVUK začal: ${"%.0f".format(frameFreq)} Hz @ ${"%.1f".format(frameDb)} dB · Tep: $lastBpmText"
                EventLog.log(LogLevel.WARN, "Detekce", text)
                lastIncidentText = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $text"
            } else if (alertActiveLocal && consecutiveBelow >= DETECTION_HOLD_FRAMES) {
                alertActiveLocal = false
                val durationSec = (System.currentTimeMillis() - incidentStartMs) / 1000.0
                val text = "ULTRAZVUK skončil: trval ${"%.2f".format(durationSec)} s · Tep: $lastBpmText"
                EventLog.log(LogLevel.WARN, "Detekce", text)
                lastIncidentText = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $text"
            }

            // ultrasound-band peak specifically -- a full-spectrum peak is dominated by
            // ordinary handling/voice noise and makes the readout jump around uselessly
            val (peakFreq, peakDb) = analyzer.peakInRange(db, ULTRASOUND_BAND_START_HZ, sampleRateHz / 2.0)

            latestSpectrumDb = db.copyOf()
            latestBinWidthHz = analyzer.binWidthHz
            peakText = getString(R.string.peak_format, peakFreq, peakDb)
            alertActive = alertActiveLocal
            alertText = if (alertActiveLocal) {
                getString(R.string.ultrasound_detected_format, frameFreq, frameDb)
            } else {
                getString(R.string.status_idle)
            }
        }
    }

    private fun writeToRewindBuffer(samples: ShortArray) {
        synchronized(rewindLock) {
            val rb = rewindBuffer ?: return
            for (s in samples) {
                rb[rewindWritePos] = s
                rewindWritePos = (rewindWritePos + 1) % rb.size
                if (rewindFilledCount < rb.size) rewindFilledCount++
            }
        }
    }

    /** Copies the valid samples out of a ring buffer in chronological order (oldest first). */
    private fun extractLinear(buffer: ShortArray?, writePos: Int, filledCount: Int): ShortArray? {
        if (buffer == null || filledCount == 0) return null
        val out = ShortArray(filledCount)
        if (filledCount < buffer.size) {
            System.arraycopy(buffer, 0, out, 0, filledCount)
        } else {
            val tail = buffer.size - writePos
            System.arraycopy(buffer, writePos, out, 0, tail)
            System.arraycopy(buffer, 0, out, tail, writePos)
        }
        return out
    }

    /** One tap saves the last REWIND_BUFFER_SECONDS of mic audio, the same window of My Noise
     * playback if that capture is on, and the log entries from that window -- a matched set
     * sharing one timestamp. Runs on its own thread so a slow save can't jank the UI. */
    fun requestSaveRewindBundle() {
        thread(name = "ulzvu-save") { saveRewindBundle() }
    }

    private fun saveRewindBundle() {
        val cfg = config
        if (cfg == null) {
            lastSaveStatusText = getString(R.string.rewind_failed)
            return
        }

        val linear = synchronized(rewindLock) { extractLinear(rewindBuffer, rewindWritePos, rewindFilledCount) }
        if (linear == null) {
            lastSaveStatusText = getString(R.string.rewind_failed)
            EventLog.log(LogLevel.WARN, "Rewind", "Kruhový buffer je zatím prázdný")
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val audioName = saveWavToDownloads(linear, cfg.sampleRateHz, "zvuk_$timestamp.wav", "Rewind")

        val playbackLinear = if (playbackCaptureActive) {
            synchronized(playbackLock) { extractLinear(playbackBuffer, playbackWritePos, playbackFilledCount) }
        } else null
        val playbackName = playbackLinear?.let {
            saveWavToDownloads(it, PLAYBACK_SAMPLE_RATE_HZ, "mynoise_$timestamp.wav", "Playback")
        }

        val cutoffMs = System.currentTimeMillis() - REWIND_BUFFER_SECONDS * 1000L
        val logLines = EventLog.snapshot().filter { it.timestampMs >= cutoffMs }.map { EventLog.format(it) }
        val logName = saveLogSliceToDownloads(logLines, timestamp)

        lastSaveStatusText = when {
            audioName == null || logName == null -> getString(R.string.rewind_failed)
            playbackName != null -> getString(R.string.saved_triple_format, audioName, playbackName, logName)
            else -> getString(R.string.saved_pair_format, audioName, logName)
        }
    }

    private fun saveWavToDownloads(samples: ShortArray, sampleRateHz: Int, displayName: String, logTag: String): String? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ulzvu")
            }

            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                EventLog.log(LogLevel.ERROR, logTag, "MediaStore.insert vrátil null pro $displayName")
                return null
            }

            val stream = contentResolver.openOutputStream(uri)
            if (stream == null) {
                EventLog.log(LogLevel.ERROR, logTag, "openOutputStream vrátil null pro $uri")
                return null
            }
            val pcmBytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                samples.forEach { putShort(it) }
            }.array()
            stream.use { out ->
                out.write(WavHeader.build(sampleRateHz, pcmBytes.size))
                out.write(pcmBytes)
            }
            displayName
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, logTag, "Uložení WAV selhalo ($displayName)", e)
            null
        }
    }

    /** Starts capturing only what com.mynoise.mynoise is playing (via AudioPlaybackCapture,
     * scoped to its UID so other apps' audio is never touched) into its own 30 s ring buffer.
     * `projection` must come from a screen-capture consent the Activity just obtained --
     * Android routes audio playback capture through that same consent flow, there's no
     * audio-only variant of it. */
    fun enablePlaybackCapture(projection: MediaProjection) {
        if (playbackCaptureActive) return

        val uid = try {
            packageManager.getApplicationInfo(MYNOISE_PACKAGE, 0).uid
        } catch (e: PackageManager.NameNotFoundException) {
            playbackCaptureErrorText = getString(R.string.mynoise_not_installed)
            EventLog.log(LogLevel.ERROR, "Playback", "My Noise ($MYNOISE_PACKAGE) není nainstalovaná")
            projection.stop()
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
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            EventLog.log(LogLevel.ERROR, "Playback", "AudioRecord (playback) se neinicializoval (state=${record.state})")
            playbackCaptureErrorText = getString(R.string.mynoise_capture_failed)
            projection.stop()
            return
        }

        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        synchronized(playbackLock) {
            playbackBuffer = ShortArray(PLAYBACK_SAMPLE_RATE_HZ * REWIND_BUFFER_SECONDS)
            playbackWritePos = 0
            playbackFilledCount = 0
        }

        playbackAudioRecord = record
        playbackCaptureActive = true
        record.startRecording()

        playbackThread = thread(name = "ulzvu-playback") {
            val buf = ShortArray(2048)
            while (playbackCaptureActive) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    synchronized(playbackLock) {
                        val pb = playbackBuffer ?: return@synchronized
                        for (i in 0 until read) {
                            pb[playbackWritePos] = buf[i]
                            playbackWritePos = (playbackWritePos + 1) % pb.size
                            if (playbackFilledCount < pb.size) playbackFilledCount++
                        }
                    }
                }
            }
        }

        EventLog.log(LogLevel.INFO, "Playback", "Záznam My Noise spuštěn (uid=$uid)")
    }

    fun disablePlaybackCapture() {
        if (!playbackCaptureActive) return
        playbackCaptureActive = false
        playbackThread?.join(500)
        playbackThread = null
        try {
            playbackAudioRecord?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Playback", "Chyba při zastavování playback AudioRecord", e)
        }
        playbackAudioRecord = null
        synchronized(playbackLock) { playbackBuffer = null }
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            // best effort -- projection may already be gone
        }
        mediaProjection = null
        EventLog.log(LogLevel.INFO, "Playback", "Záznam My Noise zastaven")
    }

    private fun saveLogSliceToDownloads(lines: List<String>, timestamp: String): String? {
        val displayName = "log_$timestamp.txt"

        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ulzvu")
            }

            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                EventLog.log(LogLevel.ERROR, "Rewind", "MediaStore.insert vrátil null pro $displayName")
                return null
            }

            val stream = contentResolver.openOutputStream(uri)
            if (stream == null) {
                EventLog.log(LogLevel.ERROR, "Rewind", "openOutputStream vrátil null pro $uri")
                return null
            }
            stream.use { out -> out.write(lines.joinToString("\n").toByteArray()) }
            displayName
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Rewind", "Uložení výřezu logu selhalo", e)
            null
        }
    }

    private fun startHeartRate() {
        val sensor = heartRateSensor ?: return
        synchronized(hrBufferLock) {
            hrTimestampsNs.clear()
            hrMagnitudes.clear()
        }
        val registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (!registered) {
            EventLog.log(LogLevel.ERROR, "HeartRate", "registerListener selhal pro ${sensor.name}")
            return
        }
        bpmText = getString(R.string.hr_unreliable)
        motionWarningActive = false
        EventLog.log(LogLevel.INFO, "HeartRate", "Měření spuštěno (${sensor.name})")
        mainHandler.post(hrUpdateRunnable)
    }

    private fun stopHeartRate() {
        sensorManager.unregisterListener(this)
        mainHandler.removeCallbacks(hrUpdateRunnable)
        if (heartRateSensor != null) EventLog.log(LogLevel.INFO, "HeartRate", "Měření zastaveno")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val mag = sqrt(
            (event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]).toDouble()
        )
        synchronized(hrBufferLock) {
            hrTimestampsNs.addLast(event.timestamp)
            hrMagnitudes.addLast(mag)
            while (hrMagnitudes.size > HR_WINDOW_SIZE) {
                hrTimestampsNs.removeFirst()
                hrMagnitudes.removeFirst()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun computeAndDisplayBpm() {
        val snapshot: Pair<DoubleArray, Double>? = synchronized(hrBufferLock) {
            if (hrMagnitudes.size < HR_WINDOW_SIZE) return@synchronized null
            val ts = hrTimestampsNs.toLongArray()
            val avgDtNs = (ts.last() - ts.first()).toDouble() / (ts.size - 1)
            if (avgDtNs <= 0) return@synchronized null
            hrMagnitudes.toDoubleArray() to (1_000_000_000.0 / avgDtNs)
        }
        val (samples, sampleRateHz) = snapshot ?: return

        val mean = samples.average()
        val stddev = sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)

        if (stddev > HR_MOTION_STDDEV_THRESHOLD) {
            motionWarningActive = true
            bpmText = getString(R.string.hr_unreliable)
            lastBpmText = "nespolehlivý (pohyb)"
            return
        }
        motionWarningActive = false

        val centered = DoubleArray(samples.size) { samples[it] - mean }
        val maxAbs = centered.maxOf { abs(it) }.coerceAtLeast(1e-6)
        val normalized = DoubleArray(centered.size) { centered[it] / maxAbs }

        val hrAnalyzer = SpectrumAnalyzer(HR_WINDOW_SIZE, sampleRateHz.toInt().coerceAtLeast(1))
        val db = DoubleArray(hrAnalyzer.binCount)
        hrAnalyzer.analyze(normalized, db)

        val (peakFreq, peakDb) = hrAnalyzer.peakInRange(db, HR_MIN_HZ, HR_MAX_HZ)
        val bpm = peakFreq * 60.0

        bpmText = getString(R.string.bpm_format, bpm)
        lastBpmText = "%.0f BPM".format(bpm)
        EventLog.log(
            LogLevel.INFO, "HeartRate",
            "Odhad ${"%.0f".format(bpm)} BPM (${"%.1f".format(peakDb)} dB, vzorkování ${"%.0f".format(sampleRateHz)} Hz)"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) stopCapture()
        disablePlaybackCapture()
    }
}
