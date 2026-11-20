package com.example.altyazicikar

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.LinkedHashSet

class MainActivity : Activity() {

    private val TARGET_DIR = "X Video Player"
    private val TARGET_LABEL = "X Video Player"
    private val FALLBACK_LABEL = "İndirilenler/Altyazi"
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var prefs: SharedPreferences
    private var dav: DavClient? = null
    private var curPath = "/"
    private var items: List<DavItem> = emptyList()
    private val selected = LinkedHashSet<String>()
    private val DEFAULT_LANG = "eng"
    private var inBrowser = false
    private var sortByDate = true

    @Volatile
    private var cancelFlag = false
    @Volatile
    private var usedFallback = false
    private var askedAccess = false
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
        sortByDate = prefs.getBoolean("sortDate", true)
        val u = prefs.getString("url", null)
        val n = prefs.getString("user", null)
        val p = prefs.getString("pass", null)
        if (u != null && n != null && p != null && n.isNotEmpty()) {
            connect(u, n, p)
        } else {
            showLogin(null)
        }
        askStorageAccess()
    }

    override fun onResume() {
        super.onResume()
        if (askedAccess) toastIfNoAccess()
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
        selected.clear()
        items = emptyList()
        var shown: List<DavItem> = emptyList()
        var rawItems: List<DavItem> = emptyList()
        var query = ""

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pathView = TextView(this).apply {
            text = Uri.decode(path)
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }

        // 1. satır: Geri + arama kutusu + aA (sıralama)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val up = Button(this).apply {
            text = "← Geri"
            setOnClickListener {
                if (path != client.rootPath) showBrowser(parentOf(path, client.rootPath))
                else toast("En üst klasördesin")
            }
            // Hesaptan çıkış: Geri düğmesine uzun bas
            setOnLongClickListener { confirmLogout(); true }
        }
        val sortBtn = Button(this).apply { text = "aA" }
        val search = EditText(this).apply {
            hint = "🔍 Bu klasörde ara…"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        bar.addView(up, LinearLayout.LayoutParams(WRAP, WRAP))
        bar.addView(search, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(sortBtn, LinearLayout.LayoutParams(WRAP, WRAP))

        // 2. satır: seçim düğmeleri
        val bar2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val selAll = Button(this).apply { text = "Tümünü seç"; textSize = 12f }
        val go = Button(this).apply { text = "Çıkar (0)"; textSize = 12f; isEnabled = false }
        bar2.addView(selAll, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar2.addView(go, LinearLayout.LayoutParams(0, WRAP, 1f))

        val status = TextView(this).apply {
            text = "Yükleniyor…"
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val list = ListView(this)

        root.addView(pathView, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(bar2, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        lateinit var adapter: RowAdapter

        fun refreshHeader() {
            val allSel = shown.isNotEmpty() && shown.all { selected.contains(it.href) }
            selAll.text = if (allSel) "Seçimi kaldır" else "Tümünü seç"
            selAll.isEnabled = shown.isNotEmpty()
            go.text = "Çıkar (${selected.size})"
            go.isEnabled = selected.isNotEmpty()
            val order = if (sortByDate) "en yeni üstte" else "A-Z"
            status.text = when {
                items.isEmpty() -> "Klasör boş"
                query.isNotEmpty() -> "${shown.size} / ${items.size} öğe · ${selected.size} seçili · $order"
                else -> "${items.size} öğe · ${selected.size} seçili · $order"
            }
        }

        fun applyFilter() {
            // Liste sunucudan klasör-önce + doğal ada göre gelir (A-Z);
            // tarih modunda en yeni değişiklik en üste (sıralama kararlı).
            items = if (sortByDate) rawItems.sortedByDescending { it.modified } else rawItems
            val q = query.trim().lowercase(Locale.ROOT)
            shown = if (q.isEmpty()) items
            else items.filter { it.name.lowercase(Locale.ROOT).contains(q) }
            adapter.setRows(shown)
            refreshHeader()
        }

        adapter = RowAdapter { refreshHeader() }
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                applyFilter()
            }
        })

        sortBtn.setOnClickListener {
            sortByDate = !sortByDate
            prefs.edit().putBoolean("sortDate", sortByDate).apply()
            applyFilter()
            list.setSelection(0)
            toast(if (sortByDate) "Sıralama: en yeni üstte" else "Sıralama: harf sırası")
        }

        selAll.setOnClickListener {
            val allSel = shown.isNotEmpty() && shown.all { selected.contains(it.href) }
            if (allSel) shown.forEach { selected.remove(it.href) }
            else shown.forEach { selected.add(it.href) }
            adapter.notifyDataSetChanged()
            refreshHeader()
        }

        // Dokununca doğrudan başlar (varsayılan dil: eng). Uzun basınca dil sorulur.
        go.setOnClickListener {
            val sel = items.filter { selected.contains(it.href) }
            if (sel.isNotEmpty()) runBatch(sel, prefs.getString("lang", DEFAULT_LANG) ?: DEFAULT_LANG)
        }
        go.setOnLongClickListener {
            val sel = items.filter { selected.contains(it.href) }
            if (sel.isNotEmpty()) askBatch(sel)
            true
        }

        list.setOnItemClickListener { _, _, position, _ ->
            if (position in shown.indices) onItem(shown[position])
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (position in shown.indices) {
                val h = shown[position].href
                if (!selected.add(h)) selected.remove(h)
                adapter.notifyDataSetChanged()
                refreshHeader()
            }
            true
        }

        Thread {
            try {
                val res = client.list(path)
                runOnUiThread {
                    if (curPath != path) return@runOnUiThread
                    rawItems = res
                    applyFilter()
                }
            } catch (e: AuthFailed) {
                runOnUiThread { showLogin("Giriş başarısız: kullanıcı adı/şifreyi kontrol et") }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Hata: ${e.message ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Çıkış")
            .setMessage("Kayıtlı hesap bilgileri silinsin mi?")
            .setPositiveButton("Çıkış yap") { _, _ ->
                prefs.edit().remove("url").remove("user").remove("pass").apply()
                dav = null
                showLogin(null)
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** Onay kutulu liste satırları. Kutu seçer, satıra dokunmak klasörü açar / dosyayı tarar. */
    private inner class RowAdapter(val onChange: () -> Unit) : BaseAdapter() {
        private var rows: List<DavItem> = emptyList()

        fun setRows(r: List<DavItem>) {
            rows = r
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val cb: CheckBox
            val tv: TextView
            if (convertView is LinearLayout) {
                row = convertView
                cb = row.getChildAt(0) as CheckBox
                tv = row.getChildAt(1) as TextView
            } else {
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(6), dp(12), dp(6))
                }
                cb = CheckBox(this@MainActivity).apply { isFocusable = false }
                tv = TextView(this@MainActivity).apply {
                    textSize = 16f
                    setPadding(dp(4), dp(8), 0, dp(8))
                }
                row.addView(cb, LinearLayout.LayoutParams(WRAP, WRAP))
                row.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            val d = rows[position]
            tv.text = if (d.isDir) "📁  ${d.name}" else "🎞  ${d.name}${sizeText(d.size)}"
            cb.isChecked = selected.contains(d.href)
            cb.setOnClickListener {
                val h = rows[position].href
                if (cb.isChecked) selected.add(h) else selected.remove(h)
                onChange()
            }
            return row
        }
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
        val lang = prefs.getString("lang", DEFAULT_LANG) ?: DEFAULT_LANG
        val best = Lang.pickBest(tracks, lang) ?: tracks.firstOrNull { it.supported }
        val bi = tracks.indexOf(best)
        if (bi >= 0) checked[bi] = true
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
        usedFallback = false
        val b = showBusy(item.name, item.size > 0)
        Thread {
            try {
                val saved = extractTo(item, sel, b)
                runOnUiThread {
                    dismissBusy()
                    finishWork(saved, emptyList())
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

    /** Dosyayı akışla okur, seçili izleri SRT'ye çevirip kaydeder, dosya adlarını döndürür. */
    private fun extractTo(item: DavItem, sel: List<MkvTrack>, b: Busy, prefix: String = ""): List<String> {
        val client = dav ?: throw IOException("Bağlantı yok")
        var last = 0L
        val res = client.collect(item, sel.map { it.number }.toSet(), { cancelFlag }) { pos ->
            val now = System.currentTimeMillis()
            if (now - last > 250) {
                last = now
                b.update(pos, item.size)
            }
        }
        val base = safeName(prefix + item.name.substringBeforeLast('.'))
        val names = ArrayList<String>()
        for (t in sel) {
            val ev = res.events[t.number] ?: emptyList()
            if (ev.isEmpty()) continue
            val data = SubtitleWriter.buildSrt(t, ev)
            val lang = safeName(t.language.ifBlank { "und" })
            val dupe = sel.count { it.language == t.language } > 1
            val name = if (dupe) "$base.$lang.t${t.number}.srt" else "$base.$lang.srt"
            saveSubtitle(name, data)
            names.add(name)
        }
        if (names.isEmpty()) throw IOException("Seçilen akışta altyazı satırı bulunamadı")
        return names
    }

    // ---------------------------------------------------------- toplu işlem

    /** Seçili dosya/klasörler için dil kodunu sorar (varsayılan: eng) ve işlemi başlatır. */
    private fun askBatch(sel: List<DavItem>) {
        val dirs = sel.count { it.isDir }
        val files = sel.size - dirs
        val et = EditText(this).apply {
            hint = "eng, tur, jpn … (boş = ilk altyazı)"
            setText(prefs.getString("lang", DEFAULT_LANG))
            setSingleLine()
        }
        val parts = ArrayList<String>()
        if (files > 0) parts.add("$files dosya")
        if (dirs > 0) parts.add("$dirs klasör (alt klasörleriyle taranır)")
        AlertDialog.Builder(this)
            .setTitle("Altyazı dili")
            .setMessage("Seçili: ${parts.joinToString(", ")}.\nHer videodan bu dile uyan en uygun metin altyazısı SRT olarak çıkarılır.")
            .setView(et)
            .setPositiveButton("Başla") { _, _ ->
                val lang = et.text.toString().trim()
                prefs.edit().putString("lang", lang).apply()
                runBatch(sel, lang)
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** Seçilen dosyaları alır, klasörleri alt klasörleriyle gezip .mkv/.webm dosyalarını toplar. */
    private fun gatherVideos(client: DavClient, start: List<DavItem>, fail: MutableList<String>): List<DavItem> {
        val out = LinkedHashMap<String, DavItem>()
        fun walk(node: DavItem, depth: Int) {
            if (cancelFlag) throw CancelledException()
            if (!node.isDir) {
                if (isVideo(node.name)) out[node.href] = node
                return
            }
            if (depth > 8) return
            val kids = try {
                client.list(node.href)
            } catch (e: AuthFailed) {
                throw e
            } catch (e: Exception) {
                fail.add("${node.name}: klasör okunamadı (${e.message ?: e.javaClass.simpleName})")
                return
            }
            for (k in kids) walk(k, depth + 1)
        }
        for (s in start) walk(s, 0)
        return out.values.toList()
    }

    private fun parentName(href: String): String =
        Uri.decode(href.trimEnd('/').substringBeforeLast('/', "").substringAfterLast('/'))

    private fun runBatch(sel: List<DavItem>, lang: String) {
        val b = showBusy("Klasörler taranıyor…", false)
        Thread {
            val ok = ArrayList<String>()
            val fail = ArrayList<String>()
            try {
                val client = dav ?: throw IOException("Bağlantı yok")
                val vids = gatherVideos(client, sel, fail)
                if (vids.isEmpty()) {
                    runOnUiThread {
                        dismissBusy()
                        finishWork(ok, if (fail.isEmpty()) listOf("Seçimde .mkv/.webm dosyası yok") else fail)
                    }
                    return@Thread
                }
                // Farklı klasörlerde aynı adlı videolar birbirinin üzerine yazılmasın
                val nameCount = vids.groupingBy { it.name }.eachCount()
                usedFallback = false
                for ((i, v) in vids.withIndex()) {
                    if (cancelFlag) break
                    runOnUiThread {
                        b.bar.isIndeterminate = false
                        b.title.text = "${i + 1}/${vids.size}  ${v.name}"
                        b.bar.progress = 0
                    }
                    try {
                        val h = client.readHeader(v)
                        val pick = Lang.pickBest(h.tracks, lang)
                        if (pick == null) {
                            fail.add("${v.name}: uygun altyazı bulunamadı")
                            continue
                        }
                        val prefix = if ((nameCount[v.name] ?: 1) > 1) parentName(v.href) + " - " else ""
                        ok.addAll(extractTo(v, listOf(pick), b, prefix))
                    } catch (e: CancelledException) {
                        break
                    } catch (e: AuthFailed) {
                        throw e
                    } catch (e: Exception) {
                        fail.add("${v.name}: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            } catch (e: CancelledException) {
                // işlem kullanıcı tarafından iptal edildi; eldeki sonuç gösterilir
            } catch (e: Exception) {
                fail.add(e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                dismissBusy()
                finishWork(ok, fail)
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

    /**
     * İşlem bitince: hepsi başarılıysa uygulama kendiliğinden kapanır.
     * Hata varsa rapor gösterilir ve Tamam'a basılınca (en az bir altyazı çıktıysa) kapanır.
     * İptal edildiyse uygulama açık kalır.
     */
    private fun finishWork(ok: List<String>, fail: List<String>) {
        val cancelled = cancelFlag
        if (ok.isNotEmpty() && fail.isEmpty() && !cancelled) {
            val where = if (usedFallback) FALLBACK_LABEL else TARGET_LABEL
            Toast.makeText(this, "${ok.size} altyazı kaydedildi → $where", Toast.LENGTH_LONG).show()
            finishAndRemoveTask()
        } else {
            showReport(ok, fail, closeAfter = ok.isNotEmpty() && !cancelled)
        }
    }

    private fun showReport(ok: List<String>, fail: List<String>, closeAfter: Boolean = false) {
        val sb = StringBuilder()
        if (ok.isNotEmpty()) {
            sb.append("Kaydedildi (").append(if (usedFallback) FALLBACK_LABEL else TARGET_LABEL).append("):\n")
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
            .setOnDismissListener { if (closeAfter) finishAndRemoveTask() }
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

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askStorageAccess() {
        if (hasStorageAccess()) return
        askedAccess = true
        AlertDialog.Builder(this)
            .setTitle("Depolama izni")
            .setMessage("Altyazıların otomatik olarak \"$TARGET_LABEL\" klasörüne kaydedilmesi için tüm dosyalara erişim izni gerekir. İzin vermezsen $FALLBACK_LABEL klasörüne kaydedilir.")
            .setPositiveButton("İzin ver") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                }
            }
            .setNegativeButton("Şimdi değil", null)
            .show()
    }

    private fun toastIfNoAccess() {
        askedAccess = false
        if (!hasStorageAccess()) toast("İzin verilmedi: $FALLBACK_LABEL kullanılacak")
    }

    /** X video player/subtitle klasörüne yazar; izin yoksa İndirilenler/Altyazi'ya düşer. */
    private fun saveSubtitle(name: String, data: ByteArray) {
        if (hasStorageAccess()) {
            try {
                val dir = File(Environment.getExternalStorageDirectory(), TARGET_DIR)
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Klasör oluşturulamadı")
                val f = File(dir, name)
                f.writeBytes(data)
                MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)
                return
            } catch (e: Exception) {
                // izin var ama yazılamadıysa yedek konuma düş
            }
        }
        usedFallback = true
        saveToDownloads(name, data)
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
