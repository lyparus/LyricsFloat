package io.github.lyricsfloat

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Плавающее окно с текстом поверх всех приложений.
 * Можно свернуть до язычка у края экрана, менять размер и шрифт.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class Overlay(private val ctx: Context, private val onClose: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private fun dp(v: Int) = (v * density).toInt()
    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    private val colorAccent = 0xFFFFCC00.toInt()
    /** Непрозрачность фона окна в процентах (настраивается). */
    private var opacity = prefs.getInt(Prefs.OVERLAY_OPACITY, 90)
    /** Акцент из обложки текущего трека; null — обложки нет или она серая. */
    private var coverAccent: Int? = null
    private val accent get() = coverAccent ?: colorAccent
    private val colorBg: Int
        get() {
            val base = coverAccent?.let { CoverColors.darkTint(it) and 0xFFFFFF } ?: 0x161616
            return ((opacity.coerceIn(20, 100) * 255 / 100) shl 24) or base
        }

    private val minWidth get() = dp(200)
    private val minHeight get() = dp(48)

    /** Окно должно быть на экране (идёт трек и показ включён). */
    private var shown = false
    /** Окно спрятано в язычок у края экрана. */
    private var minimized = false
    /** Свёрнуто до заголовка. */
    private var collapsed = false
    private val attachedViews = mutableSetOf<View>()

    private val panel = LyricsPanel(ctx, Prefs.FONT, 17f, centered = false, bold = false, edgePaddingDp = 60)

    private val titleView = TextView(ctx).apply {
        setTextColor(colorAccent)
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    private fun icon(res: Int, onClick: () -> Unit) = LyricsPanel.iconButton(ctx, res, onClick = onClick)

    private val collapseButton = icon(R.drawable.ic_chevron_down) { toggleCollapse() }
    private val resizeHandle = icon(R.drawable.ic_resize) {}

    private val autoScrollButton = LyricsPanel.autoScrollButton(ctx)
    private val settingsButton = icon(R.drawable.ic_settings) { toggleSettings() }

    private val bottomRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(settingsButton)
        addView(autoScrollButton)
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        addView(resizeHandle)
    }

    private val rootBackground = GradientDrawable().apply {
        setColor(colorBg)
        cornerRadius = dp(16).toFloat()
    }

    /** Настройки вида — отдельное маленькое окно рядом с окном текста. */
    private var settingsOpen = false
    private val settings = SettingsCard(
        ctx, panel, fontStep = 1f,
        sliderLabel = "Непрозрачность фона окна",
        sliderValue = opacity,
        onSlider = { setOpacity(it) },
        onClose = { toggleSettings() },
        toggle = Triple("Обложка на фоне", prefs.getBoolean(Prefs.OVERLAY_COVER_BG, true)) { on ->
            prefs.edit().putBoolean(Prefs.OVERLAY_COVER_BG, on).apply()
            applyArt()
        },
    )
    private val settingsParams = overlayParams(dp(300))

    /** Содержимое окна: шапка, текст, нижняя панель. */
    private val content = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(2), dp(4), 0)
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(icon(R.drawable.ic_search) { openGenius(ctx) })
            addView(icon(R.drawable.ic_fullscreen) { openFullscreen() })
            addView(collapseButton)
            addView(icon(R.drawable.ic_dock) { minimize() })
            addView(icon(R.drawable.ic_close) { onClose() })
        }
        addView(header)
        addView(panel.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            prefs.getInt(Prefs.HEIGHT, dp(200)).coerceIn(minHeight, (screenH * 0.8).toInt()),
        ))
        addView(bottomRow)
    }

    /** Размытая обложка под содержимым. */
    private val coverBg = FillView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
    }

    /** Окно целиком: обложка → полупрозрачный тёмный слой → содержимое, со скруглёнными углами. */
    private val root = android.widget.FrameLayout(ctx).apply {
        addView(coverBg, android.widget.FrameLayout.LayoutParams(-1, -1))
        addView(FillView(ctx).apply { background = rootBackground }, android.widget.FrameLayout.LayoutParams(-1, -1))
        addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
        }
        clipToOutline = true
    }

    private val params = overlayParams(
        prefs.getInt(Prefs.WIDTH, min(screenW - dp(24), dp(420))).coerceIn(minWidth, screenW)
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt(Prefs.POS_X, dp(12))
        y = prefs.getInt(Prefs.POS_Y, dp(120))
    }

    /** Язычок у края экрана, в который прячется окно. */
    private val tab = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_note)
        imageTintList = ColorStateList.valueOf(colorAccent)
        layoutParams = ViewGroup.LayoutParams(dp(40), dp(56))
        setPadding(dp(9), dp(17), dp(9), dp(17))
    }

    private val tabParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT).apply {
        y = prefs.getInt(Prefs.TAB_Y, dp(200))
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!collapsed && !minimized) panel.tick()
            handler.postDelayed(this, 200)
        }
    }

    private val stateListener: (NowPlaying.Change) -> Unit = { change ->
        when (change) {
            NowPlaying.Change.TRACK -> {
                titleView.text = with(NowPlaying) { if (artist.isBlank()) title else "$artist — $title" }
                settings.refresh()
                panel.render()
                LyricsPanel.styleAutoScroll(autoScrollButton)
            }
            NowPlaying.Change.LYRICS -> {
                panel.render(); LyricsPanel.styleAutoScroll(autoScrollButton); settings.refresh()
                if (settingsOpen) { placeSettings(); relayout(settings.view, settingsParams) }
            }
            NowPlaying.Change.AUTO_SCROLL -> { LyricsPanel.styleAutoScroll(autoScrollButton); settings.refresh() }
            NowPlaying.Change.OFFSET -> {
                settings.refresh()
                panel.recenter()
            }
            NowPlaying.Change.FULLSCREEN -> syncWindows()
            NowPlaying.Change.ART -> applyArt()
            NowPlaying.Change.PLAYBACK -> {}
        }
    }

    init {
        NowPlaying.addListener(stateListener)
        stateListener(NowPlaying.Change.TRACK)
        applyArt()

        // Перетаскивание окна за заголовок.
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        titleView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    relayout(root, params)
                    if (settingsOpen) { placeSettings(); relayout(settings.view, settingsParams) }
                }
                MotionEvent.ACTION_UP ->
                    prefs.edit().putInt(Prefs.POS_X, params.x).putInt(Prefs.POS_Y, params.y).apply()
            }
            true
        }

        // Изменение размера за уголок.
        var startW = 0; var startH = 0; var resizeX = 0f; var resizeY = 0f
        resizeHandle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width; startH = panel.view.layoutParams.height
                    resizeX = e.rawX; resizeY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + (e.rawX - resizeX)).toInt().coerceIn(minWidth, screenW)
                    if (!collapsed) {
                        val h = (startH + (e.rawY - resizeY)).toInt().coerceIn(minHeight, (screenH * 0.8).toInt())
                        panel.view.layoutParams = panel.view.layoutParams.apply { height = h }
                    }
                    relayout(root, params)
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit()
                        .putInt(Prefs.WIDTH, params.width)
                        .putInt(Prefs.HEIGHT, panel.view.layoutParams.height)
                        .apply()
                    panel.recenter()
                }
            }
            true
        }

        // Язычок: тап — раскрыть окно, перетаскивание — вверх/вниз вдоль края.
        var tabStartY = 0; var tabTouchY = 0f; var moved = false
        tab.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { tabStartY = tabParams.y; tabTouchY = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - tabTouchY
                    if (abs(dy) > touchSlop) moved = true
                    if (moved) {
                        tabParams.y = (tabStartY + dy).toInt().coerceIn(0, screenH - dp(60))
                        relayout(tab, tabParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt(Prefs.TAB_Y, tabParams.y).apply() else restore()
                }
            }
            true
        }
    }

    fun show() {
        shown = true
        syncWindows()
    }

    fun hide() {
        shown = false
        syncWindows()
    }

    /** Окончательно убрать окно (сервис остановлен). */
    fun destroy() {
        hide()
        NowPlaying.removeListener(stateListener)
    }

    /** Применить настройку «не гасить экран». */
    fun applyKeepScreenOn() {
        val on = prefs.getBoolean(Prefs.KEEP_SCREEN_ON, true)
        for ((v, lp) in listOf(root to params, tab to tabParams)) {
            lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            else lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            relayout(v, lp)
        }
    }

    private fun toggleSettings() {
        settingsOpen = !settingsOpen
        settingsButton.imageTintList = ColorStateList.valueOf(if (settingsOpen) colorAccent else 0xFFFFFFFF.toInt())
        if (settingsOpen) placeSettings()
        syncWindows()
    }

    /** Над окном текста, а если сверху не помещается — под ним. */
    private fun placeSettings() {
        settingsParams.width = min(dp(300), screenW - dp(16))
        settings.view.measure(
            View.MeasureSpec.makeMeasureSpec(settingsParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val h = settings.view.measuredHeight
        val gap = dp(8)
        settingsParams.gravity = Gravity.TOP or Gravity.START
        settingsParams.x = params.x.coerceIn(0, max(0, screenW - settingsParams.width))
        settingsParams.y = if (params.y - h - gap >= dp(24)) params.y - h - gap
        else min(params.y + root.height + gap, screenH - h - gap)
    }

    /** Обложка фоном и цвета из неё. */
    private fun applyArt() {
        val art = NowPlaying.art
        coverAccent = CoverColors.accent(art)
        if (art != null && prefs.getBoolean(Prefs.OVERLAY_COVER_BG, true)) {
            Ui.setBlurred(coverBg, art, 60f)
            coverBg.visibility = View.VISIBLE
        } else {
            coverBg.setImageDrawable(null)
            coverBg.visibility = View.GONE
        }
        titleView.setTextColor(accent)
        tab.imageTintList = ColorStateList.valueOf(accent)
        rootBackground.setColor(colorBg)
        (tab.background as? GradientDrawable)?.setColor(colorBg)
    }

    private fun setOpacity(percent: Int) {
        opacity = percent.coerceIn(20, 100)
        prefs.edit().putInt(Prefs.OVERLAY_OPACITY, opacity).apply()
        rootBackground.setColor(colorBg)
        (tab.background as? GradientDrawable)?.setColor(colorBg)
    }

    private fun minimize() {
        // Язычок появляется у того края, к которому окно ближе.
        val left = params.x + params.width / 2 < screenW / 2
        tabParams.gravity = Gravity.TOP or (if (left) Gravity.START else Gravity.END)
        tab.imageTintList = ColorStateList.valueOf(accent)
        val r = dp(14).toFloat()
        tab.background = GradientDrawable().apply {
            setColor(colorBg)
            cornerRadii = if (left) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        minimized = true
        syncWindows()
    }

    private fun restore() {
        minimized = false
        panel.recenter()
        syncWindows()
    }

    /** Показывает либо окно, либо язычок, либо ничего. Во время полноэкранного режима окно не нужно. */
    private fun syncWindows() {
        val visible = shown && !NowPlaying.fullscreenOpen
        applyKeepScreenOn()
        setAttached(root, params, visible && !minimized)
        setAttached(tab, tabParams, visible && minimized)
        setAttached(settings.view, settingsParams, visible && !minimized && settingsOpen)
        handler.removeCallbacks(ticker)
        if (visible) handler.post(ticker)
    }

    private fun setAttached(v: View, lp: WindowManager.LayoutParams, want: Boolean) {
        if (want && v !in attachedViews) {
            // Мягкое появление: из прозрачного и чуть уменьшенного.
            v.alpha = 0f
            v.scaleX = 0.96f
            v.scaleY = 0.96f
            wm.addView(v, lp)
            attachedViews += v
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        } else if (!want && v in attachedViews) {
            wm.removeView(v)
            attachedViews -= v
        }
    }

    private fun relayout(v: View, lp: WindowManager.LayoutParams) {
        if (v in attachedViews) wm.updateViewLayout(v, lp)
    }

    private fun overlayParams(width: Int) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    )

    private fun toggleCollapse() {
        collapsed = !collapsed
        panel.view.visibility = if (collapsed) View.GONE else View.VISIBLE
        bottomRow.visibility = panel.view.visibility
        collapseButton.setImageResource(if (collapsed) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)
        if (!collapsed) panel.recenter()
    }

    private fun openFullscreen() {
        ctx.startActivity(Intent(ctx, FullscreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        fun openGenius(ctx: Context) {
            val q = URLEncoder.encode("${NowPlaying.artist} ${NowPlaying.title}".trim(), "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://genius.com/search?q=$q"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}

/**
 * Слой фона окна: растягивается под размер окна, но сам на этот размер не влияет.
 * Обычный View в окне «по содержимому» просит всю высоту экрана — и окно становится на весь экран.
 */
private class FillView(ctx: Context) : ImageView(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        fun size(spec: Int) =
            if (View.MeasureSpec.getMode(spec) == View.MeasureSpec.EXACTLY) View.MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(widthSpec), size(heightSpec))
    }
}
