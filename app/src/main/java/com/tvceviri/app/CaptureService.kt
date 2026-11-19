package com.tvceviri.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class CaptureService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var wm: WindowManager? = null
    private var overlay: SubtitleView? = null
    private var shown = false

    @Volatile private var running = false
    private var lastText = ""
    private var seq = 0
    private val net = Executors.newFixedThreadPool(2)
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 200
    }
    private var emptyCount = 0
    private var cw = 0
    private var ch = 0
    private var screenW = 0
    private var screenH = 0

    private var msToken: String? = null
    private var msTokenTime = 0L

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

        startCapture()
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
        if (Prefs.cover(this) && shown) {
            // Örtü modunda kendi kutumuzu yakalamamak için kısa süre gizle.
            main.post { overlay?.visibility = View.INVISIBLE }
            bg?.postDelayed({ grab() }, 150)
        } else {
            grab()
        }
    }

    private fun next() { if (running) bg?.postDelayed({ cycle() }, Prefs.delay(this).toLong()) }

    private fun restore() { main.post { if (shown) overlay?.visibility = View.VISIBLE } }

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
        if (!running) return
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.length < 4 || !text.any { it.isLetter() }) {
            if (++emptyCount >= 2) {
                lastText = ""
                seq++ // bekleyen eski çevirileri geçersiz kıl
                shown = false
                overlay?.visibility = View.GONE
            } else restore()
            next(); return
        }
        emptyCount = 0
        if (text == lastText) { restore(); next(); return }
        lastText = text

        val cleaned = clean(text)
        val mySeq = ++seq
        val hit = cache[cleaned]
        if (hit != null) { show(hit); next(); return }

        next() // çeviriyi bekleme, taramaya hemen devam et
        val engine = Prefs.engine(this)
        net.execute {
            // Seçilen motor başarısız olursa diğerine düş.
            val r = if (engine == "microsoft") tMicrosoft(cleaned) ?: tGoogle(cleaned)
            else tGoogle(cleaned) ?: tMicrosoft(cleaned)
            main.post {
                if (mySeq != seq || !running) return@post // araya yeni altyazı girdi
                if (r != null) { cache[cleaned] = r; show(r) } else restore()
            }
        }
    }

    private fun show(t: String) {
        val w = wm ?: return
        val v = overlay ?: createOverlay().also { overlay = it }
        v.maxW = (screenW * 0.9f).toInt()
        v.update()
        v.setSubtitle(t)
        shown = true
        v.visibility = View.VISIBLE
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.y = if (Prefs.cover(this)) (screenH * 0.04f).toInt()
        else (screenH * Prefs.region(this) / 100f).toInt()
        w.updateViewLayout(v, lp)
    }

    private fun createOverlay(): SubtitleView {
        val v = SubtitleView(this)
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

    // ---- Çeviri motorları (bg thread) ----

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2500
        c.readTimeout = 3500
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        if (c.responseCode !in 200..299) null
        else c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    private fun tGoogle(t: String): String? = try {
        val body = http(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=tr&dt=t&q=" + enc(t),
            mapOf("User-Agent" to "Mozilla/5.0")
        )
        if (body == null) null else {
            val arr = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) sb.append(arr.getJSONArray(i).optString(0, ""))
            sb.toString().trim().ifEmpty { null }
        }
    } catch (e: Exception) { null }

    /** Microsoft Edge'in anahtarsız çeviri uç noktası. */
    private fun tMicrosoft(t: String): String? = try {
        if (msToken == null || System.currentTimeMillis() - msTokenTime > 8 * 60_000L) {
            msToken = http("https://edge.microsoft.com/translate/auth", mapOf("User-Agent" to "Mozilla/5.0"))
            msTokenTime = System.currentTimeMillis()
        }
        val tok = msToken
        if (tok == null) null else {
            val reqBody = JSONArray().put(JSONObject().put("Text", t)).toString()
            val r = http(
                "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=en&to=tr",
                mapOf("Authorization" to "Bearer $tok", "Content-Type" to "application/json"),
                reqBody
            )
            if (r == null) { msToken = null; null }
            else JSONArray(r).getJSONObject(0).getJSONArray("translations")
                .getJSONObject(0).getString("text").trim().ifEmpty { null }
        }
    } catch (e: Exception) { null }

    /** OCR'ın sık yaptığı hataları temizle; çeviriye temiz metin gitsin. */
    private fun clean(s: String): String = s
        .replace(Regex("[♪♫♬#*_]+"), " ")
        .replace("|", "I")
        .replace(Regex("\\bl(?=['’]\\w)"), "I")   // l'm -> I'm
        .replace(Regex("\\s+"), " ")
        .trim()

    override fun onDestroy() {
        running = false
        net.shutdownNow()
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        overlay = null
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { recognizer.close() } catch (_: Exception) {}
        bgThread?.quitSafely()
        super.onDestroy()
    }
}
