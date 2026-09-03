package com.grrrrshadow.ulzvu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
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

    private var config: AudioConfig? = null
    private var audioRecord: AudioRecord? = null
    private var analysisThread: Thread? = null

    private var rewindBuffer: ShortArray? = null
    private var rewindWritePos = 0
    private var rewindFilledCount = 0
    private val rewindLock = Any()

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

    /** One tap saves both the last REWIND_BUFFER_SECONDS of audio and the log entries from
     * that same window, as a matched pair of files sharing one timestamp. Runs on its own
     * thread so a slow save can't jank whatever's driving the UI. */
    fun requestSaveRewindBundle() {
        thread(name = "ulzvu-save") { saveRewindBundle() }
    }

    private fun saveRewindBundle() {
        val cfg = config
        if (cfg == null) {
            lastSaveStatusText = getString(R.string.rewind_failed)
            return
        }

        val linear: ShortArray? = synchronized(rewindLock) {
            val rb = rewindBuffer ?: return@synchronized null
            if (rewindFilledCount == 0) return@synchronized null
            val out = ShortArray(rewindFilledCount)
            if (rewindFilledCount < rb.size) {
                System.arraycopy(rb, 0, out, 0, rewindFilledCount)
            } else {
                val tail = rb.size - rewindWritePos
                System.arraycopy(rb, rewindWritePos, out, 0, tail)
                System.arraycopy(rb, 0, out, tail, rewindWritePos)
            }
            out
        }

        if (linear == null) {
            lastSaveStatusText = getString(R.string.rewind_failed)
            EventLog.log(LogLevel.WARN, "Rewind", "Kruhový buffer je zatím prázdný")
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val audioName = saveRewindAudioToDownloads(linear, cfg.sampleRateHz, timestamp)

        val cutoffMs = System.currentTimeMillis() - REWIND_BUFFER_SECONDS * 1000L
        val logLines = EventLog.snapshot().filter { it.timestampMs >= cutoffMs }.map { EventLog.format(it) }
        val logName = saveLogSliceToDownloads(logLines, timestamp)

        lastSaveStatusText = if (audioName != null && logName != null) {
            getString(R.string.saved_pair_format, audioName, logName)
        } else {
            getString(R.string.rewind_failed)
        }
    }

    private fun saveRewindAudioToDownloads(samples: ShortArray, sampleRateHz: Int, timestamp: String): String? {
        val displayName = "zvuk_$timestamp.wav"

        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
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
            val pcmBytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                samples.forEach { putShort(it) }
            }.array()
            stream.use { out ->
                out.write(WavHeader.build(sampleRateHz, pcmBytes.size))
                out.write(pcmBytes)
            }
            displayName
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Rewind", "Zpětné uložení WAV selhalo", e)
            null
        }
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
    }
}
