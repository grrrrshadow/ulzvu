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
import com.grrrrshadow.ulzvu.core.PcmRingBuffer
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
private const val LOOP_BUFFER_SECONDS = 300

/** Samples copied per disk write when streaming the ring buffer out to a WAV. */
private const val SAVE_CHUNK_SAMPLES = 32768

/** Samples read per AudioRecord call in loop mode, where no FFT dictates the frame size. */
private const val LOOP_READ_SAMPLES = 8192

private const val NOTIFICATION_CHANNEL_ID = "ulzvu_running"
private const val NOTIFICATION_ID = 1

const val EXTRA_CAPTURE_MODE = "com.grrrrshadow.ulzvu.CAPTURE_MODE"

/**
 * The two capture modes are mutually exclusive by design, not by accident: analysis runs an
 * FFT on every 2048-sample frame (~94 per second at 192 kHz), which is pointless CPU burn
 * when the user only wants a long raw window of the surroundings, and the 5-minute loop needs
 * ten times the buffer memory that analysis does.
 */
enum class CaptureMode { ANALYSIS, LOOP }

/**
 * Android revokes microphone access from a plain background thread within a few seconds of
 * the app losing foreground focus -- switching to another app silently stops AudioRecord.
 * Running the capture inside a foreground service (with the required Android 14 "microphone"
 * service type) keeps it alive regardless of what's on screen; MainActivity just binds to
 * this while visible to mirror its state into the UI.
 *
 * This service intentionally declares ONLY the "microphone" type -- My Noise playback
 * capture lives in the separate PlaybackCaptureService, because Android requires a valid
 * MediaProjection consent to already exist before a "mediaProjection"-typed service can even
 * start; folding both types into one service made every plain "Spustit analýzu" press (no
 * MediaProjection involved yet) crash with a SecurityException.
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
    @Volatile var mode: CaptureMode = CaptureMode.ANALYSIS
        private set

    private var config: AudioConfig? = null
    private var audioRecord: AudioRecord? = null
    private var analysisThread: Thread? = null

    private var rewindBuffer: PcmRingBuffer? = null
    private var bufferSeconds = REWIND_BUFFER_SECONDS
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
        val requested = intent?.getStringExtra(EXTRA_CAPTURE_MODE)
            ?.let { runCatching { CaptureMode.valueOf(it) }.getOrNull() }
            ?: CaptureMode.ANALYSIS
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!running) startCapture(requested)
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

    private fun startCapture(requestedMode: CaptureMode) {
        mode = requestedMode
        bufferSeconds = if (requestedMode == CaptureMode.LOOP) LOOP_BUFFER_SECONDS else REWIND_BUFFER_SECONDS
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

        // Native-memory ring (see PcmRingBuffer): a 5-minute 192 kHz window is ~110 MB, which
        // as a ShortArray would sit at well over half of a 4 GB device's ~192 MB heap cap.
        val ring = try {
            PcmRingBuffer(cfg.sampleRateHz * bufferSeconds)
        } catch (e: Throwable) {
            val mb = cfg.sampleRateHz.toLong() * bufferSeconds * 2 / (1024 * 1024)
            EventLog.log(LogLevel.ERROR, "Audio", "Nepodařilo se vyhradit buffer $mb MB", e)
            lastSaveStatusText = getString(R.string.buffer_alloc_failed, mb)
            try { record.release() } catch (_: Exception) {}
            audioRecord = null
            stopSelfCleanly()
            return
        }
        synchronized(rewindLock) { rewindBuffer = ring }

        val bufferMb = ring.capacityBytes / (1024 * 1024)
        EventLog.log(
            LogLevel.INFO, "Audio",
            "Režim ${if (mode == CaptureMode.LOOP) "smyčka" else "analýza"}: " +
                "buffer $bufferSeconds s = $bufferMb MB"
        )

        running = true
        record.startRecording()

        analysisThread = thread(name = "ulzvu-capture") {
            try {
                if (mode == CaptureMode.LOOP) loopOnlyCapture(record) else analysisLoop(record, cfg.sampleRateHz)
            } catch (e: Exception) {
                EventLog.log(LogLevel.ERROR, "Audio", "Záznamová smyčka spadla", e)
            }
        }

        startHeartRate()
    }

    /**
     * Loop mode: read and retain, nothing else. No FFT, no detection, no spectrum -- the point
     * is a long untouched window of the surroundings, and anything we'd filter or flag here
     * can be done afterwards on the saved WAV without destroying data.
     */
    private fun loopOnlyCapture(record: AudioRecord) {
        val buffer = ShortArray(LOOP_READ_SAMPLES)
        latestSpectrumDb = null
        peakText = ""
        alertActive = false
        alertText = getString(R.string.status_idle)

        while (running) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            synchronized(rewindLock) { rewindBuffer?.write(buffer, read) }
        }
    }

    fun stopCapture() {
        running = false
        // Generous join: a save in flight holds the ring lock for the length of one disk
        // write (a 5-minute loop is ~110 MB), and the capture thread parks on that lock.
        analysisThread?.join(2000)
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
        EventLog.log(LogLevel.INFO, "Audio", if (mode == CaptureMode.LOOP) "Smyčka zastavena" else "Analýza zastavena")

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
        synchronized(rewindLock) { rewindBuffer?.write(samples) }
    }

    /** One tap saves the last REWIND_BUFFER_SECONDS of mic audio, the same window of My Noise
     * playback if `playbackSamples` was supplied (extracted by the caller from
     * PlaybackCaptureService just before calling this), and the log entries from that window --
     * a matched set sharing one timestamp. Runs on its own thread so a slow save can't jank
     * the UI. */
    fun requestSaveRewindBundle(playbackSamples: ShortArray?) {
        thread(name = "ulzvu-save") { saveRewindBundle(playbackSamples) }
    }

    private fun saveRewindBundle(playbackSamples: ShortArray?) {
        val cfg = config
        if (cfg == null) {
            lastSaveStatusText = getString(R.string.rewind_failed)
            return
        }

        val ring = rewindBuffer
        if (ring == null || synchronized(rewindLock) { ring.filled } == 0) {
            lastSaveStatusText = getString(R.string.rewind_failed)
            EventLog.log(LogLevel.WARN, "Rewind", "Kruhový buffer je zatím prázdný")
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val audioName = saveRingToWav(ring, cfg.sampleRateHz, "zvuk_$timestamp.wav", "Rewind")

        val playbackName = playbackSamples?.let {
            saveWavToDownloads(it, PLAYBACK_SAMPLE_RATE_HZ, "mynoise_$timestamp.wav", "Playback")
        }

        val cutoffMs = System.currentTimeMillis() - bufferSeconds * 1000L
        val logLines = EventLog.snapshot().filter { it.timestampMs >= cutoffMs }.map { EventLog.format(it) }
        val logName = saveLogSliceToDownloads(logLines, timestamp)

        lastSaveStatusText = when {
            audioName == null || logName == null -> getString(R.string.rewind_failed)
            playbackName != null -> getString(R.string.saved_triple_format, audioName, playbackName, logName)
            else -> getString(R.string.saved_pair_format, audioName, logName)
        }
    }

    /**
     * Streams the ring buffer straight into the file in chunks.
     *
     * The 5-minute loop is ~110 MB; turning it into one ShortArray and then one ByteArray (as
     * the old snapshot-then-write path did) would ask the Java heap for 220 MB and die on the
     * per-app cap. The lock is held for the whole write so the file is one consistent window --
     * the capture thread stalls meanwhile and AudioRecord may drop a moment of live audio,
     * which is the right trade when the entire point of the button is to keep the PAST.
     */
    private fun saveRingToWav(ring: PcmRingBuffer, sampleRateHz: Int, displayName: String, logTag: String): String? {
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

            val chunk = ShortArray(SAVE_CHUNK_SAMPLES)
            val bytes = ByteArray(SAVE_CHUNK_SAMPLES * 2)
            stream.use { out ->
                synchronized(rewindLock) {
                    val total = ring.filled
                    out.write(WavHeader.build(sampleRateHz, total * 2))
                    var pos = 0
                    while (pos < total) {
                        val n = ring.readChronological(pos, chunk, minOf(SAVE_CHUNK_SAMPLES, total - pos))
                        if (n <= 0) break
                        var bi = 0
                        for (i in 0 until n) {
                            val v = chunk[i].toInt()
                            bytes[bi++] = (v and 0xff).toByte()
                            bytes[bi++] = (v shr 8 and 0xff).toByte()
                        }
                        out.write(bytes, 0, n * 2)
                        pos += n
                    }
                }
            }
            displayName
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, logTag, "Uložení WAV selhalo ($displayName)", e)
            null
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
