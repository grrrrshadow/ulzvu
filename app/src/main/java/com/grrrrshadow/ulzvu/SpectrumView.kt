package com.grrrrshadow.ulzvu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var db: DoubleArray = DoubleArray(0)
    private var binWidthHz: Double = 0.0
    private val ultrasoundStartHz: Double = 17000.0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22D3EE")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val ultrasoundZonePaint = Paint().apply {
        color = Color.parseColor("#33F87171")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 1f
    }
    private val path = Path()

    private val minDb = -90.0
    private val maxDb = 0.0

    fun update(spectrumDb: DoubleArray, binWidthHz: Double) {
        this.db = spectrumDb
        this.binWidthHz = binWidthHz
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (db.isEmpty() || binWidthHz <= 0.0 || w <= 0f || h <= 0f) return

        val nyquist = db.size * binWidthHz

        if (ultrasoundStartHz < nyquist) {
            val xStart = (ultrasoundStartHz / nyquist * w).toFloat()
            canvas.drawRect(xStart, 0f, w, h, ultrasoundZonePaint)
        }

        var gDb = 0.0
        while (gDb >= minDb) {
            val y = ((maxDb - gDb) / (maxDb - minDb) * h).toFloat()
            canvas.drawLine(0f, y, w, y, gridPaint)
            gDb -= 20.0
        }

        path.reset()
        for (bin in db.indices) {
            val x = (bin.toFloat() / db.size) * w
            val clamped = db[bin].coerceIn(minDb, maxDb)
            val y = ((maxDb - clamped) / (maxDb - minDb) * h).toFloat()
            if (bin == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
