package com.grrrrshadow.ulzvu

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

private const val UI_POLL_INTERVAL_MS = 200L

/**
 * Thin UI shell: mic/spectrum/detection/heart-rate/rewind-buffer capture lives in
 * UlzvuService, and My Noise playback capture lives in the separate PlaybackCaptureService
 * (kept apart because Android requires MediaProjection consent to exist before a
 * "mediaProjection"-typed service can even start -- see PlaybackCaptureService's doc comment).
 * This Activity binds to both while visible and polls their published state to refresh the UI.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvConfigInfo: TextView
    private lateinit var tvPeakInfo: TextView
    private lateinit var tvUltrasoundAlert: TextView
    private lateinit var tvBpm: TextView
    private lateinit var tvMotionWarning: TextView
    private lateinit var tvLastIncident: TextView
    private lateinit var tvRecentLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var spectrumView: SpectrumView
    private lateinit var btnToggleAnalysis: Button
    private lateinit var btnSaveRewind: Button
    private lateinit var btnToggleLoop: Button
    private lateinit var btnToggleMynoise: Button

    /** Which mode the pending permission request is for. */
    private var pendingMode = CaptureMode.ANALYSIS

    /** True while the save button shows "Ukládám…"/"Uloženo ✓" instead of its normal label. */
    private var saveLabelTransient = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: UlzvuService? = null
    private var bound = false
    private var playbackService: PlaybackCaptureService? = null
    private var playbackBound = false
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            startService()
        } else {
            tvStatus.text = getString(R.string.permission_denied)
        }
    }

    // My Noise capture rides on the screen-capture consent flow -- Android has no
    // audio-only variant of this permission. A fresh grant is needed each time it's enabled.
    // The (resultCode, data) pair travels to PlaybackCaptureService via Intent extras, since
    // that service must call getMediaProjection() itself, after its own startForeground().
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, PlaybackCaptureService::class.java).apply {
                putExtra(EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
                putExtra(EXTRA_PROJECTION_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as UlzvuService.LocalBinder).getService()
            bound = true
            mainHandler.post(pollRunnable)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playbackService = (binder as PlaybackCaptureService.LocalBinder).getService()
            playbackBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            playbackBound = false
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshFromService()
            if (bound) mainHandler.postDelayed(this, UI_POLL_INTERVAL_MS)
        }
    }
    private val revertSaveButtonRunnable = Runnable {
        saveLabelTransient = false
        btnSaveRewind.text = defaultSaveLabel()
    }

    /** The save button spells out what it will actually write, which differs per mode. */
    private fun defaultSaveLabel(): String = getString(
        if (service?.mode == CaptureMode.LOOP) R.string.loop_save_button else R.string.rewind_button
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        EventLog.init(applicationContext)
        installCrashLogger()

        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvConfigInfo = findViewById(R.id.tvConfigInfo)
        tvPeakInfo = findViewById(R.id.tvPeakInfo)
        tvUltrasoundAlert = findViewById(R.id.tvUltrasoundAlert)
        tvBpm = findViewById(R.id.tvBpm)
        tvMotionWarning = findViewById(R.id.tvMotionWarning)
        tvLastIncident = findViewById(R.id.tvLastIncident)
        tvRecentLog = findViewById(R.id.tvRecentLog)
        tvStatus = findViewById(R.id.tvStatus)
        spectrumView = findViewById(R.id.spectrumView)
        btnToggleAnalysis = findViewById(R.id.btnToggleAnalysis)
        btnSaveRewind = findViewById(R.id.btnSaveRewind)
        btnToggleLoop = findViewById(R.id.btnToggleLoop)
        btnToggleMynoise = findViewById(R.id.btnToggleMynoise)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        tvDeviceInfo.text =
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        btnToggleAnalysis.setOnClickListener {
            if (service?.running == true) {
                service?.stopCapture()
            } else {
                requestPermissionsAndStart(CaptureMode.ANALYSIS)
            }
        }
        btnToggleLoop.setOnClickListener {
            if (service?.running == true) {
                service?.stopCapture()
            } else {
                requestPermissionsAndStart(CaptureMode.LOOP)
            }
        }
        btnSaveRewind.setOnClickListener {
            saveLabelTransient = true
            btnSaveRewind.text = getString(R.string.rewind_saving)
            mainHandler.removeCallbacks(revertSaveButtonRunnable)
            val playbackSamples = playbackService?.takeIf { it.playbackCaptureActive }?.extractCurrentBuffer()
            service?.requestSaveRewindBundle(playbackSamples)
        }
        btnToggleMynoise.setOnClickListener {
            if (playbackService?.playbackCaptureActive == true) {
                playbackService?.disableCapture()
            } else {
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }
        findViewById<Button>(R.id.btnOpenLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        EventLog.log(LogLevel.INFO, "App", "Spuštěno na ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        updateRecentLogView()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, UlzvuService::class.java), connection, Context.BIND_AUTO_CREATE)
        bindService(Intent(this, PlaybackCaptureService::class.java), playbackConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(revertSaveButtonRunnable)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        if (playbackBound) {
            unbindService(playbackConnection)
            playbackBound = false
        }
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            EventLog.log(LogLevel.ERROR, "Crash", "Neošetřená výjimka ve vlákně ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun requestPermissionsAndStart(requestedMode: CaptureMode) {
        pendingMode = requestedMode
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startService() {
        val intent = Intent(this, UlzvuService::class.java)
            .putExtra(EXTRA_CAPTURE_MODE, pendingMode.name)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun updateRecentLogView() {
        val lines = EventLog.snapshot().takeLast(4).map { EventLog.format(it) }
        tvRecentLog.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
    }

    private fun refreshFromService() {
        val s = service ?: return

        // Analysis and the 5-minute loop are mutually exclusive: whichever is running owns the
        // microphone, so the other button greys out until it is stopped.
        val loopMode = s.mode == CaptureMode.LOOP
        btnToggleAnalysis.isEnabled = !s.running || !loopMode
        btnToggleLoop.isEnabled = !s.running || loopMode
        btnToggleAnalysis.text = getString(
            if (s.running && !loopMode) R.string.stop_analysis else R.string.start_analysis
        )
        btnToggleLoop.text = getString(
            if (s.running && loopMode) R.string.loop_button_stop else R.string.loop_button_start
        )
        btnSaveRewind.isEnabled = s.running
        btnToggleMynoise.isEnabled = s.running
        val playbackActive = playbackService?.playbackCaptureActive == true
        btnToggleMynoise.text = getString(
            if (playbackActive) R.string.mynoise_button_stop else R.string.mynoise_button_start
        )
        if (s.configText.isNotEmpty()) tvConfigInfo.text = s.configText

        if (!saveLabelTransient) btnSaveRewind.text = defaultSaveLabel()

        // Loop mode runs no FFT, so there is no spectrum or peak to show -- leaving the last
        // analysis frame frozen on screen would look like live data that isn't being measured.
        val spectrumVisible = !(s.running && loopMode)
        spectrumView.visibility = if (spectrumVisible) View.VISIBLE else View.INVISIBLE
        tvPeakInfo.visibility = if (spectrumVisible) View.VISIBLE else View.INVISIBLE

        val db = s.latestSpectrumDb
        if (db != null) spectrumView.update(db, s.latestBinWidthHz)
        if (s.peakText.isNotEmpty()) tvPeakInfo.text = s.peakText

        if (s.alertActive) {
            tvUltrasoundAlert.text = s.alertText
            tvUltrasoundAlert.setBackgroundColor(ContextCompat.getColor(this, R.color.ultrasound_alert))
        } else {
            tvUltrasoundAlert.text = getString(R.string.status_idle)
            tvUltrasoundAlert.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
        }

        val incident = s.lastIncidentText
        if (incident != null) tvLastIncident.text = incident

        tvBpm.text = s.bpmText.ifEmpty { getString(R.string.hr_unreliable) }
        tvMotionWarning.visibility = if (s.motionWarningActive) View.VISIBLE else View.INVISIBLE

        val saveStatus = s.consumeSaveStatus()
        if (saveStatus != null) {
            tvStatus.text = saveStatus
            btnSaveRewind.text = getString(R.string.rewind_saved)
            mainHandler.removeCallbacks(revertSaveButtonRunnable)
            mainHandler.postDelayed(revertSaveButtonRunnable, 5000L)
        }

        val playbackError = playbackService?.consumePlaybackError()
        if (playbackError != null) tvStatus.text = playbackError

        updateRecentLogView()
    }
}
