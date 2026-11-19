package com.tvceviri.app

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("tvceviri", Context.MODE_PRIVATE)

    /** Ekranın altından yüzde kaçı altyazı bölgesi olarak taranır. */
    fun region(c: Context) = sp(c).getInt("region", 30)
    fun setRegion(c: Context, v: Int) = sp(c).edit().putInt("region", v).apply()

    fun textSp(c: Context) = sp(c).getInt("textSp", 28)
    fun setTextSp(c: Context, v: Int) = sp(c).edit().putInt("textSp", v).apply()

    /** false: Türkçe metin altyazının üstünde görünür. true: altyazının üstünü örter. */
    /** true: Google online çeviri (daha kaliteli), başarısız olursa ML Kit'e düşer. */
    fun online(c: Context) = sp(c).getBoolean("online", true)
    fun setOnline(c: Context, v: Boolean) = sp(c).edit().putBoolean("online", v).apply()

    fun cover(c: Context) = sp(c).getBoolean("cover", false)
    fun setCover(c: Context, v: Boolean) = sp(c).edit().putBoolean("cover", v).apply()
}
