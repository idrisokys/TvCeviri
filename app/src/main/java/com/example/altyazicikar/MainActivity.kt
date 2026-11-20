package com.example.altyazicikar

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.Locale

class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var prefs: SharedPreferences
    private var dav: DavClient? = null
    private var curPath = "/"
    private var items: List<DavItem> = emptyList()
    private var inBrowser = false

    @Volatile
    private var cancelFlag = false
    private var busy: Busy? = null

    private inner class Busy(
        val dialog: AlertDialog,
        val title: TextView,
        val bar: ProgressBar,
        val detail: TextView
    ) {
        fun update(pos: Long, total: Long) {
            runOnUiThread {
                if (total > 0) {
                    bar.isIndeterminate = false
                    bar.max = 1000
                    bar.progress = (pos * 1000 / total).toInt().coerceIn(0, 1000)
                    detail.text = "${pos / 1048576} / ${total / 1048576} MB"
                } else {
                    detail.text = "${pos / 1048576} MB okundu"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val u = prefs.getString("url", null)
        val n = prefs.getString("user", null)
        val p = prefs.getString("pass", null)
        if (u != null && n != null && p != null && n.isNotEmpty()) {
            connect(u, n, p)
        } else {
            showLogin(null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val c = dav
        if (inBrowser && c != null && curPath != c.rootPath) {
            showBrowser(parentOf(curPath, c.rootPath))
        } else {
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- ekranlar

    private fun showLogin(message: String?) {
        inBrowser = false
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        val title = TextView(this).apply {
            text = "TorBox WebDAV"
            textSize = 22f
        }
        val info = TextView(this).apply {
            text = message ?: "Hesap bilgilerini gir. Bilgiler yalnızca bu telefonda saklanır."
            setPadding(0, dp(8), 0, dp(12))
        }
        val url = EditText(this).apply {
            hint = "Sunucu adresi"
            setText(prefs.getString("url", "https://webdav.torbox.app"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val user = EditText(this).apply {
            hint = "Kullanıcı adı / e-posta"
            setText(prefs.getString("user", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine()
        }
        val pass = EditText(this).apply {
            hint = "Şifre"
            setText(prefs.getString("pass", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val go = Button(this).apply {
            text = "Bağlan"
            setOnClickListener {
                connect(url.text.toString(), user.text.toString(), pass.text.toString())
            }
        }
        col.addView(title)
        col.addView(info)
        col.addView(url, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(user, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(pass, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(go, LinearLayout.LayoutParams(MATCH, WRAP))
        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    private fun connect(url: String, user: String, pass: String) {
        val client = try {
            DavClient(url, user, pass)
        } catch (e: Exception) {
            toast("Geçersiz sunucu adresi")
            showLogin("Geçersiz sunucu adresi")
            return
        }
        prefs.edit()
            .putString("url", url)
            .putString("user", user)
            .putString("pass", pass)
            .apply()
        dav = client
        showBrowser(client.rootPath)
    }

    private fun showBrowser(path: String) {
        val client = dav
        if (client == null) {
            showLogin(null)
            return
        }
        inBrowser = true
        curPath = path

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pathView = TextView(this).apply {
            text = Uri.decode(path)
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val up = Button(this).apply {
            text = "↑ Yukarı"
            isEnabled = path != client.rootPath
            setOnClickListener { showBrowser(parentOf(path, client.rootPath)) }
        }
        val all = Button(this).apply {
            text = "Hepsinden çıkar"
            setOnClickListener { batchFolder() }
        }
        val out = Button(this).apply {
            text = "Çıkış"
            setOnClickListener {
                prefs.edit().clear().apply()
                dav = null
                showLogin(null)
            }
        }
        bar.addView(up, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(all, LinearLayout.LayoutParams(0, WRAP, 1.4f))
        bar.addView(out, LinearLayout.LayoutParams(0, WRAP, 1f))

        val status = TextView(this).apply {
            text = "Yükleniyor…"
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val list = ListView(this)

        root.addView(pathView, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        list.setOnItemClickListener { _, _, position, _ ->
            if (position in items.indices) onItem(items[position])
        }

        Thread {
            try {
                val res = client.list(path)
                runOnUiThread {
                    if (curPath != path) return@runOnUiThread
                    items = res
                    status.text = if (res.isEmpty()) "Klasör boş" else "${res.size} öğe"
                    val labels = res.map {
                        if (it.isDir) "📁  ${it.name}" else "🎞  ${it.name}${sizeText(it.size)}"
                    }
                    list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
                }
            } catch (e: AuthFailed) {
                runOnUiThread { showLogin("Giriş başarısız: kullanıcı adı/şifreyi kontrol et") }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Hata: ${e.message ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun onItem(item: DavItem) {
        if (item.isDir) {
            showBrowser(item.href)
            return
        }
        if (!isVideo(item.name)) {
            toast("Yalnızca .mkv dosyaları destekleniyor")
            return
        }
        scanThenPick(item)
    }

    // ------------------------------------------------------------ tek dosya

    private fun scanThenPick(item: DavItem) {
        val client = dav ?: return
        showBusy("Altyazı akışları okunuyor…", false)
        Thread {
            try {
                val header = client.readHeader(item)
                runOnUiThread {
                    dismissBusy()
                    pickTracks(item, header.tracks)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissBusy()
                    showError(e)
                }
            }
        }.start()
    }

    private fun pickTracks(item: DavItem, tracks: List<MkvTrack>) {
        if (tracks.isEmpty()) {
            toast("Bu dosyada altyazı akışı yok")
            return
        }
        val labels = tracks.map { it.label() }.toTypedArray<CharSequence>()
        val checked = BooleanArray(tracks.size)
        val firstOk = tracks.indexOfFirst { it.supported }
        if (firstOk >= 0) checked[firstOk] = true
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Çıkar") { _, _ ->
                val sel = tracks.filterIndexed { i, t -> checked[i] && t.supported }
                if (sel.isEmpty()) toast("Desteklenen bir altyazı seç") else extractSingle(item, sel)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun extractSingle(item: DavItem, sel: List<MkvTrack>) {
        val b = showBusy(item.name, item.size > 0)
        Thread {
            try {
                val saved = extractTo(item, sel, b)
                runOnUiThread {
                    dismissBusy()
                    showReport(saved, emptyList())
                }
            } catch (e: CancelledException) {
                runOnUiThread {
                    dismissBusy()
                    toast("İptal edildi")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissBusy()
                    showError(e)
                }
            }
        }.start()
    }

    /** Dosyayı akışla okur, seçili izleri İndirilenler/Altyazi içine yazar, dosya adlarını döndürür. */
    private fun extractTo(item: DavItem, sel: List<MkvTrack>, b: Busy): List<String> {
        val client = dav ?: throw IOException("Bağlantı yok")
        var last = 0L
        val res = client.collect(item, sel.map { it.number }.toSet(), { cancelFlag }) { pos ->
            val now = System.currentTimeMillis()
            if (now - last > 250) {
                last = now
                b.update(pos, item.size)
            }
        }
        val base = safeName(item.name.substringBeforeLast('.'))
        val names = ArrayList<String>()
        for (t in sel) {
            val ev = res.events[t.number] ?: emptyList()
            if (ev.isEmpty()) continue
            val data = SubtitleWriter.build(t, ev)
            val lang = safeName(t.language.ifBlank { "und" })
            val dupe = sel.count { it.language == t.language } > 1
            val name = if (dupe) "$base.$lang.t${t.number}.${t.ext}" else "$base.$lang.${t.ext}"
            saveToDownloads(name, data)
            names.add(name)
        }
        if (names.isEmpty()) throw IOException("Seçilen akışta altyazı satırı bulunamadı")
        return names
    }

    // ---------------------------------------------------------- toplu işlem

    private fun batchFolder() {
        val vids = items.filter { !it.isDir && isVideo(it.name) }
        if (vids.isEmpty()) {
            toast("Bu klasörde .mkv dosyası yok")
            return
        }
        val et = EditText(this).apply {
            hint = "tur, eng, jpn … (boş = ilk altyazı)"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("${vids.size} dosya için dil kodu")
            .setMessage("Her dosyadan bu dile uyan ilk metin altyazısı çıkarılır.")
            .setView(et)
            .setPositiveButton("Başla") { _, _ -> runBatch(vids, et.text.toString().trim()) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun runBatch(vids: List<DavItem>, lang: String) {
        val b = showBusy("Başlıyor…", true)
        Thread {
            val ok = ArrayList<String>()
            val fail = ArrayList<String>()
            for ((i, v) in vids.withIndex()) {
                if (cancelFlag) break
                runOnUiThread {
                    b.title.text = "${i + 1}/${vids.size}  ${v.name}"
                    b.bar.progress = 0
                }
                try {
                    val client = dav ?: throw IOException("Bağlantı yok")
                    val h = client.readHeader(v)
                    val cands = h.tracks.filter { it.supported }
                    val pick = cands.firstOrNull { Lang.matches(it, lang) }
                    if (pick == null) {
                        fail.add("${v.name}: uygun altyazı bulunamadı")
                        continue
                    }
                    ok.addAll(extractTo(v, listOf(pick), b))
                } catch (e: CancelledException) {
                    break
                } catch (e: Exception) {
                    fail.add("${v.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            runOnUiThread {
                dismissBusy()
                showReport(ok, fail)
            }
        }.start()
    }

    // ------------------------------------------------------------ yardımcılar

    private fun showBusy(title: String, determinate: Boolean): Busy {
        cancelFlag = false
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val t = TextView(this).apply {
            text = title
            textSize = 15f
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = !determinate
            max = 1000
        }
        val d = TextView(this).apply { textSize = 13f }
        col.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(d, LinearLayout.LayoutParams(MATCH, WRAP))
        val dlg = AlertDialog.Builder(this)
            .setView(col)
            .setCancelable(false)
            .setNegativeButton("İptal") { _, _ -> cancelFlag = true }
            .create()
        dlg.show()
        val b = Busy(dlg, t, bar, d)
        busy = b
        return b
    }

    private fun dismissBusy() {
        try {
            busy?.dialog?.dismiss()
        } catch (_: Exception) {
        }
        busy = null
    }

    private fun showReport(ok: List<String>, fail: List<String>) {
        val sb = StringBuilder()
        if (ok.isNotEmpty()) {
            sb.append("Kaydedildi (İndirilenler/Altyazi):\n")
            for (n in ok) sb.append("• ").append(n).append('\n')
        }
        if (fail.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("Başarısız:\n")
            for (n in fail) sb.append("• ").append(n).append('\n')
        }
        if (sb.isEmpty()) sb.append("Hiçbir şey çıkarılmadı.")
        AlertDialog.Builder(this)
            .setTitle("Sonuç")
            .setMessage(sb.toString())
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun showError(e: Exception) {
        if (e is AuthFailed) {
            showLogin("Giriş reddedildi")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Hata")
            .setMessage(e.message ?: e.javaClass.simpleName)
            .setPositiveButton("Tamam", null)
            .show()
    }

    /** Android 10+ için İndirilenler/Altyazi klasörüne izinsiz yazar. */
    private fun saveToDownloads(name: String, data: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Altyazi")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Dosya oluşturulamadı")
        val os = contentResolver.openOutputStream(uri) ?: throw IOException("Dosya yazılamadı")
        os.use { it.write(data) }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        contentResolver.update(uri, done, null, null)
    }

    private fun parentOf(path: String, rootPath: String): String {
        val trimmed = path.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "") + "/"
        return if (parent.length < rootPath.length) rootPath else parent
    }

    private fun isVideo(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".mkv") || n.endsWith(".webm")
    }

    private fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun sizeText(size: Long): String {
        if (size <= 0) return ""
        return if (size >= 1_073_741_824L) {
            String.format(Locale.US, "  (%.1f GB)", size / 1_073_741_824.0)
        } else {
            "  (${size / 1_048_576} MB)"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
