package com.grrrrshadow.ulzvu

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        tvLog = findViewById(R.id.tvLog)
        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            EventLog.clear()
            refresh()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val text = EventLog.readAll()
        tvLog.text = text.ifBlank { getString(R.string.log_empty) }
    }
}
