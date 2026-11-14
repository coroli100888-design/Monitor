package ro.coroli.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class OverlayView(ctx: Context) : View(ctx) {
    var grid = true
    var center = true
    var frameRatio = 0f // 0 = off, e.g. 2.39f, 1.78f

    private val p = Paint().apply {
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (grid) {
            for (i in 1..2) {
                c.drawLine(w * i / 3, 0f, w * i / 3, h, p)
                c.drawLine(0f, h * i / 3, w, h * i / 3, p)
            }
        }
        if (center) {
            c.drawLine(w / 2 - 30, h / 2, w / 2 + 30, h / 2, p)
            c.drawLine(w / 2, h / 2 - 30, w / 2, h / 2 + 30, p)
        }
        if (frameRatio > 0f) {
            val fh = w / frameRatio
            if (fh < h) c.drawRect(0f, (h - fh) / 2, w, (h + fh) / 2, p)
        }
    }
}
