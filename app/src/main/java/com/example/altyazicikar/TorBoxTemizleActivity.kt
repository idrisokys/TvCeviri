package com.example.altyazicikar

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TorBox API ile hesaptaki tüm indirmeleri (torrent, usenet, web indirmeleri) siler.
 * Uygulamanın diğer bölümlerinden bağımsızdır; yalnızca kendi API anahtarını kullanır.
 */
class TorBoxTemizleActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val BASE = "https://api.torbox.app/v1/api"

    private class Kind(val label: String, val list: String, val control: String, val idKey: String)

    private val kinds = listOf(
        Kind("Torrent", "torrents/mylist", "torrents/controltorrent", "torrent_id"),
        Kind("Usenet", "usenet/mylist", "usenet/controlusenetdownload", "usenet_id"),
        Kind("Web indirme", "webdl/mylist", "webdl/controlwebdownload", "webdl_id")
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: SharedPreferences
    private lateinit var keyEdit: EditText
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var checkBtn: Button
    private lateinit var wipeBtn: Button

    @Volatile
    private var cancelFlag = false
    @Volatile
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        val title = TextView(this).apply {
            text = "TorBox Temizle"
            textSize = 22f
        }
        val info = TextView(this).apply {
            text = "TorBox API anahtarınla hesabındaki tüm indirmeler (torrent, usenet, web indirmeleri) silinir. " +
                "Anahtarı TorBox → Settings → API Key bölümünden alabilirsin. Bu işlem geri alınamaz."
            setPadding(0, dp(8), 0, dp(12))
        }
        keyEdit = EditText(this).apply {
            hint = "TorBox API anahtarı"
            setText(prefs.getString("torbox_key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        checkBtn = Button(this).apply {
            text = "İndirmeleri say"
            setOnClickListener { startCount() }
        }
        wipeBtn = Button(this).apply {
            text = "Tüm indirmeleri sil"
            setOnClickListener { confirmWipe() }
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = android.view.View.GONE
        }
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(title)
        col.addView(info)
        col.addView(keyEdit, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(checkBtn, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(wipeBtn, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    override fun onBackPressed() {
        if (running) {
            cancelFlag = true
            toast("İptal ediliyor…")
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ işlemler

    private fun apiKey(): String? {
        val k = keyEdit.text.toString().trim()
        if (k.isEmpty()) {
            toast("API anahtarını gir")
            return null
        }
        prefs.edit().putString("torbox_key", k).apply()
        return k
    }

    private fun setBusy(b: Boolean) {
        running = b
        checkBtn.isEnabled = !b
        wipeBtn.isEnabled = !b
        keyEdit.isEnabled = !b
        bar.visibility = if (b) android.view.View.VISIBLE else android.view.View.GONE
        bar.isIndeterminate = true
    }

    private fun startCount() {
        val key = apiKey() ?: return
        setBusy(true)
        status.text = "Sayılıyor…"
        Thread {
            try {
                val sb = StringBuilder()
                var total = 0
                for (k in kinds) {
                    val ids = fetchIds(key, k)
                    total += ids.size
                    sb.append("${k.label}: ${ids.size}\n")
                }
                sb.append("\nToplam: $total")
                runOnUiThread { status.text = sb.toString() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Hata: ${e.message ?: e.javaClass.simpleName}" }
            }
            runOnUiThread { setBusy(false) }
        }.start()
    }

    private fun confirmWipe() {
        val key = apiKey() ?: return
        setBusy(true)
        status.text = "Sayılıyor…"
        Thread {
            try {
                val found = kinds.associateWith { fetchIds(key, it) }
                val total = found.values.sumOf { it.size }
                runOnUiThread {
                    setBusy(false)
                    if (total == 0) {
                        status.text = "Hesapta silinecek indirme yok."
                        return@runOnUiThread
                    }
                    val parts = found.entries.filter { it.value.isNotEmpty() }
                        .joinToString("\n") { "• ${it.key.label}: ${it.value.size}" }
                    AlertDialog.Builder(this)
                        .setTitle("Hepsi silinsin mi?")
                        .setMessage("$total indirme TorBox hesabından kalıcı olarak silinecek:\n\n$parts\n\nBu işlem geri alınamaz.")
                        .setPositiveButton("Evet, sil") { _, _ -> wipe(key, found) }
                        .setNegativeButton("Vazgeç", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    status.text = "Hata: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun wipe(key: String, found: Map<Kind, List<Long>>) {
        cancelFlag = false
        setBusy(true)
        val total = found.values.sumOf { it.size }
        runOnUiThread {
            bar.isIndeterminate = false
            bar.progress = 0
        }
        Thread {
            var done = 0
            var failed = 0
            var firstError: String? = null
            try {
                outer@ for ((k, ids) in found) {
                    for (id in ids) {
                        if (cancelFlag) break@outer
                        try {
                            delete(key, k, id)
                        } catch (e: Exception) {
                            failed++
                            if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
                        }
                        done++
                        val d = done
                        runOnUiThread {
                            bar.progress = (d * 1000L / total).toInt()
                            status.text = "Siliniyor… $d / $total (${k.label})"
                        }
                    }
                }
            } finally {
                val ok = done - failed
                val cancelled = cancelFlag
                val msg = StringBuilder()
                msg.append(if (cancelled) "İptal edildi. " else "Bitti. ")
                msg.append("$ok indirme silindi")
                if (failed > 0) msg.append(", $failed başarısız (${firstError ?: ""})")
                runOnUiThread {
                    status.text = msg.toString()
                    setBusy(false)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ TorBox API

    private fun fetchIds(key: String, k: Kind): List<Long> {
        val req = Request.Builder()
            .url("$BASE/${k.list}?bypass_cache=true")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) throw IOException("API anahtarı reddedildi")
            if (!resp.isSuccessful) throw IOException("${k.label} listesi alınamadı (HTTP ${resp.code})")
            val json = JSONObject(body)
            val arr = json.optJSONArray("data") ?: return emptyList()
            val out = ArrayList<Long>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.has("id")) out.add(o.getLong("id"))
            }
            return out
        }
    }

    private fun delete(key: String, k: Kind, id: Long) {
        val payload = JSONObject().put(k.idKey, id).put("operation", "delete").toString()
        val req = Request.Builder()
            .url("$BASE/${k.control}")
            .header("Authorization", "Bearer $key")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = try { JSONObject(body).optString("detail") } catch (_: Exception) { "" }
                throw IOException(detail.ifBlank { "HTTP ${resp.code}" })
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
