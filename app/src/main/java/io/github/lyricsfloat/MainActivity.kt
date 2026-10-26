package io.github.lyricsfloat

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var notifPill: TextView
    private lateinit var overlayPill: TextView
    private lateinit var batteryPill: TextView
    private lateinit var windowButton: Button
    private lateinit var fullscreenButton: Button
    private lateinit var hint: TextView
    private lateinit var diagView: TextView
    private lateinit var diagBody: LinearLayout

    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }
    private val listener by lazy { ComponentName(this, MediaListener::class.java) }
    private fun dp(v: Int) = Ui.dp(this, v)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.ENABLED) updateButtons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        box.addView(header())
        box.addView(mainCard())
        box.addView(featuresCard())
        box.addView(sourcesCard())
        box.addView(permissionsCard())
        box.addView(diagnosticsCard())
        box.addView(footer())

        val root = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(box)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(dp(16), dp(12) + insets.systemWindowInsetTop, dp(16), dp(24) + insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)

        val current = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        val lastSeen = prefs.getInt(Prefs.LAST_SEEN_VERSION, 0)
        if (!prefs.getBoolean(Prefs.ONBOARDING_DONE, false)) {
            // Новому пользователю «Что нового» не нужен — он и так всё увидит в обучалке.
            prefs.edit().putInt(Prefs.LAST_SEEN_VERSION, current).apply()
            startActivity(Intent(this, OnboardingActivity::class.java))
        } else if (lastSeen < current) {
            startActivity(Intent(this, WhatsNewActivity::class.java).putExtra("since", lastSeen))
        }
    }

    // ——— Блоки экрана ———

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), 0, dp(4))
        addView(Ui.iconTile(this@MainActivity, R.drawable.ic_note, sizeDp = 52))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(Ui.text(this@MainActivity, "Текст песни", 26f, bold = true))
            addView(Ui.text(this@MainActivity, "Текст играющего трека — поверх всего", 14f, Ui.TEXT_DIM))
        })
    }

    private fun mainCard() = Ui.card(this).apply {
        windowButton = Ui.primaryButton(this@MainActivity, "") {
            val enabled = prefs.getBoolean(Prefs.ENABLED, false)
            if (!enabled) askNotificationPermission()
            prefs.edit().putBoolean(Prefs.ENABLED, !enabled).commit()
            MediaWatcher.start(this@MainActivity)
            KeepAliveService.sync(this@MainActivity)
            updateButtons()
        }
        addView(windowButton, LinearLayout.LayoutParams(-1, dp(56)))

        fullscreenButton = Ui.primaryButton(this@MainActivity, "Открыть на весь экран") {
            startActivity(Intent(this@MainActivity, FullscreenActivity::class.java))
        }.also { Ui.style(it, primary = false) }
        addView(fullscreenButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })

        hint = Ui.text(this@MainActivity, "", 13f, Ui.TEXT_DIM).apply { setPadding(dp(2), dp(10), dp(2), 0) }
        addView(hint)

        addView(switchRow("Показывать окно только во время воспроизведения", Prefs.AUTO_SHOW, false))
        addView(switchRow("Не гасить экран, пока открыто окно", Prefs.KEEP_SCREEN_ON, true))
        addView(Ui.linkButton(this@MainActivity, "Как пользоваться  →") {
            startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
        })
        addView(Ui.linkButton(this@MainActivity, "Что нового  →") {
            startActivity(Intent(this@MainActivity, WhatsNewActivity::class.java).putExtra("since", -1))
        })
    }

    private fun featuresCard() = Ui.card(this, "Возможности").apply {
        val c = this@MainActivity
        addView(Ui.featureRow(c, R.drawable.ic_dock, "Плавающее окно",
            "Тяните за название, меняйте размер уголком, прячьте к краю экрана."))
        addView(Ui.featureRow(c, R.drawable.ic_play, "Тап по строке",
            "Перематывает трек на эту строку."))
        addView(Ui.featureRow(c, R.drawable.ic_settings, "Настройки вида",
            "Шрифт, сдвиг текста и прозрачность — изменения видно сразу."))
        addView(Ui.featureRow(c, R.drawable.ic_autoscroll, "Автопрокрутка",
            "Для текстов без таймкодов: текст едет вместе с треком."))
        addView(Ui.featureRow(c, R.drawable.ic_fullscreen, "Полный экран",
            "Обложка на фоне, управление плеером, экран не гаснет."))
        addView(Ui.featureRow(c, R.drawable.ic_search, "Не нашёлся текст?",
            "Ищем в LRCLIB и NetEase, а лупа откроет поиск в Genius."))
    }

    private fun sourcesCard() = Ui.card(this, "Откуда брать трек").apply {
        for (src in Source.entries) {
            addView(CheckBox(this@MainActivity).apply {
                text = src.label
                textSize = 15f
                setTextColor(Ui.TEXT)
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                isChecked = prefs.getBoolean(src.prefKey, src.defaultOn)
                setPadding(dp(4), dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(src.prefKey, checked).apply() }
            })
        }
        addView(Ui.text(this@MainActivity,
            "С YouTube название берётся из заголовка видео, поэтому находится не всё.", 12f, Ui.TEXT_DIM)
            .apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun permissionsCard() = Ui.card(this, "Разрешения").apply {
        val c = this@MainActivity
        notifPill = Ui.pill(c)
        addView(permissionRow(R.drawable.ic_note, "Доступ к уведомлениям", "Чтобы видеть, какой трек играет", notifPill))
        addView(Ui.linkButton(c, "Открыть настройки") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        addView(Ui.text(c,
            "Переключатель серый? «О приложении» → ⋮ → «Разрешить ограниченные настройки».", 12f, Ui.TEXT_DIM))
        addView(Ui.linkButton(c, "О приложении") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        overlayPill = Ui.pill(c)
        addView(permissionRow(R.drawable.ic_dock, "Поверх других приложений", "Для плавающего окна", overlayPill))
        addView(Ui.linkButton(c, "Разрешить") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        batteryPill = Ui.pill(c)
        addView(permissionRow(R.drawable.ic_settings, "Работа в фоне", "Чтобы окно не пропадало", batteryPill))
        addView(Ui.linkButton(c, "Снять ограничение батареи") {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })
        addView(Ui.text(c,
            "На vivo и Xiaomi ещё: «О приложении» → «Батарея» → разрешить высокое энергопотребление " +
                "в фоне и включить «Автозапуск». Пока окно включено, в шторке висит уведомление — так и задумано.",
            12f, Ui.TEXT_DIM))
    }

    private fun diagnosticsCard() = Ui.card(this).apply {
        val c = this@MainActivity
        val arrow = Ui.text(c, "▾", 16f, Ui.TEXT_DIM)
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(c, "ДИАГНОСТИКА", 13f, Ui.TEXT_DIM, bold = true).apply { letterSpacing = 0.06f },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(arrow)
            setOnClickListener {
                val open = diagBody.visibility != View.VISIBLE
                diagBody.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "▴" else "▾"
                if (open) updateDiagnostics()
            }
        })
        diagBody = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(Ui.text(c,
                "Окно не появляется или полный экран пишет «ничего не играет»? Перезапустите сервис. " +
                    "Не помогло — выключите и включите доступ к уведомлениям и пришлите отчёт.",
                13f, Ui.TEXT_DIM).apply { setPadding(0, dp(10), 0, dp(10)) })
            diagView = Ui.text(c, "", 11f).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                background = Ui.rounded(c, Ui.TILE, 12)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            addView(diagView)
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.linkButton(c, "Перезапустить") {
                    NotificationListenerService.requestRebind(listener)
                    MediaWatcher.start(c)
                    diagView.postDelayed({ updateDiagnostics() }, 1500)
                })
                addView(Ui.linkButton(c, "Скопировать") {
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("LyricsFloat", Diagnostics.report(c)))
                    Toast.makeText(c, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
                })
                addView(Ui.linkButton(c, "Очистить") {
                    Diagnostics.crashFile(c).delete()
                    Diagnostics.lastError = null
                    updateDiagnostics()
                })
            })
        }
        addView(diagBody)
    }

    private fun footer() = Ui.text(this,
        "Текст песни · версия ${packageManager.getPackageInfo(packageName, 0).versionName}\nАвтор: Латыпов Юсуп · lyparus",
        12f, Ui.TEXT_DIM).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun permissionRow(icon: Int, title: String, desc: String, pill: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(16), 0, 0)
        addView(Ui.iconTile(this@MainActivity, icon))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            addView(Ui.text(this@MainActivity, title, 15f, bold = true))
            addView(Ui.text(this@MainActivity, desc, 13f, Ui.TEXT_DIM))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(pill)
    }

    private fun switchRow(label: String, key: String, default: Boolean) = Switch(this).apply {
        text = label
        textSize = 14f
        setTextColor(Ui.TEXT)
        thumbTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Ui.ACCENT, 0xFFBBBBBB.toInt()),
        )
        trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x88FFCC00.toInt(), 0x44FFFFFF),
        )
        isChecked = prefs.getBoolean(key, default)
        setPadding(dp(2), dp(14), 0, 0)
        setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
    }

    // ——— Состояние ———

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        MediaWatcher.start(this)
        KeepAliveService.sync(this)
        updateButtons()
        if (hasNotificationAccess()) NotificationListenerService.requestRebind(listener)
        if (diagBody.visibility == View.VISIBLE) diagView.postDelayed({ updateDiagnostics() }, 800)
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private fun updateDiagnostics() {
        diagView.text = runCatching { Diagnostics.report(this) }.getOrElse { it.toString() }.trim()
    }

    private fun updateButtons() {
        val notifOk = hasNotificationAccess()
        val overlayOk = Settings.canDrawOverlays(this)
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val enabled = prefs.getBoolean(Prefs.ENABLED, false)

        Ui.setPill(notifPill, notifOk, "Есть", "Нет")
        Ui.setPill(overlayPill, overlayOk, "Есть", "Нет")
        Ui.setPill(batteryPill, batteryOk, "Можно", "Ограничено")

        windowButton.isEnabled = notifOk && overlayOk
        windowButton.alpha = if (windowButton.isEnabled) 1f else 0.4f
        windowButton.text = if (enabled) "Закрыть окно с текстом" else "Открыть окно с текстом"
        Ui.style(windowButton, primary = !enabled)

        fullscreenButton.isEnabled = notifOk
        fullscreenButton.alpha = if (notifOk) 1f else 0.4f

        hint.text = when {
            !notifOk -> "Сначала дайте доступ к уведомлениям — раздел «Разрешения» ниже."
            !overlayOk -> "Для плавающего окна разрешите показ поверх окон. Полный экран уже доступен."
            enabled && !NowPlaying.hasTrack -> "Окно включено и появится, как только заиграет трек."
            enabled -> "Окно открыто. Закрыть можно здесь, крестиком в окне или из шторки."
            else -> ""
        }
        hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun askNotificationPermission() {
        // Без этого разрешения уведомление фонового режима просто не видно, но всё работает.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun hasNotificationAccess() = MediaWatcher.hasNotificationAccess(this)
}
