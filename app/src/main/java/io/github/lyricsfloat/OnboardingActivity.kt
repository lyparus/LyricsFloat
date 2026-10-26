package io.github.lyricsfloat

import android.app.Activity
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Короткая обучалка по основным функциям. Показывается при первом запуске и по кнопке «Как пользоваться». */
class OnboardingActivity : Activity() {

    private class Page(val icon: Int, val title: String, val text: String, val tips: List<Triple<Int, String, String>>)

    private val pages = listOf(
        Page(R.drawable.ic_note, "Текст песни",
            "Показывает текст трека, который играет в Яндекс Музыке, YouTube Music или YouTube — " +
                "поверх любых приложений. Строки подсвечиваются в такт музыке.",
            emptyList()),
        Page(R.drawable.ic_dock, "Плавающее окно",
            "Окно с текстом висит поверх всего. Его можно подстроить под себя.",
            listOf(
                Triple(R.drawable.ic_note, "Переместить", "Тяните за название трека в шапке"),
                Triple(R.drawable.ic_resize, "Изменить размер", "Тяните за уголок справа внизу"),
                Triple(R.drawable.ic_dock, "Спрятать к краю", "Останется язычок — тап, чтобы вернуть"),
            )),
        Page(R.drawable.ic_play, "Текст в такт",
            "Если у текста есть таймкоды, текущая строка подсвечивается сама.",
            listOf(
                Triple(R.drawable.ic_play, "Тап по строке", "Перематывает трек на неё"),
                Triple(R.drawable.ic_settings, "Настройки вида", "Шрифт, сдвиг текста и прозрачность — видно сразу"),
                Triple(R.drawable.ic_autoscroll, "Автопрокрутка", "Для текстов без таймкодов"),
            )),
        Page(R.drawable.ic_fullscreen, "Полный экран",
            "Крупный текст на фоне обложки — удобно, когда телефон лежит рядом.",
            listOf(
                Triple(R.drawable.ic_play, "Управление плеером", "Пауза, следующий трек, перемотка"),
                Triple(R.drawable.ic_fullscreen, "Экран не гаснет", "Пока открыт полный экран"),
                Triple(R.drawable.ic_search, "Нет текста?", "Лупа откроет поиск в Genius"),
            )),
        Page(R.drawable.ic_settings, "Осталось разрешить",
            "На главном экране, в разделе «Разрешения»:",
            listOf(
                Triple(R.drawable.ic_note, "Доступ к уведомлениям", "Чтобы видеть, какой трек играет"),
                Triple(R.drawable.ic_dock, "Поверх других приложений", "Для плавающего окна"),
                Triple(R.drawable.ic_settings, "Работа в фоне", "Чтобы окно не пропадало (важно на vivo, Xiaomi)"),
            )),
    )

    private var index = 0
    private lateinit var pageBox: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var nextButton: Button
    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pageBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        nextButton = Ui.primaryButton(this, "Далее") {
            if (index < pages.lastIndex) show(index + 1, forward = true) else finishOnboarding()
        }
        val skip = Ui.text(this, "Пропустить", 15f, Ui.TEXT_DIM).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { finishOnboarding() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(FrameLayout(this@OnboardingActivity).apply {
                addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.END))
            })
            addView(pageBox, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(dots, LinearLayout.LayoutParams(-1, dp(24)))
            addView(nextButton, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16) })
        }
        content.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(dp(24), dp(8) + insets.systemWindowInsetTop, dp(24), dp(24) + insets.systemWindowInsetBottom)
            insets
        }

        // Свайп влево/вправо листает страницы.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 400) return false
                if (vx < 0 && index < pages.lastIndex) show(index + 1, forward = true)
                if (vx > 0 && index > 0) show(index - 1, forward = false)
                return true
            }
        })
        content.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(content, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        show(0, forward = true, animate = false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (index > 0) show(index - 1, forward = false) else finishOnboarding()
    }

    private fun show(i: Int, forward: Boolean, animate: Boolean = true) {
        index = i
        val page = pages[i]
        pageBox.removeAllViews()
        pageBox.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        pageBox.addView(Ui.iconTile(this, page.icon, sizeDp = 96).apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
        pageBox.addView(Ui.text(this, page.title, 26f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(10))
        })
        pageBox.addView(Ui.text(this, page.text, 16f, Ui.TEXT_DIM).apply { gravity = Gravity.CENTER })
        if (page.tips.isNotEmpty()) {
            pageBox.addView(Ui.card(this).apply {
                setPadding(dp(16), dp(4), dp(16), dp(16))
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
                page.tips.forEach { (icon, t, d) -> addView(Ui.featureRow(this@OnboardingActivity, icon, t, d)) }
            })
        }
        pageBox.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        if (animate) {
            pageBox.alpha = 0f
            pageBox.translationX = dp(if (forward) 40 else -40).toFloat()
            pageBox.animate().alpha(1f).translationX(0f).setDuration(220).start()
        }

        dots.removeAllViews()
        pages.indices.forEach { d ->
            dots.addView(View(this).apply {
                background = Ui.rounded(this@OnboardingActivity, if (d == i) Ui.ACCENT else 0x44FFFFFF, 4)
            }, LinearLayout.LayoutParams(dp(if (d == i) 22 else 8), dp(8)).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        nextButton.text = if (i == pages.lastIndex) "Начать" else "Далее"
    }

    private fun finishOnboarding() {
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit().putBoolean(Prefs.ONBOARDING_DONE, true).apply()
        finish()
    }
}
