package com.grrrrshadow.ulzvu

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.grrrrshadow.ulzvu.core.SpectrumAnalyzer
import kotlin.math.abs
import kotlin.math.sqrt

private const val WINDOW_SIZE = 512 // ~10 s of samples at the ~50 Hz SENSOR_DELAY_GAME rate
private const val MIN_BPM_HZ = 0.7  // 42 BPM
private const val MAX_BPM_HZ = 3.5  // 210 BPM
private const val MOTION_STDDEV_THRESHOLD = 1.2 // m/s^2 -- hand shake vs. pulse-scale vibration
private const val UPDATE_INTERVAL_MS = 500L

/**
 * Ballistocardiography via the phone's own accelerometer: hold the phone firmly against
 * skin (wrist/chest) and it picks up the tiny periodic jolt of each heartbeat. This is a
 * rough heuristic, not a medical device -- large hand movement swamps the signal, which is
 * exactly why the motion check below refuses to report a number when it's too shaky to trust.
 */
class HeartRateActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var tvBpm: TextView
    private lateinit var tvMotionWarning: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button

    private lateinit var sensorManager: SensorManager
    private var sensor: Sensor? = null

    private val timestampsNs = ArrayDeque<Long>()
    private val magnitudes = ArrayDeque<Double>()
    private val bufferLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private val updateRunnable = object : Runnable {
        override fun run() {
            computeAndDisplay()
            if (running) mainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_heart_rate)

        tvBpm = findViewById(R.id.tvBpm)
        tvMotionWarning = findViewById(R.id.tvMotionWarning)
        tvStatus = findViewById(R.id.tvHrStatus)
        btnToggle = findViewById(R.id.btnToggleHr)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (sensor == null) {
            tvStatus.text = getString(R.string.no_accelerometer)
            btnToggle.isEnabled = false
            EventLog.log(LogLevel.ERROR, "HeartRate", "Zařízení nemá akcelerometr")
            return
        }

        btnToggle.setOnClickListener { if (running) stop() else start() }
    }

    private fun start() {
        val s = sensor ?: return
        synchronized(bufferLock) {
            timestampsNs.clear()
            magnitudes.clear()
        }
        val registered = sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        if (!registered) {
            tvStatus.text = getString(R.string.no_accelerometer)
            EventLog.log(LogLevel.ERROR, "HeartRate", "registerListener selhal pro ${s.name}")
            return
        }
        running = true
        btnToggle.text = getString(R.string.stop_hr)
        tvStatus.text = getString(R.string.hr_measuring)
        EventLog.log(LogLevel.INFO, "HeartRate", "Měření spuštěno (${s.name})")
        mainHandler.post(updateRunnable)
    }

    private fun stop() {
        running = false
        sensorManager.unregisterListener(this)
        mainHandler.removeCallbacks(updateRunnable)
        btnToggle.text = getString(R.string.start_hr)
        tvStatus.text = getString(R.string.status_idle)
        EventLog.log(LogLevel.INFO, "HeartRate", "Měření zastaveno")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val mag = sqrt(
            (event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]).toDouble()
        )
        synchronized(bufferLock) {
            timestampsNs.addLast(event.timestamp)
            magnitudes.addLast(mag)
            while (magnitudes.size > WINDOW_SIZE) {
                timestampsNs.removeFirst()
                magnitudes.removeFirst()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun computeAndDisplay() {
        val snapshot: Pair<DoubleArray, Double>? = synchronized(bufferLock) {
            if (magnitudes.size < WINDOW_SIZE) return@synchronized null
            val ts = timestampsNs.toLongArray()
            val avgDtNs = (ts.last() - ts.first()).toDouble() / (ts.size - 1)
            if (avgDtNs <= 0) return@synchronized null
            magnitudes.toDoubleArray() to (1_000_000_000.0 / avgDtNs)
        }
        val (samples, sampleRateHz) = snapshot ?: return

        val mean = samples.average()
        val stddev = sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)

        if (stddev > MOTION_STDDEV_THRESHOLD) {
            tvMotionWarning.visibility = View.VISIBLE
            tvBpm.text = getString(R.string.hr_unreliable)
            return
        }
        tvMotionWarning.visibility = View.INVISIBLE

        val centered = DoubleArray(samples.size) { samples[it] - mean }
        val maxAbs = centered.maxOf { abs(it) }.coerceAtLeast(1e-6)
        val normalized = DoubleArray(centered.size) { centered[it] / maxAbs }

        val analyzer = SpectrumAnalyzer(WINDOW_SIZE, sampleRateHz.toInt().coerceAtLeast(1))
        val db = DoubleArray(analyzer.binCount)
        analyzer.analyze(normalized, db)

        val (peakFreq, peakDb) = analyzer.peakInRange(db, MIN_BPM_HZ, MAX_BPM_HZ)
        val bpm = peakFreq * 60.0

        tvBpm.text = getString(R.string.bpm_format, bpm)
        EventLog.log(
            LogLevel.INFO, "HeartRate",
            "Odhad ${"%.0f".format(bpm)} BPM (${"%.1f".format(peakDb)} dB, vzorkování ${"%.0f".format(sampleRateHz)} Hz)"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) stop()
    }
}
