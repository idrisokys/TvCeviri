package com.tvceviri.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil

object Fonts {
    private var tf: Typeface? = null
    fun subtitle(c: Context): Typeface {
        tf?.let { return it }
        val t = try {
            Typeface.createFromAsset(c.assets, "fonts/ProductSans-Medium.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT_BOLD
        }
        tf = t
        return t
    }
}

/** Kenarlıklı, saydamlık ayarlı altyazı görünümü. Hem overlay hem önizleme bunu kullanır. */
class SubtitleView(ctx: Context) : View(ctx) {

    var maxW = 0
    private var text = ""
    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var fillLayout: StaticLayout? = null
    private var strokeLayout: StaticLayout? = null
    private var padX = 0
    private var padY = 0

    fun setSubtitle(t: String) {
        text = t
        requestLayout()
        invalidate()
    }

    /** Ayarları (Prefs) okuyup uygular. */
    fun update() {
        val c = context
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, Prefs.textSp(c).toFloat(), resources.displayMetrics
        )
        val tf = Fonts.subtitle(c)
        val op = Prefs.opacity(c) / 100f
        val cover = Prefs.cover(c)

        fill.typeface = tf
        fill.textSize = sizePx
        fill.style = Paint.Style.FILL
        fill.color = Prefs.color(c)

        stroke.typeface = tf
        stroke.textSize = sizePx
        stroke.style = Paint.Style.STROKE
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.color = Color.BLACK
        stroke.strokeWidth = sizePx * 0.5f * Prefs.outline(c) / 100f

        if (cover) {
            // Arka plan opak kalsın; saydamlık sadece yazıya uygulanır.
            fill.alpha = (op * 255).toInt()
            stroke.alpha = (op * 255).toInt()
            alpha = 1f
            setBackgroundColor(0xF2000000.toInt())
        } else {
            fill.alpha = 255
            stroke.alpha = 255
            alpha = op
            setBackgroundColor(Color.TRANSPARENT)
        }
        padX = (sizePx * 0.4f + stroke.strokeWidth).toInt()
        padY = (sizePx * 0.15f + stroke.strokeWidth).toInt()
        requestLayout()
        invalidate()
    }

    private fun build(s: String, p: TextPaint, w: Int): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val specW = MeasureSpec.getSize(wSpec)
        var limit = if (maxW > 0) minOf(maxW, if (specW > 0) specW else maxW) else specW
        if (limit <= 0) limit = 1000
        val avail = limit - 2 * padX
        val s = text.ifEmpty { " " }
        val desired = ceil(StaticLayout.getDesiredWidth(s, fill)).toInt()
        val tw = desired.coerceAtMost(avail).coerceAtLeast(1)
        val fl = build(s, fill, tw)
        fillLayout = fl
        strokeLayout = build(s, stroke, tw)
        setMeasuredDimension(tw + 2 * padX, fl.height + 2 * padY)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(padX.toFloat(), padY.toFloat())
        if (stroke.strokeWidth > 0.5f) strokeLayout?.draw(canvas)
        fillLayout?.draw(canvas)
        canvas.restore()
    }
}
