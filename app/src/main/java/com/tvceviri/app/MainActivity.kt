package com.tvceviri.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val reqCapture = 42
    private lateinit var btnRegion: Button
    private lateinit var btnText: Button
    private lateinit var btnMode: Button
    private lateinit var btnEngine: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(120, 60, 120, 60)
            setBackgroundColor(Color.parseColor("#121212"))
        }
        root.addView(TextView(this).apply {
            text = "TV Çeviri (EN → TR)"
            textSize = 30f
            setTextColor(Color.WHITE)
        })

        fun btn(label: String, onClick: (Button) -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.isFocusable = true
            b.setOnClickListener { onClick(b) }
            root.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 })
            return b
        }

        btn("Başlat") { start() }.requestFocus()
        btn("Durdur") { stopService(Intent(this, CaptureService::class.java)) }

        btnRegion = btn("") {
            val opts = listOf(20, 25, 30, 35, 40, 50)
            val next = opts[(opts.indexOf(Prefs.region(this)) + 1) % opts.size]
            Prefs.setRegion(this, next); refresh()
        }
        btnText = btn("") {
            val opts = listOf(20, 24, 28, 32, 36, 40, 44)
            val next = opts[(opts.indexOf(Prefs.textSp(this)) + 1) % opts.size]
            Prefs.setTextSp(this, next); refresh()
        }
        btnMode = btn("") {
            Prefs.setCover(this, !Prefs.cover(this)); refresh()
        }
        btnEngine = btn("") {
            Prefs.setOnline(this, !Prefs.online(this)); refresh()
        }

        root.addView(TextView(this).apply {
            text = "Önce 'Başlat'a bas, izinleri ver, sonra Kodi/Nuvio'ya geç. " +
                "İlk çalıştırmada çeviri modeli indirilir (internet gerekir)."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 32, 0, 0)
        })
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        btnRegion.text = "Altyazı bölgesi: ekranın alt %${Prefs.region(this)}'i"
        refreshEngine()
        btnText.text = "Yazı boyutu: ${Prefs.textSp(this)}"
        btnMode.text = if (Prefs.cover(this))
            "Mod: Örtü (altyazıyı kapatır, hafif yanıp sönebilir)"
        else
            "Mod: Üstte göster (İngilizce altyazı altta kalır)"
    }

    private fun refreshEngine() {
        btnEngine.text = if (Prefs.online(this))
            "Çeviri: Online (Google, daha kaliteli) - internet yoksa offline'a düşer"
        else
            "Çeviri: Offline (ML Kit, internetsiz ama zayıf)"
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
