package com.tvceviri.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CaptureService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var translator: Translator? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var wm: WindowManager? = null
    private var overlay: TextView? = null

    @Volatile private var running = false
    private var lastText = ""
    private var emptyCount = 0
    private var cw = 0
    private var ch = 0
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY

        startFg()

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bgThread = HandlerThread("tvceviri-cap").also { it.start() }
        bg = Handler(bgThread!!.looper)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        // Android 14+: callback, createVirtualDisplay'den ÖNCE kayıtlı olmalı.
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        setupTranslator()
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("cap", "TV Çeviri", NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, "cap")
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val n = builder
            .setContentTitle("TV Çeviri çalışıyor")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun setupTranslator() {
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        val t = Translation.getClient(opts)
        translator = t
        toast("Çeviri modeli hazırlanıyor...")
        t.downloadModelIfNeeded()
            .addOnSuccessListener { startCapture() }
            .addOnFailureListener {
                if (Prefs.online(this)) {
                    startCapture() // Offline model yok ama online çeviri çalışabilir
                } else {
                    toast("Model indirilemedi, internet bağlantısını kontrol et")
                    stopSelf()
                }
            }
    }

    private fun startCapture() {
        if (!running) return
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm!!.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        // OCR için yeterli: genişliği en fazla 1280 pikselle sınırla.
        val scale = minOf(1f, 1280f / screenW)
        cw = (screenW * scale).toInt()
        ch = (screenH * scale).toInt()

        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        vd = projection?.createVirtualDisplay(
            "tvceviri", cw, ch, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, bg
        )
        toast("Çeviri aktif")
        bg?.postDelayed({ cycle() }, 500)
    }

    // ---- Döngü (arka plan thread'i) ----

    private fun cycle() {
        if (!running) return
        if (Prefs.cover(this) && overlay?.visibility == View.VISIBLE) {
            // Örtü modunda kendi kutumuzu yakalamamak için kısa süre gizle.
            main.post { overlay?.alpha = 0f }
            bg?.postDelayed({ grab() }, 150)
        } else {
            grab()
        }
    }

    private fun next() { if (running) bg?.postDelayed({ cycle() }, 500) }

    private fun restore() { main.post { overlay?.alpha = 1f } }

    private fun grab() {
        if (!running) return
        val img = try { reader?.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) { restore(); next(); return }

        val crop: Bitmap = try {
            val p = img.planes[0]
            val px = p.pixelStride
            val full = Bitmap.createBitmap(p.rowStride / px, ch, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            val top = (ch * (1f - Prefs.region(this) / 100f)).toInt().coerceIn(0, ch - 20)
            val c = Bitmap.createBitmap(full, 0, top, cw, ch - top)
            full.recycle()
            c
        } catch (e: Exception) {
            img.close(); restore(); next(); return
        }
        img.close()

        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { r -> crop.recycle(); handleText(r.text) }
            .addOnFailureListener { crop.recycle(); restore(); next() }
    }

    // ---- Ana thread (ML Kit callback'leri) ----

    private fun handleText(raw: String) {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.length < 4 || !text.any { it.isLetter() }) {
            if (++emptyCount >= 2) {
                lastText = ""
                overlay?.visibility = View.GONE
            } else restore()
            next(); return
        }
        emptyCount = 0
        if (text == lastText) { restore(); next(); return }
        lastText = text

        val cleaned = clean(text)
        if (Prefs.online(this)) {
            bg?.post {
                val r = translateOnline(cleaned)
                main.post { if (r != null) { show(r); next() } else translateOffline(cleaned) }
            }
        } else {
            translateOffline(cleaned)
        }
    }

    private fun translateOffline(text: String) {
        translator?.translate(text)
            ?.addOnSuccessListener { show(it); next() }
            ?.addOnFailureListener { restore(); next() }
            ?: run { restore(); next() }
    }

    /** Google Translate (anahtarsız uç nokta). Hata olursa null döner. */
    private fun translateOnline(text: String): String? {
        return try {
            val url = URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=tr&dt=t&q=" +
                    URLEncoder.encode(text, "UTF-8")
            )
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) sb.append(arr.getJSONArray(i).optString(0, ""))
            sb.toString().trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** OCR'ın sık yaptığı hataları temizle; çeviriye temiz metin gitsin. */
    private fun clean(s: String): String = s
        .replace(Regex("[♪♫♬#*_]+"), " ")
        .replace("|", "I")
        .replace(Regex("\\bl(?=['’]\\w)"), "I")   // l'm -> I'm
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun show(t: String) {
        val w = wm ?: return
        val v = overlay ?: createOverlay().also { overlay = it }
        v.text = t
        v.textSize = Prefs.textSp(this).toFloat()
        v.alpha = 1f
        v.visibility = View.VISIBLE
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.y = if (Prefs.cover(this)) (screenH * 0.04f).toInt()
        else (screenH * Prefs.region(this) / 100f).toInt()
        w.updateViewLayout(v, lp)
    }

    private fun createOverlay(): TextView {
        val v = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D9000000"))
            gravity = Gravity.CENTER
            setPadding(32, 12, 32, 12)
            maxWidth = (screenW * 0.85f).toInt()
        }
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        wm!!.addView(v, lp)
        return v
    }

    override fun onDestroy() {
        running = false
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        overlay = null
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { translator?.close() } catch (_: Exception) {}
        try { recognizer.close() } catch (_: Exception) {}
        bgThread?.quitSafely()
        super.onDestroy()
    }
}
