package io.github.lyricsfloat

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class WhatsNewActivity : Activity() {
    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val since = intent.extras?.getInt("since", -1) ?: -1
        val current = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()

        val releasesToShow = if (since == -1) {
            // Показываем все версии до текущей
            Changelog.releases.filter { it.versionCode <= current }
        } else if (since == 0) {
            // Показываем только текущую версию (fallback: первую версию до текущей)
            val currentRelease = Changelog.releases.find { it.versionCode == current }
            if (currentRelease != null) listOf(currentRelease) else {
                Changelog.releases.filter { it.versionCode <= current }.take(1)
            }
        } else {
            // Показываем версии после since до текущей
            Changelog.newerThan(since, current)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(dp(24), dp(8) + insets.systemWindowInsetTop, dp(24), dp(24) + insets.systemWindowInsetBottom)
                insets
            }
        }

        // Верхняя часть с заголовком
        content.addView(Ui.iconTile(this, R.drawable.ic_note, sizeDp = 64).apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
        content.addView(Ui.text(this, "Что нового", 26f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        })
        content.addView(Ui.text(this, "Версия ${packageManager.getPackageInfo(packageName, 0).versionName}", 15f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
        })

        // Список изменений
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        
        val releasesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(0), dp(24), dp(0), dp(16))
        }

        releasesToShow.forEach { release ->
            val card = Ui.card(this).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            
            card.addView(Ui.text(this, "Версия ${release.version}", 15f, bold = true).apply {
                })
            
            release.items.forEach { item ->
                val row = LinearLayout(this@WhatsNewActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                }
                
                row.addView(View(this@WhatsNewActivity).apply {
                    background = Ui.rounded(this@WhatsNewActivity, Ui.ACCENT, 4)
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                        topMargin = dp(7)
                        marginEnd = dp(12)
                    }
                })
                
                row.addView(Ui.text(this@WhatsNewActivity, item, 14f).apply {
                    setPadding(dp(0), dp(0), dp(0), dp(0))
                })
                
                card.addView(row)
            }
            
            releasesContainer.addView(card)
        }
        
        scrollView.addView(releasesContainer)
        content.addView(scrollView)

        // Кнопка "Понятно"
        val button = Ui.primaryButton(this, "Понятно") {
            markSeen()
            finish()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply {
                topMargin = dp(16)
            }
        }
        content.addView(button)

        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        markSeen()
        finish()
    }

    private fun markSeen() {
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit().putInt(Prefs.LAST_SEEN_VERSION, 
            packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()).apply()
    }
}
