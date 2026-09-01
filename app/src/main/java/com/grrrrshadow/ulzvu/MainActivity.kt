package com.grrrrshadow.ulzvu

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.grrrrshadow.ulzvu.core.SpectrumAnalyzer
import com.grrrrshadow.ulzvu.core.WavHeader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private const val FFT_SIZE = 2048
private const val ULTRASOUND_BAND_START_HZ = 17000.0
private const val DETECTION_MARGIN_DB = 12.0
private const val NOISE_FLOOR_ALPHA = 0.01
private const val DETECTION_HOLD_FRAMES = 5 // consecutive frames required before flipping the alert state

class MainActivity : AppCompatActivity() {

    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvConfigInfo: TextView
    private lateinit var tvPeakInfo: TextView
    private lateinit var tvUltrasoundAlert: TextView
    private lateinit var tvStatus: TextView
    private lateinit var spectrumView: SpectrumView
    private lateinit var btnToggleAnalysis: Button
    private lateinit var btnToggleRecording: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAnalysis() else tvStatus.text = getString(R.string.permission_denied)
    }

    private var config: AudioConfig? = null
    private var audioRecord: AudioRecord? = null
    private var analysisThread: Thread? = null
    @Volatile private var running = false

    @Volatile private var recording = false
    private val recordingLock = Any()
    private var pcmCacheFile: File? = null
    private var pcmOut: FileOutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        EventLog.init(applicationContext)
        installCrashLogger()

        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvConfigInfo = findViewById(R.id.tvConfigInfo)
        tvPeakInfo = findViewById(R.id.tvPeakInfo)
        tvUltrasoundAlert = findViewById(R.id.tvUltrasoundAlert)
        tvStatus = findViewById(R.id.tvStatus)
        spectrumView = findViewById(R.id.spectrumView)
        btnToggleAnalysis = findViewById(R.id.btnToggleAnalysis)
        btnToggleRecording = findViewById(R.id.btnToggleRecording)

        tvDeviceInfo.text =
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        btnToggleAnalysis.setOnClickListener {
            if (running) stopAnalysis() else requestPermissionAndStart()
        }
        btnToggleRecording.setOnClickListener {
            if (recording) stopRecording() else startRecording()
        }
        findViewById<Button>(R.id.btnOpenLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenHeartRate).setOnClickListener {
            startActivity(Intent(this, HeartRateActivity::class.java))
        }

        EventLog.log(LogLevel.INFO, "App", "Spuštěno na ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            EventLog.log(LogLevel.ERROR, "Crash", "Neošetřená výjimka ve vlákně ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun requestPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startAnalysis()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAnalysis() {
        val cfg = try {
            AudioProber.probe(this)
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Audio", "AudioProber.probe selhal", e)
            null
        }
        if (cfg == null) {
            tvStatus.text = getString(R.string.probe_failed)
            EventLog.log(LogLevel.WARN, "Audio", "Žádná funkční kombinace vzorkování/zdroje nenalezena")
            return
        }
        config = cfg

        val nyquist = cfg.sampleRateHz / 2
        tvConfigInfo.text = getString(R.string.config_format, cfg.sampleRateHz, cfg.sourceName, nyquist)
        EventLog.log(LogLevel.INFO, "Audio", "Konfigurace: ${cfg.sampleRateHz} Hz, zdroj ${cfg.sourceName}, Nyquist $nyquist Hz")

        val record = try {
            AudioRecord(
                cfg.source, cfg.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                cfg.minBufferBytes
            )
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Audio", "AudioRecord() selhal", e)
            tvStatus.text = getString(R.string.probe_failed)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            tvStatus.text = getString(R.string.probe_failed)
            EventLog.log(LogLevel.ERROR, "Audio", "AudioRecord se neinicializoval (state=${record.state})")
            return
        }
        audioRecord = record

        running = true
        record.startRecording()
        btnToggleAnalysis.text = getString(R.string.stop_analysis)
        btnToggleRecording.isEnabled = true
        tvStatus.text = ""

        analysisThread = thread(name = "ulzvu-analysis") {
            try {
                analysisLoop(record, cfg.sampleRateHz)
            } catch (e: Exception) {
                EventLog.log(LogLevel.ERROR, "Audio", "Analyzační smyčka spadla", e)
            }
        }
    }

    private fun analysisLoop(record: AudioRecord, sampleRateHz: Int) {
        val analyzer = SpectrumAnalyzer(FFT_SIZE, sampleRateHz)
        val noiseFloor = DoubleArray(FFT_SIZE / 2) { -90.0 }
        val buffer = ShortArray(FFT_SIZE)
        val db = DoubleArray(FFT_SIZE / 2)

        var alertActive = false
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

            if (recording) writePcmChunk(buffer)

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

            if (!alertActive && consecutiveAbove >= DETECTION_HOLD_FRAMES) {
                alertActive = true
                incidentStartMs = System.currentTimeMillis()
                EventLog.log(
                    LogLevel.WARN, "Detekce",
                    "ULTRAZVUK začal: ${"%.0f".format(frameFreq)} Hz @ ${"%.1f".format(frameDb)} dB"
                )
            } else if (alertActive && consecutiveBelow >= DETECTION_HOLD_FRAMES) {
                alertActive = false
                val durationMs = System.currentTimeMillis() - incidentStartMs
                EventLog.log(LogLevel.WARN, "Detekce", "ULTRAZVUK skončil: trval ${durationMs} ms")
            }

            // ultrasound-band peak specifically -- a full-spectrum peak is dominated by
            // ordinary handling/voice noise and makes the readout jump around uselessly
            val (peakFreq, peakDb) = analyzer.peakInRange(db, ULTRASOUND_BAND_START_HZ, sampleRateHz / 2.0)
            val dbCopy = db.copyOf()
            val isAlertActive = alertActive
            val ff = frameFreq
            val fd = frameDb

            mainHandler.post {
                spectrumView.update(dbCopy, analyzer.binWidthHz)
                tvPeakInfo.text = getString(R.string.peak_format, peakFreq, peakDb)
                if (isAlertActive) {
                    tvUltrasoundAlert.text = getString(R.string.ultrasound_detected_format, ff, fd)
                    tvUltrasoundAlert.setBackgroundColor(ContextCompat.getColor(this, R.color.ultrasound_alert))
                } else {
                    tvUltrasoundAlert.text = getString(R.string.status_idle)
                    tvUltrasoundAlert.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
                }
            }
        }
    }

    private fun stopAnalysis() {
        running = false
        if (recording) stopRecording()
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
        btnToggleAnalysis.text = getString(R.string.start_analysis)
        btnToggleRecording.isEnabled = false
        EventLog.log(LogLevel.INFO, "Audio", "Analýza zastavena")
    }

    private fun startRecording() {
        synchronized(recordingLock) {
            try {
                pcmCacheFile = File(cacheDir, "ulzvu_tmp.pcm")
                pcmOut = FileOutputStream(pcmCacheFile)
            } catch (e: IOException) {
                EventLog.log(LogLevel.ERROR, "Recording", "Nelze otevřít dočasný PCM soubor", e)
                tvStatus.text = getString(R.string.recording_failed)
                return
            }
        }
        recording = true
        btnToggleRecording.text = getString(R.string.stop_recording)
        tvStatus.text = getString(R.string.recording_in_progress)
        EventLog.log(LogLevel.INFO, "Recording", "Nahrávání spuštěno")
    }

    private fun writePcmChunk(samples: ShortArray) {
        synchronized(recordingLock) {
            val out = pcmOut ?: return
            try {
                val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                samples.forEach { bytes.putShort(it) }
                out.write(bytes.array())
            } catch (e: IOException) {
                EventLog.log(LogLevel.ERROR, "Recording", "Zápis PCM chunku selhal", e)
            }
        }
    }

    private fun stopRecording() {
        recording = false
        btnToggleRecording.text = getString(R.string.start_recording)

        synchronized(recordingLock) {
            try {
                pcmOut?.flush()
                pcmOut?.close()
            } catch (e: IOException) {
                EventLog.log(LogLevel.ERROR, "Recording", "Chyba při zavírání PCM souboru", e)
            }
            pcmOut = null
        }

        val cfg = config
        val cacheFile = pcmCacheFile
        if (cfg == null || cacheFile == null || !cacheFile.exists() || cacheFile.length() == 0L) {
            tvStatus.text = getString(R.string.recording_failed)
            EventLog.log(LogLevel.ERROR, "Recording", "Dočasný PCM soubor chybí nebo je prázdný")
            return
        }

        val savedName = saveWavToDownloads(cacheFile, cfg.sampleRateHz)
        cacheFile.delete()
        pcmCacheFile = null

        tvStatus.text = if (savedName != null) {
            getString(R.string.saved_format, savedName)
        } else {
            getString(R.string.recording_failed)
        }
    }

    private fun saveWavToDownloads(pcmFile: File, sampleRateHz: Int): String? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "ulzvu_$timestamp.wav"

        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ulzvu")
            }

            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                EventLog.log(LogLevel.ERROR, "Recording", "MediaStore.insert vrátil null pro $displayName")
                return null
            }

            val stream = contentResolver.openOutputStream(uri)
            if (stream == null) {
                EventLog.log(LogLevel.ERROR, "Recording", "openOutputStream vrátil null pro $uri")
                return null
            }
            stream.use { out ->
                val header = WavHeader.build(sampleRateHz, pcmFile.length().toInt())
                out.write(header)
                pcmFile.inputStream().use { it.copyTo(out) }
            }
            EventLog.log(LogLevel.INFO, "Recording", "Uloženo $displayName (${pcmFile.length()} B PCM)")
            displayName
        } catch (e: Exception) {
            EventLog.log(LogLevel.ERROR, "Recording", "Uložení WAV selhalo", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) stopAnalysis()
    }
}
