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
    /** Во сколько раз увеличивается текущая строка. */
    private val activeScale: Float = 1.05f,
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
    /** Текущая позиция автопрокрутки (дробная, чтобы медленная скорость не терялась при округлении). */
    private var scrollPos = 0f
    private var lastTickAt = 0L
    private var lastTrackPos: Long? = null
    private var lineViews: List<TextView> = emptyList()
    private var currentIdx = -1
    /** Строка, которая сейчас подсвечена на экране (может отличаться от [currentIdx] после recenter). */
    private var highlighted = -1
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
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(220).start()
        val l = NowPlaying.lyrics
        when {
            !NowPlaying.hasTrack -> showStatus("Сейчас ничего не играет")
            l == null -> showStatus(NowPlaying.status)
            l is Lyrics.Synced -> showSynced(l.lines)
            l is Lyrics.Plain -> {
                clear()
                plainShown = true
                lastTrackPos = null
                lastTickAt = 0
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
        if (idx != highlighted) {
            // Плавно гасим прошлую строку и разжигаем текущую.
            lineViews.getOrNull(highlighted)?.animate()
                ?.alpha(INACTIVE_ALPHA)?.scaleX(1f)?.scaleY(1f)?.setDuration(ANIM_MS)?.start()
            lineViews.getOrNull(idx)?.animate()
                ?.alpha(1f)?.scaleX(activeScale)?.scaleY(activeScale)?.setDuration(ANIM_MS)?.start()
            highlighted = idx
        }
        currentIdx = idx
        val v = lineViews.getOrNull(idx) ?: return
        if (SystemClock.uptimeMillis() - lastUserScroll > 3000) {
            view.smoothScrollTo(0, max(0, v.top - (view.height - v.height) / 2))
        }
    }

    /**
     * Текст без таймкодов: едет с постоянной скоростью, пока трек играет.
     * Базовая скорость — весь текст за длительность трека, множитель задаётся в настройках.
     * Прокрутил пальцем — продолжаем с того же места; перемотал трек — прыгаем на нужное место.
     */
    private fun autoScrollPlain() {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastTickAt == 0L) 0L else now - lastTickAt
        lastTickAt = now
        if (!NowPlaying.autoScroll) { lastTrackPos = null; return }
        val duration = NowPlaying.durationMs
        val maxScroll = linesBox.height - view.height
        val pos = NowPlaying.position()
        if (duration <= 0 || maxScroll <= 0 || pos == null) return

        // Пользователь листает сам — ждём и запоминаем, где он остановился.
        if (now - lastUserScroll < USER_PAUSE_MS) {
            scrollPos = view.scrollY.toFloat()
            lastTrackPos = pos
            return
        }

        val prev = lastTrackPos
        lastTrackPos = pos
        val expected = if (NowPlaying.isPlaying) dt else 0L
        if (prev == null || abs((pos - prev) - expected) > SEEK_THRESHOLD_MS) {
            // Первый тик или перемотка трека — встаём на место, соответствующее позиции.
            val fraction = ((pos + NowPlaying.offsetMs).toDouble() / duration).coerceIn(0.0, 1.0)
            scrollPos = (fraction * maxScroll).toFloat()
            view.smoothScrollTo(0, scrollPos.toInt())
            return
        }
        if (!NowPlaying.isPlaying || dt <= 0) return

        val pxPerMs = maxScroll.toFloat() / duration * NowPlaying.autoScrollSpeed
        scrollPos = (scrollPos + pxPerMs * dt).coerceIn(0f, maxScroll.toFloat())
        view.scrollTo(0, scrollPos.toInt())
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
            lineView(line.text, colorActive).also { v ->
                v.alpha = INACTIVE_ALPHA
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
        highlighted = -1
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
        // Слева выровненный текст увеличивается от левого края, а не от середины.
        if (!centered) pivotX = 0f
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(if (bold) 8 else 4), 0, dp(if (bold) 8 else 4))
    }

    companion object {
        private const val USER_PAUSE_MS = 1500L
        private const val ANIM_MS = 250L
        private const val INACTIVE_ALPHA = 0.45f
        private const val SEEK_THRESHOLD_MS = 2500L

        fun formatSpeed(speed: Float) = String.format(Locale.US, "%.1f×", speed)
        fun formatOffset(ms: Long) =
            if (ms == 0L) "0 с" else String.format(Locale.US, "%+.1f с", ms / 1000.0)

        /** Кнопка «автопрокрутка»: видна только для текста без таймкодов, жёлтая — когда включена. */
        fun autoScrollButton(ctx: Context, sizeDp: Int = 36) = iconButton(ctx, R.drawable.ic_autoscroll, sizeDp) {
            val on = !NowPlaying.autoScroll
            ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().putBoolean(Prefs.AUTO_SCROLL, on).apply()
            NowPlaying.setAutoScroll(on)
        }.also { styleAutoScroll(it) }

        fun styleAutoScroll(b: ImageView) {
            // Кнопка видна всегда, но работает только у текста без таймкодов:
            // у синхронизированного текста прокрутка и так идёт сама.
            val usable = NowPlaying.lyrics is Lyrics.Plain
            b.visibility = android.view.View.VISIBLE
            b.isEnabled = usable
            b.alpha = if (usable) 0.9f else 0.3f
            b.imageTintList = ColorStateList.valueOf(
                if (usable && NowPlaying.autoScroll) 0xFFFFCC00.toInt() else 0xFFFFFFFF.toInt()
            )
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
