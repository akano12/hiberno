package dev.hiberno

import android.annotation.TargetApi
import android.app.Activity
import android.app.UiModeManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.reflect.KMutableProperty0

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Colors.apply(this)
        prefs = Prefs(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, px(32)) }

        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(8), px(8))
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_back)
                setBackgroundResource(android.R.color.transparent)
                contentDescription = "Back"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(px(48), px(48)))
            addView(text("Settings", 22f, R.color.text, bold = true))
        })

        section("Hibernation")
        group {
            switchRow("Hibernate on screen off", "Put selected apps to sleep every time the screen turns off", prefs::enabled)
            valueRow("Delay", "Wait before hibernating, so a quick glance at the phone does not count", { delayLabel(prefs.delayMin) }) {
                prefs.delayMin = DELAYS[(DELAYS.indexOf(prefs.delayMin) + 1) % DELAYS.size]
            }
            switchRow("Keep active apps awake", "Skip apps playing music, navigating or uploading", prefs::keepActive)
            switchRow("Pause while charging", "Do nothing when the phone is plugged in", prefs::pauseCharging)
        }
        section("Battery")
        group { switchRow("Aggressive Doze", "Enter deep sleep right after the screen turns off instead of waiting", prefs::doze) }
        section("Wake-up cut-off · LSPosed")
        group {
            valueRow("Status", "Blocks other apps from waking sleeping ones", { if (App.xposed != null) "Active" else "Off" }, null)
            switchRow("Count blocked wake-ups", "Show how often each app was stopped from waking", prefs::countWakeups)
        }
        section("Notifications")
        group {
            switchRow("Confirm hibernation", "Show a short message after the widget or button is used", prefs::confirm)
            valueRow("Background notification", "Android requires it; you can hide it in system settings", { "Open" }) {
                startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, HibernateService.CHANNEL))
            }
        }
        section("Appearance")
        if (Build.VERSION.SDK_INT >= 31) themePicker() // per-app night mode needs Android 12
        group {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(16), px(14), px(16), px(6))
                addView(text("Accent", 16f, R.color.text, bold = true))
                addView(text("Colour of buttons, tabs and highlights", 13f, R.color.muted))
                addView(palettePicker(), LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(8) })
            })
            switchRow("Pure black background", "In the dark theme, which saves power on OLED screens", prefs::black) { recreate() }
        }
        section("About")
        group {
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            valueRow("Hiberno $version", "Free software under GPL-3.0 · github.com/akano12/hiberno", { "Open" }) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
            }
            valueRow("Open-source libraries", "libsu and libxposed (Apache License 2.0)", { "" }, null)
        }

        // A fixed id lets the scroll position survive recreate() after a colour change.
        setContentView(ScrollView(this).apply { id = R.id.settings_scroll; fitsSystemWindows = true; addView(body) })
    }

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    /** [color] is a colour resource, or a theme attribute (R.attr.*) for colours a palette can change. */
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(if (resources.getResourceTypeName(color) == "attr") attrColor(color) else getColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(title: String) = body.addView(text(title.uppercase(), 13f, R.attr.hAccent, bold = true).apply {
        letterSpacing = 0.06f
        setPadding(px(24), px(18), px(24), px(8))
    })

    private fun group(rows: LinearLayout.() -> Unit) = body.addView(
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_card); rows() },
        LinearLayout.LayoutParams(-1, -2).apply { marginStart = px(16); marginEnd = px(16) },
    )

    private fun row(title: String, hint: String, trailing: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(64)
        setPadding(px(16), px(14), px(16), px(14))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16f, R.color.text, bold = true))
            addView(text(hint, 13f, R.color.muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(trailing)
    }

    private fun LinearLayout.switchRow(title: String, hint: String, pref: KMutableProperty0<Boolean>, onChange: () -> Unit = {}) {
        val sw = Switch(context).apply {
            isChecked = pref.get()
            contentDescription = title
            setOnCheckedChangeListener { _, on -> pref.set(on); onChange() }
        }
        addView(row(title, hint, sw).apply { setOnClickListener { sw.toggle() } })
    }

    private fun LinearLayout.valueRow(title: String, hint: String, value: () -> String, onClick: (() -> Unit)?) {
        val v = text(value(), 14f, R.attr.hAccent, bold = true).apply { setPadding(px(12), 0, 0, 0) }
        addView(row(title, hint, v).apply {
            if (onClick != null) setOnClickListener { onClick(); v.text = value() }
        })
    }

    /** One swatch per palette, drawn in that palette's own colours for the current light/dark theme. */
    private fun palettePicker() = LinearLayout(this).apply {
        for ((id, style, title) in Colors.PALETTES) {
            val look = ContextThemeWrapper(context, R.style.Theme_Hiberno).apply { if (style != 0) theme.applyStyle(style, true) }
            val on = prefs.palette == id
            addView(FrameLayout(context).apply {
                contentDescription = title
                isSelected = on
                background = if (on) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(look.attrColor(R.attr.hAccentSoft)) } else null
                setOnClickListener { prefs.palette = id; recreate() }
                addView(ImageView(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(look.attrColor(R.attr.hAccent)) }
                    if (on) setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(look.attrColor(R.attr.hOnAccent))
                    setPadding(px(7), px(7), px(7), px(7))
                }, FrameLayout.LayoutParams(px(32), px(32), Gravity.CENTER))
            }, LinearLayout.LayoutParams(px(44), px(44)))
        }
    }

    @TargetApi(31)
    private fun themePicker() {
        val modes = listOf(UiModeManager.MODE_NIGHT_AUTO to "System", UiModeManager.MODE_NIGHT_NO to "Light", UiModeManager.MODE_NIGHT_YES to "Dark")
        val bar = LinearLayout(this).apply { setBackgroundResource(R.drawable.bg_tabs); setPadding(px(4), px(4), px(4), px(4)) }
        for ((mode, label) in modes) {
            val on = prefs.theme == mode
            bar.addView(text(label, 14f, if (on) R.attr.hOnAccent else R.color.text, bold = true).apply {
                gravity = Gravity.CENTER
                isSelected = on
                if (on) setBackgroundResource(R.drawable.bg_tab_on)
                setOnClickListener {
                    prefs.theme = mode
                    getSystemService(UiModeManager::class.java).setApplicationNightMode(mode) // recreates the activity
                    recreate()
                }
            }, LinearLayout.LayoutParams(0, px(44), 1f))
        }
        body.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { marginStart = px(16); marginEnd = px(16); bottomMargin = px(10) })
    }

    companion object {
        private const val REPO_URL = "https://github.com/akano12/hiberno"
        private val DELAYS = listOf(0, 1, 5, 15)
        private fun delayLabel(min: Int) = if (min == 0) "Immediately" else "After $min min"
    }
}
