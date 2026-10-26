package io.github.lyricsfloat

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.view.animation.AccelerateDecelerateInterpolator
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max

/** Полноэкранный текст на фоне размытой обложки. Экран не гаснет, пока режим открыт. */
class FullscreenActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var panel: LyricsPanel
    private lateinit var backdrop: ImageView
    private lateinit var cover: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var shade: View
    private lateinit var settings: SettingsCard
    private lateinit var settingsButton: ImageView
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }
    private lateinit var seekBar: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeTotal: TextView
    private lateinit var seekRow: LinearLayout
    private lateinit var playButton: ImageView
    private var userSeeking = false
    /** Медленное «дыхание» обложки на фоне. */
    private var drift: ObjectAnimator? = null
    private lateinit var autoScrollButton: ImageView

    private val ticker = object : Runnable {
        override fun run() {
            panel.tick()
            updateProgress()
            handler.postDelayed(this, 200)
        }
    }

    private val stateListener: (NowPlaying.Change) -> Unit = { change ->
        when (change) {
            NowPlaying.Change.TRACK -> {
                showTrackInfo(); showArt(NowPlaying.art); panel.render(); updatePlayback()
                settings.refresh()
                LyricsPanel.styleAutoScroll(autoScrollButton)
            }
            NowPlaying.Change.PLAYBACK -> updatePlayback()
            NowPlaying.Change.AUTO_SCROLL -> { LyricsPanel.styleAutoScroll(autoScrollButton); settings.refresh() }
            NowPlaying.Change.LYRICS -> { panel.render(); LyricsPanel.styleAutoScroll(autoScrollButton); settings.refresh() }
            NowPlaying.Change.ART -> showArt(NowPlaying.art)
            NowPlaying.Change.OFFSET -> {
                settings.refresh()
                panel.recenter()
            }
            NowPlaying.Change.FULLSCREEN -> {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        panel = LyricsPanel(this, Prefs.FONT_FULL, 26f, centered = true, bold = true, edgePaddingDp = 220, activeScale = 1.08f)

        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            scaleX = 1.15f
            scaleY = 1.15f
        }
        shade = View(this)
        applyDim(prefs.getInt(Prefs.FULL_DIM, 60))

        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
            }
            clipToOutline = true
        }
        titleView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        artistView = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(titleView)
            addView(artistView)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cover, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(icon(R.drawable.ic_search) { Overlay.openGenius(this@FullscreenActivity) })
            addView(icon(R.drawable.ic_close) { finish() })
        }

        autoScrollButton = LyricsPanel.autoScrollButton(this, sizeDp = 44)
        settingsButton = icon(R.drawable.ic_settings) { toggleSettings() }
        settings = SettingsCard(
            this, panel, fontStep = 2f,
            sliderLabel = "Затемнение обложки",
            sliderValue = prefs.getInt(Prefs.FULL_DIM, 60),
            onSlider = { prefs.edit().putInt(Prefs.FULL_DIM, it).apply(); applyDim(it) },
            onClose = { toggleSettings() },
        ).also { it.visible = false }

        // Полоса прогресса: текущее время | перемотка | длительность.
        timeNow = timeLabel()
        timeTotal = timeLabel()
        seekBar = SeekBar(this).apply {
            val white = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            progressTintList = white
            thumbTintList = white
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) timeNow.text = formatTime(value.toLong())
                }
                override fun onStartTrackingTouch(bar: SeekBar) { userSeeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    userSeeking = false
                    NowPlaying.controls?.seekTo(bar.progress.toLong())
                    panel.recenter()
                }
            })
        }
        seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(timeNow)
            addView(seekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(timeTotal)
        }

        // Кнопки плеера.
        playButton = LyricsPanel.iconButton(this, R.drawable.ic_play, sizeDp = 72) {
            NowPlaying.controls?.playPause()
        }.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0x66FFFFFF)
            }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val transportButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(LyricsPanel.iconButton(this@FullscreenActivity, R.drawable.ic_prev, sizeDp = 56) {
                NowPlaying.controls?.previous()
            })
            addView(View(this@FullscreenActivity), LinearLayout.LayoutParams(dp(28), 1))
            addView(playButton)
            addView(View(this@FullscreenActivity), LinearLayout.LayoutParams(dp(28), 1))
            addView(LyricsPanel.iconButton(this@FullscreenActivity, R.drawable.ic_next, sizeDp = 56) {
                NowPlaying.controls?.next()
            })
        }
        // Плеер по центру, настройки слева, автопрокрутка справа — центр не съезжает.
        val transport = FrameLayout(this).apply {
            setPadding(0, dp(4), 0, dp(8))
            addView(transportButtons, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(settingsButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(autoScrollButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(panel.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(seekRow)
            addView(transport)
        }
        content.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(
                dp(20) + insets.systemWindowInsetLeft, dp(16) + insets.systemWindowInsetTop,
                dp(12) + insets.systemWindowInsetRight, dp(12) + insets.systemWindowInsetBottom,
            )
            insets
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF101010.toInt())
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            addView(shade, FrameLayout.LayoutParams(-1, -1))
            addView(content, FrameLayout.LayoutParams(-1, -1))
            addView(settings.view, FrameLayout.LayoutParams(dp(320), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(190)
            })
        }
        setContentView(root)
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        // Если сервис отвалился (например, после обновления), просим систему подключить его снова.
        runCatching {
            NotificationListenerService.requestRebind(ComponentName(this, MediaListener::class.java))
        }
        MediaWatcher.start(this)
        NowPlaying.addListener(stateListener)
        NowPlaying.setFullscreen(true)
        stateListener(NowPlaying.Change.TRACK)
        settings.refresh()
        panel.recenter()
        handler.post(ticker)
    }

    override fun onStop() {
        stopDrift()
        handler.removeCallbacks(ticker)
        NowPlaying.removeListener(stateListener)
        NowPlaying.setFullscreen(false)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun toggleSettings() {
        settings.visible = !settings.visible
        settings.refresh()
        settingsButton.imageTintList = ColorStateList.valueOf(if (settings.visible) 0xFFFFCC00.toInt() else 0xFFFFFFFF.toInt())
    }

    /** Затемнение поверх обложки, 0–100 %: по краям чуть темнее, чтобы читались шапка и кнопки. */
    private fun applyDim(percent: Int) {
        val mid = (percent.coerceIn(0, 100) * 255 / 100)
        val edge = minOf(255, mid + 60)
        shade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(edge shl 24, mid shl 24, edge shl 24),
        )
    }

    private fun updatePlayback() {
        playButton.setImageResource(if (NowPlaying.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        val duration = NowPlaying.durationMs
        seekRow.visibility = if (duration > 0) View.VISIBLE else View.INVISIBLE
        seekBar.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        timeTotal.text = formatTime(duration)
        updateProgress()
    }

    private fun updateProgress() {
        if (userSeeking || NowPlaying.durationMs <= 0) return
        val pos = (NowPlaying.position() ?: return).coerceIn(0, NowPlaying.durationMs)
        seekBar.progress = pos.toInt()
        timeNow.text = formatTime(pos)
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun timeLabel() = TextView(this).apply {
        setTextColor(0xB3FFFFFF.toInt())
        textSize = 12f
        gravity = Gravity.CENTER
        minWidth = dp(44)
    }

    private fun showTrackInfo() {
        titleView.text = if (NowPlaying.hasTrack) NowPlaying.title else "Ничего не играет"
        artistView.text = NowPlaying.artist
        artistView.visibility = if (NowPlaying.artist.isBlank()) View.GONE else View.VISIBLE
    }

    private fun showArt(art: Bitmap?) {
        cover.visibility = if (art == null) View.GONE else View.VISIBLE
        cover.setImageBitmap(art)
        if (art == null) {
            stopDrift()
            backdrop.setImageDrawable(null)
            return
        }
        startDrift()
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                backdrop.setImageBitmap(art)
                backdrop.setRenderEffect(RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP))
            } else {
                // Дешёвое размытие: сильно уменьшаем, а ImageView растянет с фильтрацией.
                val w = 24
                val h = max(1, w * art.height / max(1, art.width))
                backdrop.setImageBitmap(Bitmap.createScaledBitmap(art, w, h, true))
            }
        } catch (_: Exception) {
            backdrop.setImageDrawable(null)
        }
    }

    private fun startDrift() {
        if (drift?.isStarted == true) return
        val shift = dp(18).toFloat()
        drift = ObjectAnimator.ofPropertyValuesHolder(
            backdrop,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.15f, 1.3f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.15f, 1.3f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -shift, shift),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, shift / 2, -shift / 2),
        ).apply {
            duration = 22_000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopDrift() {
        drift?.cancel()
        drift = null
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun icon(res: Int, onClick: () -> Unit) = LyricsPanel.iconButton(this, res, sizeDp = 44, onClick = onClick)
}
