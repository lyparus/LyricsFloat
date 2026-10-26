package io.github.lyricsfloat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Маленькая панель настроек вида: шрифт, сдвиг текста и прозрачность/затемнение.
 * Показывается рядом с текстом, чтобы изменения были видны сразу.
 */
class SettingsCard(
    private val ctx: Context,
    private val panel: LyricsPanel,
    private val fontStep: Float,
    sliderLabel: String,
    sliderValue: Int,
    private val onSlider: (Int) -> Unit,
    private val onClose: () -> Unit,
) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val colorText = 0xFFFFFFFF.toInt()
    private val colorDim = 0x99FFFFFF.toInt()

    private val fontValue = valueLabel()
    private val offsetValue = valueLabel().apply { setOnClickListener { NowPlaying.setOffset(0) } }

    val view = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xF2242424.toInt())
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0x22FFFFFF)
        }
        setPadding(dp(14), dp(4), dp(6), dp(12))
        elevation = dp(8).toFloat()

        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                text = "Вид текста"
                setTextColor(colorText)
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(LyricsPanel.iconButton(ctx, R.drawable.ic_close) { onClose() })
        })

        addView(row("Размер шрифта", R.drawable.ic_text_smaller, fontValue, R.drawable.ic_text_larger,
            onMinus = { panel.changeFont(-fontStep); refresh() },
            onPlus = { panel.changeFont(+fontStep); refresh() }))

        addView(row("Сдвиг текста", R.drawable.ic_earlier, offsetValue, R.drawable.ic_later,
            onMinus = { NowPlaying.setOffset(NowPlaying.offsetMs - 500) },
            onPlus = { NowPlaying.setOffset(NowPlaying.offsetMs + 500) }))
        addView(hint("«Раньше» — если текст отстаёт от голоса. Тап по значению — сброс."))

        addView(label(sliderLabel).apply { setPadding(0, dp(10), 0, 0) })
        addView(SeekBar(ctx).apply {
            max = 100
            progress = sliderValue
            val white = ColorStateList.valueOf(colorText)
            progressTintList = white
            thumbTintList = white
            progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) onSlider(value)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
    }

    init {
        refresh()
    }

    /** Обновить значения (после смены трека или сдвига из другого места). */
    fun refresh() {
        fontValue.text = panel.fontSize.toInt().toString()
        offsetValue.text = LyricsPanel.formatOffset(NowPlaying.offsetMs)
    }

    var visible: Boolean
        get() = view.visibility == View.VISIBLE
        set(v) { view.visibility = if (v) View.VISIBLE else View.GONE }

    private fun row(title: String, minus: Int, value: TextView, plus: Int, onMinus: () -> Unit, onPlus: () -> Unit) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(label(title), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(LyricsPanel.iconButton(ctx, minus) { onMinus() })
            addView(value)
            addView(LyricsPanel.iconButton(ctx, plus) { onPlus() })
        }

    private fun label(s: String) = TextView(ctx).apply {
        text = s
        setTextColor(colorText)
        textSize = 14f
    }

    private fun hint(s: String) = TextView(ctx).apply {
        text = s
        setTextColor(colorDim)
        textSize = 11f
        setPadding(0, 0, dp(8), 0)
    }

    private fun valueLabel() = TextView(ctx).apply {
        setTextColor(colorText)
        textSize = 14f
        gravity = Gravity.CENTER
        minWidth = dp(52)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }
}
