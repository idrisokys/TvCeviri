package com.example.altyazicikar

import android.net.Uri
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.w3c.dom.Element
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class AuthFailed : IOException("Kullanıcı adı veya şifre reddedildi")

class DavItem(val name: String, val href: String, val isDir: Boolean, val size: Long)

class CollectResult(val header: MkvHeader, val events: Map<Long, List<SubEvent>>)

class DavClient(baseUrl: String, user: String, pass: String) {
    val origin: String
    val rootPath: String
    private val auth = Credentials.basic(user, pass, Charsets.UTF_8)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        var u = baseUrl.trim()
        if (!u.contains("://")) u = "https://$u"
        val uri = URI(u)
        origin = "${uri.scheme}://${uri.rawAuthority}"
        var p = uri.rawPath ?: ""
        if (p.isEmpty()) p = "/"
        if (!p.endsWith("/")) p += "/"
        rootPath = p
    }

    fun urlFor(path: String): String = origin + path

    fun list(path: String): List<DavItem> {
        val body = ("<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><allprop/></propfind>")
            .toRequestBody("application/xml".toMediaType())
        val req = Request.Builder()
            .url(urlFor(path))
            .method("PROPFIND", body)
            .header("Depth", "1")
            .header("Authorization", auth)
            .build()
        return http.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) throw AuthFailed()
            if (!resp.isSuccessful) throw IOException("Sunucu hatası: HTTP ${resp.code}")
            parse(resp.body!!.byteStream(), path)
        }
    }

    private fun parse(stream: InputStream, requested: String): List<DavItem> {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        val doc = f.newDocumentBuilder().parse(stream)
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val reqNorm = norm(requested)
        val out = ArrayList<DavItem>()
        for (i in 0 until responses.length) {
            val el = responses.item(i) as Element
            val hrefs = el.getElementsByTagNameNS("DAV:", "href")
            if (hrefs.length == 0) continue
            val rawPath = hrefPath(hrefs.item(0).textContent.trim())
            if (norm(rawPath) == reqNorm) continue
            val isDir = el.getElementsByTagNameNS("DAV:", "collection").length > 0
            val lens = el.getElementsByTagNameNS("DAV:", "getcontentlength")
            val size = if (lens.length > 0) lens.item(0).textContent.trim().toLongOrNull() ?: -1L else -1L
            val name = Uri.decode(rawPath.trimEnd('/').substringAfterLast('/'))
            if (name.isEmpty()) continue
            val href = if (isDir && !rawPath.endsWith("/")) "$rawPath/" else rawPath
            out.add(DavItem(name, href, isDir, size))
        }
        out.sortWith(Comparator { a, b ->
            if (a.isDir != b.isDir) {
                if (a.isDir) -1 else 1
            } else {
                natural(a.name, b.name)
            }
        })
        return out
    }

    private fun norm(p: String): String = Uri.decode(p).trimEnd('/')

    private fun hrefPath(h: String): String {
        if (h.startsWith("http://") || h.startsWith("https://")) {
            return try {
                URI(h).rawPath ?: "/"
            } catch (e: Exception) {
                "/" + h.substringAfter("://").substringAfter('/')
            }
        }
        return h
    }

    private fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** Yalnızca iz listesini okur (ilk Cluster'a kadar, birkaç KB). */
    fun readHeader(item: DavItem): MkvHeader {
        val s = HttpByteSource(http, urlFor(item.href), auth)
        try {
            return MkvParser(s).readHeader()
        } finally {
            s.close()
        }
    }

    /** Dosyayı akışla okuyup seçilen izlerin bloklarını toplar. */
    fun collect(
        item: DavItem,
        wanted: Set<Long>,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit
    ): CollectResult {
        val s = HttpByteSource(http, urlFor(item.href), auth)
        try {
            val parser = MkvParser(s, cancelled, progress)
            val header = parser.collect(wanted)
            return CollectResult(header, parser.events)
        } finally {
            s.close()
        }
    }
}

/**
 * HTTP üzerinden akış okuyan ByteSource. Sunucu Range destekliyorsa büyük atlamalar
 * (ekler, Cues) yeni bir istekle yapılır; bağlantı koparsa kaldığı yerden devam eder.
 */
class HttpByteSource(
    private val http: OkHttpClient,
    private val url: String,
    private val auth: String
) : ByteSource {
    private companion object {
        const val SKIP_REOPEN = 4L * 1024 * 1024
    }

    private var response: Response? = null
    private var source: BufferedSource? = null
    private var eof = false

    override var pos: Long = 0
        private set

    init {
        open(0)
    }

    private fun open(at: Long) {
        close()
        eof = false
        val rb = Request.Builder().url(url).header("Authorization", auth)
        if (at > 0) rb.header("Range", "bytes=$at-")
        val resp = http.newCall(rb.build()).execute()
        if (resp.code == 416) {
            resp.close()
            eof = true
            pos = at
            return
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            if (code == 401 || code == 403) throw AuthFailed()
            throw IOException("Dosya açılamadı: HTTP $code")
        }
        response = resp
        val s = resp.body!!.source()
        source = s
        pos = at
        // Sunucu Range'i yok saydıysa (200 döndüyse) baştan gelen veriyi atla.
        if (at > 0 && resp.code == 200) s.skip(at)
    }

    private inline fun <T> retrying(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: EOFException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt > 3) throw e
                Thread.sleep(1000L * attempt)
                open(pos)
            }
        }
    }

    override fun readByte(): Int {
        if (eof) return -1
        return retrying {
            val s = source!!
            if (s.exhausted()) {
                -1
            } else {
                val v = s.readByte().toInt() and 0xFF
                pos++
                v
            }
        }
    }

    override fun readBytes(n: Int): ByteArray {
        if (n == 0) return ByteArray(0)
        if (eof) throw EOFException()
        return retrying {
            val b = source!!.readByteArray(n.toLong())
            pos += n
            b
        }
    }

    override fun skip(n: Long) {
        if (n <= 0) return
        if (eof) {
            pos += n
            return
        }
        if (n > SKIP_REOPEN) {
            open(pos + n)
        } else {
            retrying {
                source!!.skip(n)
                pos += n
            }
        }
    }

    fun close() {
        try {
            response?.close()
        } catch (_: Exception) {
        }
        response = null
        source = null
    }
}
