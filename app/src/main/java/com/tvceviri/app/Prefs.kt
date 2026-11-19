package com.tvceviri.app

import android.content.Context

object Prefs {
    val ENGINES = listOf(
        "google" to "Google",
        "microsoft" to "Microsoft Translator"
    )

    private fun sp(c: Context) = c.getSharedPreferences("tvceviri", Context.MODE_PRIVATE)
    private fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    private fun putInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    private fun str(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d
    private fun putStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()

    /** Ekranın altından yüzde kaçı altyazı bölgesi olarak taranır (1-50). */
    fun region(c: Context) = int(c, "region", 30)
    fun setRegion(c: Context, v: Int) = putInt(c, "region", v)

    fun textSp(c: Context) = int(c, "textSp", 28)
    fun setTextSp(c: Context, v: Int) = putInt(c, "textSp", v)

    fun color(c: Context) = int(c, "color", 0xFFFFEB00.toInt())      // sarı
    fun setColor(c: Context, v: Int) = putInt(c, "color", v)

    /** Kenarlık kalınlığı 0-100 (%). Varsayılan 20. */
    fun outline(c: Context) = int(c, "outline", 20)
    fun setOutline(c: Context, v: Int) = putInt(c, "outline", v)

    /** Yazı saydamlığı: görünürlük yüzdesi 10-100. Varsayılan 80. */
    fun opacity(c: Context) = int(c, "opacity", 80)
    fun setOpacity(c: Context, v: Int) = putInt(c, "opacity", v)

    /** false: Türkçe metin altyazının üstünde görünür (varsayılan). true: altyazıyı örter. */
    fun cover(c: Context) = sp(c).getBoolean("cover", false)
    fun setCover(c: Context, v: Boolean) = sp(c).edit().putBoolean("cover", v).apply()

    /** İki tarama arasındaki bekleme (ms). Büyüdükçe daha az işlem/ağ kullanılır. */
    fun delay(c: Context) = int(c, "delay", 300)
    fun setDelay(c: Context, v: Int) = putInt(c, "delay", v)

    fun engine(c: Context) = str(c, "engine", "google")
    fun setEngine(c: Context, v: String) = putStr(c, "engine", v)
}
