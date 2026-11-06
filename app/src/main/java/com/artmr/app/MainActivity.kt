package com.artmr.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: StereoLayout
    private var stereo = true
    private var appList: List<Pair<String, String>>? = null

    private val camThread = HandlerThread("cam").also { it.start() }
    private val camH = Handler(camThread.looper)
    @Volatile private var camDev: CameraDevice? = null
    @Volatile private var sess: CameraCaptureSession? = null
    @Volatile private var opening = false

    private val dm get() = resources.displayMetrics
    private fun dp(v: Int) = (v * dm.density).toInt()

    private val stl = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { startCam() }
        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { stopCam(); return true }
        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = StereoLayout(this)
        root.cam1.surfaceTextureListener = stl
        root.cam2.surfaceTextureListener = stl
        setContentView(root)
        home()
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        startCam()
    }

    override fun onResume() { super.onResume(); startCam() }
    override fun onPause() { stopCam(); super.onPause() }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(f: Boolean) {
        super.onWindowFocusChanged(f)
        if (f) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP) { root.click(); return true }
        return super.onKeyDown(k, e)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { home() }

    // ---------- câmera (passthrough) ----------
    @SuppressLint("MissingPermission")
    private fun startCam() {
        try {
            if (!stereo || opening || camDev != null) return
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            ) return
            val t1 = root.cam1; val t2 = root.cam2
            if (!t1.isAvailable || !t2.isAvailable) return
            val m = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = m.cameraIdList.firstOrNull {
                m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: m.cameraIdList.firstOrNull() ?: return
            val map = m.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            val size = sizes.filter { it.width <= 1280 && it.height <= 720 }.maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height } ?: return
            root.camAspect = size.width.toFloat() / size.height
            root.requestLayout()
            t1.surfaceTexture!!.setDefaultBufferSize(size.width, size.height)
            t2.surfaceTexture!!.setDefaultBufferSize(size.width, size.height)
            opening = true
            m.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    camDev = d; opening = false
                    try {
                        if (!stereo) { d.close(); camDev = null; return }
                        val s1 = Surface(t1.surfaceTexture)
                        val s2 = Surface(t2.surfaceTexture)
                        @Suppress("DEPRECATION")
                        d.createCaptureSession(listOf(s1, s2), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cs: CameraCaptureSession) {
                                sess = cs
                                try {
                                    val r = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    r.addTarget(s1); r.addTarget(s2)
                                    cs.setRepeatingRequest(r.build(), null, camH)
                                } catch (e: Exception) {}
                            }
                            override fun onConfigureFailed(cs: CameraCaptureSession) {}
                        }, camH)
                    } catch (e: Exception) { d.close(); camDev = null }
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); camDev = null; opening = false }
                override fun onError(d: CameraDevice, err: Int) { d.close(); camDev = null; opening = false }
            }, camH)
        } catch (e: Exception) { opening = false }
    }

    private fun stopCam() {
        try { sess?.close() } catch (e: Exception) {}
        try { camDev?.close() } catch (e: Exception) {}
        sess = null; camDev = null; opening = false
    }

    // ---------- UI (glass) ----------
    private fun glass(r: Int, a: Int = 0x33): GradientDrawable {
        val g = GradientDrawable()
        g.setColor((a shl 24) or 0xFFFFFF)
        g.cornerRadius = dp(r).toFloat()
        g.setStroke(dp(1), 0x66FFFFFF)
        return g
    }

    private fun btn(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 14f
        t.gravity = Gravity.CENTER
        t.background = glass(14)
        t.setPadding(dp(6), dp(6), dp(6), dp(6))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun row(items: List<View>): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in items) {
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
            lp.setMargins(dp(4), dp(3), dp(4), dp(3))
            r.addView(v, lp)
        }
        return r
    }

    private fun panel(title: String, build: (LinearLayout) -> Unit) {
        val s = root.scene
        s.removeAllViews()
        val ew = if (stereo) dm.widthPixels / 2 else dm.widthPixels
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.background = glass(24, 0x22)
        p.setPadding(dp(12), dp(10), dp(12), dp(10))
        val t = TextView(this)
        t.text = title
        t.setTextColor(Color.WHITE)
        t.textSize = 16f
        t.gravity = Gravity.CENTER
        p.addView(t)
        build(p)
        s.addView(p, FrameLayout.LayoutParams((ew * 0.86f).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun home() {
        panel(if (stereo) "ART-MR · VR" else "ART-MR · DEX") { p ->
            p.addView(row(listOf(btn("📱 Apps") { apps(0) }, btn("🔍 Google") { web("https://www.google.com") })))
            p.addView(row(listOf(
                btn("▶ YouTube") { if (!launchPkg("com.google.android.youtube")) web("https://m.youtube.com") },
                btn("🗺 Mapas") { web("https://maps.google.com") })))
            p.addView(row(listOf(
                btn("🧊 Modelo 3D") { model(0) },
                btn(if (stereo) "🖥 Modo DEX" else "🥽 Modo VR") { toggle() })))
            p.addView(row(listOf(
                btn("⚙ Config") { go(Intent(Settings.ACTION_SETTINGS)) },
                btn("✖ Sair") { finish() })))
        }
    }

    private fun apps(page: Int) {
        if (appList == null) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            appList = packageManager.queryIntentActivities(i, 0)
                .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
                .filter { it.second != packageName }
                .sortedBy { it.first.lowercase() }
        }
        val l = appList!!
        val per = 8
        val pages = maxOf(1, (l.size + per - 1) / per)
        val pg = page.coerceIn(0, pages - 1)
        panel("Apps ${pg + 1}/$pages") { p ->
            l.drop(pg * per).take(per).chunked(2).forEach { ch ->
                p.addView(row(ch.map { a -> btn(a.first.take(14)) { launchPkg(a.second) } }))
            }
            p.addView(row(listOf(btn("◀") { apps(pg - 1) }, btn("🏠") { home() }, btn("▶") { apps(pg + 1) })))
        }
    }

    private fun model(i: Int) {
        panel("Modelo 3D") { p ->
            val v = Model3D(this)
            v.shape = i % 3
            p.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170)))
            p.addView(row(listOf(btn("🔄 Trocar") { model(i + 1) }, btn("🏠 Voltar") { home() })))
        }
    }

    private fun toggle() {
        if (stereo) { stopCam(); stereo = false; root.stereo = false }
        else { stereo = true; root.stereo = true }
        home()
    }

    // ---------- abrir apps ----------
    private fun go(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (!stereo && Build.VERSION.SDK_INT >= 24) {
                val o = ActivityOptions.makeBasic()
                o.setLaunchBounds(Rect(0, 0, dm.widthPixels * 6 / 10, dm.heightPixels))
                startActivity(i, o.toBundle())
            } else startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun web(url: String) = go(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun launchPkg(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        go(i)
        return true
    }
}
