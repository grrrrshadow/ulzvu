package com.grrrrshadow.ulzvu

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
 * Thin UI shell: all capture (mic, spectrum, detection, heart rate, rewind buffer) lives in
 * UlzvuService so it keeps running when this Activity isn't visible. This just binds to the
 * service while on screen and polls its published state to refresh the views.
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: UlzvuService? = null
    private var bound = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            startService()
        } else {
            tvStatus.text = getString(R.string.permission_denied)
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

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshFromService()
            if (bound) mainHandler.postDelayed(this, UI_POLL_INTERVAL_MS)
        }
    }
    private val revertSaveButtonRunnable = Runnable { btnSaveRewind.text = getString(R.string.rewind_button) }

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

        tvDeviceInfo.text =
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        btnToggleAnalysis.setOnClickListener {
            if (service?.running == true) {
                service?.stopCapture()
            } else {
                requestPermissionsAndStart()
            }
        }
        btnSaveRewind.setOnClickListener {
            btnSaveRewind.text = getString(R.string.rewind_saving)
            mainHandler.removeCallbacks(revertSaveButtonRunnable)
            service?.requestSaveRewindBundle()
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
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(revertSaveButtonRunnable)
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            EventLog.log(LogLevel.ERROR, "Crash", "Neošetřená výjimka ve vlákně ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun requestPermissionsAndStart() {
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
        ContextCompat.startForegroundService(this, Intent(this, UlzvuService::class.java))
    }

    private fun updateRecentLogView() {
        val lines = EventLog.snapshot().takeLast(4).map { EventLog.format(it) }
        tvRecentLog.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
    }

    private fun refreshFromService() {
        val s = service ?: return

        btnToggleAnalysis.text = getString(if (s.running) R.string.stop_analysis else R.string.start_analysis)
        btnSaveRewind.isEnabled = s.running
        if (s.configText.isNotEmpty()) tvConfigInfo.text = s.configText

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

        updateRecentLogView()
    }
}
