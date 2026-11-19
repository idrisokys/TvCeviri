package com.tvceviri.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val reqCapture = 42
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var root: LinearLayout
    private lateinit var preview: SubtitleView
    private lateinit var btnColor: Button
    private lateinit var btnMode: Button
    private lateinit var btnEngine: Button

    private val colors = listOf(
        "Sarı" to 0xFFFFEB00.toInt(),
        "Beyaz" to 0xFFFFFFFF.toInt(),
        "Açık yeşil" to 0xFF76FF03.toInt(),
        "Camgöbeği" to 0xFF00E5FF.toInt(),
        "Turuncu" to 0xFFFF9800.toInt(),
        "Pembe" to 0xFFFF80AB.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(120, 40, 120, 60)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            addView(root)
        }

        label("TV Çeviri (EN → TR)", 30f, Color.WHITE)

        // Önizleme
        val box = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#555555")) }
        preview = SubtitleView(this).apply {
            maxW = 1100
            setSubtitle("Örnek altyazı: Merhaba, nasılsın?")
            update()
        }
        box.addView(preview, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(box, LinearLayout.LayoutParams(MATCH, 260).apply { topMargin = 16 })

        btn("Başlat") { start() }.requestFocus()
        btn("Durdur") { stopService(Intent(this, CaptureService::class.java)) }

        seek("Altyazı bölgesi (ekranın alt %)", 1, 50, 1, { Prefs.region(this) }) { Prefs.setRegion(this, it) }
        seek("Yazı boyutu", 12, 80, 1, { Prefs.textSp(this) }) { Prefs.setTextSp(this, it) }
        seek("Kenarlık kalınlığı (%)", 0, 100, 5, { Prefs.outline(this) }) { Prefs.setOutline(this, it) }
        seek("Yazı saydamlığı (görünürlük %)", 10, 100, 5, { Prefs.opacity(this) }) { Prefs.setOpacity(this, it) }

        seek("Çeviri gecikmesi (ms)", 100, 3000, 100, { Prefs.delay(this) }) { Prefs.setDelay(this, it) }

        btnColor = btn("") {
            val cur = Prefs.color(this)
            val i = colors.indexOfFirst { it.second == cur }
            Prefs.setColor(this, colors[(i + 1) % colors.size].second)
            refresh()
        }
        btnMode = btn("") { Prefs.setCover(this, !Prefs.cover(this)); refresh() }
        btnEngine = btn("") {
            val ids = Prefs.ENGINES.map { it.first }
            val i = ids.indexOf(Prefs.engine(this))
            Prefs.setEngine(this, ids[(i + 1) % ids.size])
            refresh()
        }

        label(
            "Önce 'Başlat'a bas, izinleri ver, sonra Kodi/Nuvio'ya geç. " +
                "Ayarlar bir sonraki altyazıdan itibaren geçerli olur.",
            15f, Color.LTGRAY
        )

        setContentView(scroll)
        refresh()
    }

    private fun label(t: String, size: Float, color: Int) {
        root.addView(TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 20 })
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isFocusable = true
        b.setOnClickListener { onClick() }
        root.addView(b, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 16 })
        return b
    }

    private fun seek(
        title: String, min: Int, max: Int, step: Int,
        get: () -> Int, set: (Int) -> Unit
    ) {
        val tv = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        tv.text = "$title: ${get()}"
        root.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 24 })
        val sb = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (get() - min) / step
            keyProgressIncrement = 1
            isFocusable = true
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = min + p * step
                set(v)
                tv.text = "$title: $v"
                preview.update()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(sb, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun refresh() {
        val cur = Prefs.color(this)
        btnColor.text = "Yazı rengi: " + (colors.firstOrNull { it.second == cur }?.first ?: "Özel")
        btnColor.setTextColor(cur)
        btnMode.text = if (Prefs.cover(this))
            "Mod: Örtü (altyazıyı kapatır, hafif yanıp sönebilir)"
        else
            "Mod: Üstte göster (İngilizce altyazı altta kalır)"
        btnEngine.text = "Çeviri motoru: " +
            (Prefs.ENGINES.firstOrNull { it.first == Prefs.engine(this) }?.second ?: "")
        preview.update()
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Ayar ekranı yok. ADB: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
        } catch (e: Exception) {
            Toast.makeText(this, "Bu cihazda ekran yakalama izni ekranı yok", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            ContextCompat.startForegroundService(this, i)
            Toast.makeText(this, "Başladı. Kodi/Nuvio'ya geçebilirsin.", Toast.LENGTH_LONG).show()
        }
    }
}
