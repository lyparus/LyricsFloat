package io.github.lyricsfloat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Общие цвета и элементы оформления экранов приложения. */
object Ui {
    const val BG = 0xFF101010.toInt()
    const val CARD = 0xFF1C1C1E.toInt()
    const val TILE = 0xFF2A2A2D.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_DIM = 0x99FFFFFF.toInt()
    const val ACCENT = 0xFFFFCC00.toInt()
    const val OK = 0xFF7BD88F.toInt()
    const val BAD = 0xFFFF6B6B.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(ctx, 1), it) }
    }

    fun text(ctx: Context, s: CharSequence, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    /** Карточка: скруглённый тёмный блок с необязательным заголовком. */
    fun card(ctx: Context, title: String? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, CARD, 20)
        val p = dp(ctx, 16)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 14) }
        title?.let { addView(text(ctx, it, 13f, TEXT_DIM, bold = true).apply { letterSpacing = 0.06f; isAllCaps = true }) }
    }

    /** Иконка в скруглённом квадрате. */
    fun iconTile(ctx: Context, icon: Int, sizeDp: Int = 40, tint: Int = ACCENT, bg: Int = TILE) = FrameLayout(ctx).apply {
        background = rounded(ctx, bg, sizeDp / 3)
        addView(ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
        }, FrameLayout.LayoutParams(dp(ctx, sizeDp / 2), dp(ctx, sizeDp / 2), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
    }

    /** Строка «иконка + заголовок + описание». */
    fun featureRow(ctx: Context, icon: Int, title: String, desc: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(ctx, 12), 0, 0)
        addView(iconTile(ctx, icon))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), 0, 0, 0)
            addView(text(ctx, title, 15f, bold = true))
            addView(text(ctx, desc, 13f, TEXT_DIM))
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** Цветная плашка статуса: «● Разрешено». */
    fun pill(ctx: Context) = TextView(ctx).apply {
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        val h = dp(ctx, 10)
        setPadding(h, dp(ctx, 4), h, dp(ctx, 4))
    }

    fun setPill(view: TextView, ok: Boolean, okText: String, badText: String) {
        val color = if (ok) OK else BAD
        view.text = if (ok) "● $okText" else "● $badText"
        view.setTextColor(color)
        view.background = rounded(view.context, (color and 0x00FFFFFF) or 0x22000000, 12)
    }

    fun primaryButton(ctx: Context, label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        stateListAnimator = null
        style(this, primary = true)
        setOnClickListener { onClick() }
    }

    /** Небольшая кнопка-ссылка внутри карточки. */
    fun linkButton(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        setTextColor(ACCENT)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(0, dp(ctx, 10), dp(ctx, 16), dp(ctx, 4))
        setOnClickListener { onClick() }
    }

    /** Жёлтая — главное действие, тёмная с обводкой — второстепенное. */
    fun style(b: Button, primary: Boolean) {
        b.background = if (primary) rounded(b.context, ACCENT, 16)
        else rounded(b.context, TILE, 16, strokeColor = 0x33FFFFFF)
        b.setTextColor(if (primary) 0xFF161616.toInt() else TEXT)
    }

    /** Поставить в ImageView размытую копию картинки (на старых Android — дешёвое размытие уменьшением). */
    fun setBlurred(view: android.widget.ImageView, bitmap: android.graphics.Bitmap, radius: Float) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                view.setImageBitmap(bitmap)
                view.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP))
            } else {
                val w = 24
                val h = maxOf(1, w * bitmap.height / maxOf(1, bitmap.width))
                view.setImageBitmap(android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true))
            }
        } catch (_: Exception) {
            view.setImageDrawable(null)
        }
    }

    fun spacer(ctx: Context, hDp: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(ctx, hDp)) }
}
