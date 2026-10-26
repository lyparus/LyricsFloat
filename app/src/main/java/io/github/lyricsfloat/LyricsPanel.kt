package io.github.lyricsfloat

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Прокручиваемый список строк с подсветкой текущей. Используется в окне и в полноэкранном режиме. */
@SuppressLint("ClickableViewAccessibility")
class LyricsPanel(
    private val ctx: Context,
    private val fontPrefKey: String,
    defaultFont: Float,
    private val centered: Boolean,
    private val bold: Boolean,
    edgePaddingDp: Int,
) {
    private val prefs = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val colorDim = 0x80FFFFFF.toInt()
    private val colorActive = 0xFFFFFFFF.toInt()

    var fontSize = prefs.getFloat(fontPrefKey, defaultFont); private set
    private var lines: List<LyricLine> = emptyList()
    /** Показан текст без таймкодов — его можно прокручивать автоматически. */
    private var plainShown = false
    private var lineViews: List<TextView> = emptyList()
    private var currentIdx = -1
    private var lastUserScroll = 0L

    private val linesBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(edgePaddingDp), dp(4), dp(edgePaddingDp))
    }

    val view = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(32))
        addView(linesBox)
        setOnTouchListener { _, _ -> lastUserScroll = SystemClock.uptimeMillis(); false }
    }

    /** Перерисовать по текущему состоянию [NowPlaying]. */
    fun render() {
        val l = NowPlaying.lyrics
        when {
            !NowPlaying.hasTrack -> showStatus("Сейчас ничего не играет")
            l == null -> showStatus(NowPlaying.status)
            l is Lyrics.Synced -> showSynced(l.lines)
            l is Lyrics.Plain -> {
                clear()
                plainShown = true
                linesBox.addView(lineView(l.text, colorActive))
                view.scrollTo(0, 0)
            }
            l is Lyrics.Instrumental -> showStatus("Инструментал")
            l is Lyrics.NotFound -> showStatus("Текст не найден.\nНажмите на лупу, чтобы поискать в Genius.")
            l is Lyrics.Error -> showStatus("Ошибка: ${l.message}.\nПовторю сам через 15 с или нажмите сюда.") {
                NowPlaying.retry()
            }
        }
    }

    /** Подсветить строку, которая звучит сейчас; вызывать по таймеру. */
    fun tick() {
        if (plainShown) { autoScrollPlain(); return }
        if (lines.isEmpty()) return
        val pos = NowPlaying.position() ?: return
        // Небольшое опережение, чтобы строка загоралась чуть раньше голоса.
        val idx = lines.indexOfLast { it.timeMs <= pos + 250 + NowPlaying.offsetMs }
        if (idx == currentIdx) return
        lineViews.forEachIndexed { i, v -> v.setTextColor(if (i == idx) colorActive else colorDim) }
        currentIdx = idx
        val v = lineViews.getOrNull(idx) ?: return
        if (SystemClock.uptimeMillis() - lastUserScroll > 3000) {
            view.smoothScrollTo(0, max(0, v.top - (view.height - v.height) / 2))
        }
    }

    /**
     * Текст без таймкодов: прокрутка пропорционально прогрессу трека,
     * так что пауза и перемотка учитываются сами. Сдвиг кнопками тоже работает.
     */
    private fun autoScrollPlain() {
        if (!NowPlaying.autoScroll) return
        val duration = NowPlaying.durationMs
        if (duration <= 0) return
        if (SystemClock.uptimeMillis() - lastUserScroll < 3000) return
        val pos = NowPlaying.position() ?: return
        val maxScroll = linesBox.height - view.height
        if (maxScroll <= 0) return
        val fraction = ((pos + NowPlaying.offsetMs).toDouble() / duration).coerceIn(0.0, 1.0)
        val target = (fraction * maxScroll).toInt()
        // Обычное движение — по чуть-чуть, а после перемотки — плавным прыжком.
        if (abs(target - view.scrollY) > view.height / 2) view.smoothScrollTo(0, target) else view.scrollTo(0, target)
    }

    /** Заставить следующий [tick] заново выставить текущую строку и прокрутку. */
    fun recenter() {
        currentIdx = -2
        lastUserScroll = 0
    }

    fun changeFont(delta: Float) {
        fontSize = (fontSize + delta).coerceIn(12f, 44f)
        prefs.edit().putFloat(fontPrefKey, fontSize).apply()
        for (i in 0 until linesBox.childCount) (linesBox.getChildAt(i) as? TextView)?.textSize = fontSize
        recenter()
    }

    private fun showSynced(newLines: List<LyricLine>) {
        clear()
        lines = newLines
        lineViews = newLines.map { line ->
            lineView(line.text, colorDim).also { v ->
                // Тап по строке — перемотать трек на неё.
                v.setOnClickListener {
                    NowPlaying.controls?.seekTo(max(0L, line.timeMs - NowPlaying.offsetMs))
                    recenter()
                }
                rippleBackground(v)
                linesBox.addView(v)
            }
        }
        view.scrollTo(0, 0)
    }

    private fun showStatus(text: String, onClick: (() -> Unit)? = null) {
        clear()
        val v = lineView(text, colorDim)
        v.gravity = Gravity.CENTER
        v.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        v.textSize = minOf(fontSize, 18f)
        onClick?.let { cb -> v.setOnClickListener { cb() } }
        linesBox.addView(v)
        view.scrollTo(0, 0)
    }

    private fun rippleBackground(v: TextView) {
        runCatching {
            val tv = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                v.setBackgroundResource(tv.resourceId)
            }
        }
    }

    private fun clear() {
        plainShown = false
        linesBox.removeAllViews()
        lines = emptyList()
        lineViews = emptyList()
        currentIdx = -1
    }

    private fun lineView(text: String, color: Int) = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        textSize = fontSize
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        if (centered) gravity = Gravity.CENTER_HORIZONTAL
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(if (bold) 8 else 4), 0, dp(if (bold) 8 else 4))
    }

    companion object {
        fun formatOffset(ms: Long) =
            if (ms == 0L) "0 с" else String.format(Locale.US, "%+.1f с", ms / 1000.0)

        /** Кнопка «автопрокрутка»: видна только для текста без таймкодов, жёлтая — когда включена. */
        fun autoScrollButton(ctx: Context, sizeDp: Int = 36) = iconButton(ctx, R.drawable.ic_autoscroll, sizeDp) {
            val on = !NowPlaying.autoScroll
            ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().putBoolean(Prefs.AUTO_SCROLL, on).apply()
            NowPlaying.setAutoScroll(on)
        }.also { styleAutoScroll(it) }

        fun styleAutoScroll(b: ImageView) {
            b.visibility = if (NowPlaying.lyrics is Lyrics.Plain) android.view.View.VISIBLE else android.view.View.GONE
            b.imageTintList = ColorStateList.valueOf(if (NowPlaying.autoScroll) 0xFFFFCC00.toInt() else 0x99FFFFFF.toInt())
        }

        /** Кнопка-иконка в общем стиле приложения. */
        fun iconButton(ctx: Context, icon: Int, sizeDp: Int = 36, onClick: () -> Unit) = ImageView(ctx).apply {
            val d = ctx.resources.displayMetrics.density
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            alpha = 0.9f
            layoutParams = LinearLayout.LayoutParams((sizeDp * d).toInt(), (sizeDp * d).toInt())
            val pad = (sizeDp * d / 4.5f).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }
    }
}
