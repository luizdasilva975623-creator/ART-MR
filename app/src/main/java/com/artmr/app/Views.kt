package com.artmr.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class Scene(c: Context) : FrameLayout(c) {
    var curX = 300f
    var curY = 300f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val d = resources.displayMetrics.density
        p.style = Paint.Style.FILL
        p.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(curX, curY, 5f * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.color = 0xFF8A5CFF.toInt()
        canvas.drawCircle(curX, curY, 8f * d, p)
    }
}

class StereoLayout(c: Context) : ViewGroup(c) {
    val cam1 = TextureView(c)
    val cam2 = TextureView(c)
    val scene = Scene(c)
    var camAspect = 16f / 9f
    var stereo = true
        set(v) {
            field = v
            val vis = if (v) View.VISIBLE else View.GONE
            cam1.visibility = vis
            cam2.visibility = vis
            requestLayout()
        }

    init {
        addView(cam1); addView(cam2); addView(scene)
        setBackgroundColor(0xFF05060A.toInt())
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val ew = if (stereo) w / 2 else w
        scene.measure(MeasureSpec.makeMeasureSpec(ew, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        scene.layout(0, 0, ew, h)
        if (stereo) {
            cam1.layout(0, 0, ew, h)
            cam2.layout(ew, 0, w, h)
            crop(cam1, ew, h)
            crop(cam2, ew, h)
        }
    }

    private fun crop(v: TextureView, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val va = w.toFloat() / h
        val m = Matrix()
        if (camAspect > va) m.setScale(camAspect / va, 1f, w / 2f, h / 2f)
        else m.setScale(1f, va / camAspect, w / 2f, h / 2f)
        v.setTransform(m)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (stereo) {
            canvas.save()
            canvas.translate(width / 2f, 0f)
            drawChild(canvas, scene, drawingTime)
            canvas.restore()
        }
    }

    private var lx = 0f
    private var ly = 0f
    private var sx = 0f
    private var sy = 0f
    private var t0 = 0L
    private var moved = false

    override fun onInterceptTouchEvent(e: MotionEvent) = true

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y; sx = e.x; sy = e.y; t0 = e.eventTime; moved = false
                if (!stereo) { scene.curX = e.x; scene.curY = e.y; scene.invalidate() }
            }
            MotionEvent.ACTION_MOVE -> {
                if (stereo) {
                    scene.curX = (scene.curX + (e.x - lx) * 2f).coerceIn(0f, scene.width.toFloat())
                    scene.curY = (scene.curY + (e.y - ly) * 2f).coerceIn(0f, scene.height.toFloat())
                    scene.invalidate()
                }
                lx = e.x; ly = e.y
                if (hypot(e.x - sx, e.y - sy) > 24f) moved = true
            }
            MotionEvent.ACTION_UP -> if (!moved && e.eventTime - t0 < 400) click()
        }
        return true
    }

    fun click() {
        hit(scene, scene.curX, scene.curY)?.performClick()
    }

    private fun hit(v: View, x: Float, y: Float): View? {
        if (v.visibility != View.VISIBLE) return null
        if (x < v.left || x > v.right || y < v.top || y > v.bottom) return null
        val lx = x - v.left
        val ly = y - v.top
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                hit(v.getChildAt(i), lx, ly)?.let { return it }
            }
        }
        return if (v.isClickable) v else null
    }
}

class Model3D(c: Context) : View(c) {
    class Shape(val v: Array<FloatArray>, val e: List<IntArray>)

    var shape = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFB388FF.toInt()
        strokeWidth = 3f * c.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val cube: Shape = run {
        val v = Array(8) { i ->
            floatArrayOf(
                if ((i and 1) == 0) -1f else 1f,
                if ((i and 2) == 0) -1f else 1f,
                if ((i and 4) == 0) -1f else 1f
            )
        }
        val e = ArrayList<IntArray>()
        for (i in 0 until 8) for (j in i + 1 until 8) {
            val d = i xor j
            if (d == 1 || d == 2 || d == 4) e.add(intArrayOf(i, j))
        }
        Shape(v, e)
    }
    private val pyramid = Shape(
        arrayOf(
            floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f),
            floatArrayOf(1f, -1f, 1f), floatArrayOf(-1f, -1f, 1f),
            floatArrayOf(0f, 1.3f, 0f)
        ),
        listOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0),
            intArrayOf(0, 4), intArrayOf(1, 4), intArrayOf(2, 4), intArrayOf(3, 4)
        )
    )
    private val octa: Shape = run {
        val v = arrayOf(
            floatArrayOf(1.2f, 0f, 0f), floatArrayOf(-1.2f, 0f, 0f),
            floatArrayOf(0f, 1.2f, 0f), floatArrayOf(0f, -1.2f, 0f),
            floatArrayOf(0f, 0f, 1.2f), floatArrayOf(0f, 0f, -1.2f)
        )
        val e = ArrayList<IntArray>()
        for (i in 0 until 6) for (j in i + 1 until 6) if (i / 2 != j / 2) e.add(intArrayOf(i, j))
        Shape(v, e)
    }

    override fun onDraw(canvas: Canvas) {
        val s = when (shape) { 0 -> cube; 1 -> pyramid; else -> octa }
        val a = (SystemClock.uptimeMillis() % 6000) / 6000f * 6.2831855f
        val ca = cos(a); val sa = sin(a)
        val ct = cos(0.5f); val st = sin(0.5f)
        val cx = width / 2f
        val cy = height / 2f
        val sc = min(width, height) * 0.2f
        val pts = s.v.map {
            val x1 = it[0] * ca + it[2] * sa
            val z1 = -it[0] * sa + it[2] * ca
            val y2 = it[1] * ct - z1 * st
            val z2 = it[1] * st + z1 * ct
            val f = 4f / (z2 + 4f)
            floatArrayOf(cx + x1 * sc * f, cy - y2 * sc * f)
        }
        for (ed in s.e) {
            val p = pts[ed[0]]; val q = pts[ed[1]]
            canvas.drawLine(p[0], p[1], q[0], q[1], paint)
        }
        postInvalidateOnAnimation()
    }
}
