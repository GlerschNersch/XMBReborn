package dev.xmbreborn

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.xmbreborn.xmb.MonthPalette
import dev.xmbreborn.xmb.XmbPrefs
import java.text.DateFormat
import java.util.Date

/**
 * Mock-up of a side tray of cards beside a column's list (after the user's "Active Ribbon" and
 * "Side-Docked Modular Tray" concepts). Real where Android allows: battery, storage, memory,
 * clock, the theme swatches and ambient toggle (they change the real settings), and the quick
 * actions (they open the real panels). Now playing and weather are sample data. Debug/lab only.
 */
class WidgetLabTray(private val activity: Activity, private val xmb: XmbView) {
    private val prefs = XmbPrefs(activity)
    private val ticker = Handler(Looper.getMainLooper())
    private val refreshers = ArrayList<() -> Unit>()

    init {
        ticker.post(object : Runnable {
            override fun run() { refreshers.forEach { it() }; ticker.postDelayed(this, 5000) }
        })
    }

    fun stop() = ticker.removeCallbacksAndMessages(null)

    /** Where the tray starts (screen x): list labels end before it. */
    fun left(width: Int) = width * if (width > activity.resources.displayMetrics.heightPixels) LEFT_LANDSCAPE else LEFT

    // ---- Music column: "Active Ribbon" ---------------------------------------------------

    fun music(width: Int, height: Int): View = column(width, height,
        nowPlaying(),
        ambient(),
        telemetry(),
    )

    private fun nowPlaying(): View = card("NOW PLAYING", "Sample").apply {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(activity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(AMBER, Color.rgb(120, 60, 10)))
                    .apply { cornerRadius = dp(10f) }
            }, LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(text("Sample Song", 16f, bold = true))
                addView(text("Sample Artist", 12f, alpha = 0.75f))
            })
        })
        addView(progress(0.62f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(12) })
        addView(LinearLayout(activity).apply {
            addView(text("2:18", 11f, alpha = 0.7f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("3:42", 11f, alpha = 0.7f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            addView(text("⏮", 18f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { })
            addView(text("▶", 18f).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(40, 24, 4))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AMBER) }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(text("⏭", 18f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    /** The real Ambient Sound setting. */
    private fun ambient(): View = card("AMBIENT SOUND", "Live").apply {
        val on = chip("On"); val off = chip("Off")
        fun show() { on.isSelected = prefs.ambient; off.isSelected = !prefs.ambient; styleChip(on); styleChip(off) }
        on.setOnClickListener { prefs.ambient = true; show() }
        off.setOnClickListener { prefs.ambient = false; show() }
        show()
        addView(LinearLayout(activity).apply {
            addView(on, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(6) })
            addView(off, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(6) })
        })
    }

    /** Battery, storage and memory, all real. */
    private fun telemetry(): View = card("SYSTEM", "Live").apply {
        val battery = stat("BATTERY"); val storage = stat("STORAGE"); val memory = stat("MEMORY")
        addView(LinearLayout(activity).apply {
            for ((i, s) in listOf(battery, storage, memory).withIndex()) {
                addView(s.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(6) })
            }
        })
        val update: () -> Unit = {
            val b = batteryInfo()
            battery.second.text = "${b.percent}%"; battery.third.text = if (b.charging) "Charging" else "${b.tempC}°C"
            val fs = StatFs(Environment.getDataDirectory().path)
            storage.second.text = "${fs.availableBytes / GB}GB"; storage.third.text = "free"
            val mi = ActivityManager.MemoryInfo().also { activity.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            memory.second.text = "%.1fGB".format((mi.totalMem - mi.availMem) / GB.toDouble())
            memory.third.text = "of ${(mi.totalMem + GB / 2) / GB}GB"
        }
        refreshers += update
        update()
    }

    // ---- Settings column: "Side-Docked Modular Tray" -----------------------------------------

    fun settings(width: Int, height: Int): View = column(width, height,
        themePreview(),
        clock(),
        quickActions(),
    )

    /** Swatches set the real theme colour, previewed on the bar immediately. */
    private fun themePreview(): View = card("THEME", "Live").apply {
        val current = text("", 13f, alpha = 0.85f)
        fun name(v: Int) = if (v == XmbPrefs.ORIGINAL) "Original" else MonthPalette.NAMES[v]
        current.text = name(prefs.color)
        addView(current)
        addView(LinearLayout(activity).apply {
            setPadding(0, dp(8), 0, 0)
            for (v in listOf(XmbPrefs.ORIGINAL, 1, 7, 3, 6)) {
                val month = if (v == XmbPrefs.ORIGINAL) java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) else v
                val c = MonthPalette.colors(month, 12f)
                val swatch = View(activity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.rgb((c[12] * 255 * 2.2f).toInt().coerceAtMost(255), (c[13] * 255 * 2.2f).toInt().coerceAtMost(255), (c[14] * 255 * 2.2f).toInt().coerceAtMost(255)))
                        setStroke(dp(2), if (prefs.color == v) AMBER else Color.argb(90, 255, 255, 255))
                    }
                    setOnClickListener {
                        prefs.color = v
                        xmb.renderer.post { it.themeColor = v }
                        current.text = name(v)
                    }
                }
                // Round at any width: a fixed-size dot centred in an equal share of the row.
                addView(FrameLayout(activity).apply {
                    addView(swatch, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                }, LinearLayout.LayoutParams(0, dp(34), 1f))
            }
        })
    }

    /** Real time and date; weather is sample data. */
    private fun clock(): View = card("CLOCK", "").apply {
        val time = text("", 30f, bold = true); val date = text("", 13f, alpha = 0.8f)
        addView(time); addView(date)
        addView(text("☀  18°  Partly cloudy  (sample)", 13f, alpha = 0.8f).apply { setPadding(0, dp(6), 0, 0) })
        val update: () -> Unit = {
            time.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            date.text = DateFormat.getDateInstance(DateFormat.FULL).format(Date())
        }
        refreshers += update
        update()
    }

    /** Each opens the real system panel. */
    private fun quickActions(): View = card("QUICK ACTIONS", "").apply {
        val actions = listOf(
            "Internet" to Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
            "Bluetooth" to Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            "Volume" to Intent(Settings.Panel.ACTION_VOLUME),
            "Display" to Intent(Settings.ACTION_DISPLAY_SETTINGS),
        )
        for (row in actions.chunked(2)) {
            addView(LinearLayout(activity).apply {
                for ((i, a) in row.withIndex()) {
                    addView(chip(a.first).apply {
                        isSelected = true; styleChip(this)
                        setOnClickListener { runCatching { activity.startActivity(a.second.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) leftMargin = dp(8) })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    // ---- real widgets ----------------------------------------------------------------------------

    /** A column of real Android widgets, each in a gold glass card titled with its name. */
    fun realColumn(width: Int, height: Int, widgets: List<Triple<CharSequence, View, Int>>): View =
        column(width, height, *widgets.map { (title, view, heightDp) ->
            card(title.toString().uppercase(), "Widget").apply {
                // The widget sizes itself (scaled to fit); heightDp is its unscaled height.
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }.toTypedArray())

    /** The tray's inner width in dp, for sizing widgets to fit their cards. */
    fun cardWidthDp(width: Int): Int {
        val columns = if (width > activity.resources.displayMetrics.heightPixels) 2 else 1
        val px = (width - left(width)) - dp(14) - (columns - 1) * dp(12)
        return ((px / columns - dp(28)) / activity.resources.displayMetrics.density).toInt()
    }

    // ---- building blocks -----------------------------------------------------------------------

    /**
     * The cards, right of the list. Portrait: one column just under the bar. Landscape: below the
     * bar (so it doesn't cover the categories) in two columns, since height is what's short.
     */
    private fun column(width: Int, height: Int, vararg cards: View): View = FrameLayout(activity).apply {
        val landscape = width > height
        val left = left(width)
        val columns = if (landscape) 2 else 1
        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            for (c in 0 until columns) {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    cards.filterIndexed { i, _ -> i % columns == c }.forEach {
                        addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (c > 0) leftMargin = dp(12) })
            }
        }
        addView(grid, FrameLayout.LayoutParams((width - left).toInt() - dp(14), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = left.toInt()
            topMargin = (height * if (landscape) TOP_LANDSCAPE else TOP).toInt()
        })
    }

    /** A dark glass card with a gold hairline, a small-caps title and an optional tag. */
    private fun card(title: String, tag: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.argb(175, 26, 18, 6)); setStroke(dp(1), Color.argb(120, 240, 190, 90)); cornerRadius = dp(16f)
        }
        setPadding(dp(14), dp(12), dp(14), dp(14))
        addView(LinearLayout(activity).apply {
            addView(text(title, 11f, bold = true, color = Color.rgb(255, 226, 170)).apply { letterSpacing = 0.12f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (tag.isNotEmpty()) addView(text(tag, 10f, color = AMBER, alpha = 0.9f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    private fun stat(label: String): Triple<View, TextView, TextView> {
        val value = text("", 16f, bold = true)
        val sub = text("", 10f, color = AMBER, alpha = 0.9f)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { setColor(Color.argb(60, 255, 255, 255)); cornerRadius = dp(10f) }
            setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(text(label, 9f, alpha = 0.7f).apply { letterSpacing = 0.08f })
            addView(value); addView(sub)
        }
        return Triple(box, value, sub)
    }

    private fun chip(label: String) = text(label, 13f, bold = true).apply { gravity = Gravity.CENTER; isClickable = true }

    private fun styleChip(t: TextView) {
        t.background = GradientDrawable().apply {
            cornerRadius = dp(10f)
            if (t.isSelected) { setColor(Color.argb(90, 242, 178, 60)); setStroke(dp(1), AMBER) }
            else { setColor(Color.argb(40, 255, 255, 255)); setStroke(dp(1), Color.argb(60, 255, 255, 255)) }
        }
        t.setTextColor(if (t.isSelected) Color.rgb(255, 230, 180) else Color.argb(170, 255, 255, 255))
    }

    private fun progress(fraction: Float) = LinearLayout(activity).apply {
        background = GradientDrawable().apply { setColor(Color.argb(60, 255, 255, 255)); cornerRadius = dp(2f) }
        addView(View(activity).apply { background = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(2f) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fraction))
        addView(View(activity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fraction))
    }

    private fun text(s: String, sp: Float, bold: Boolean = false, alpha: Float = 1f, color: Int = Color.WHITE) = TextView(activity).apply {
        text = s
        setTextColor(color)
        this.alpha = alpha
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setShadowLayer(3f, 0f, 1f, Color.argb(120, 0, 0, 0))
    }

    private class Battery(val percent: Int, val charging: Boolean, val tempC: Int)

    private fun batteryInfo(): Battery {
        val i = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
        return Battery(if (level >= 0) level * 100 / scale else 0,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL, temp)
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = v * activity.resources.displayMetrics.density

    private companion object {
        const val LEFT = 0.47f   // tray starts here, as a fraction of the width
        const val TOP = 0.27f    // and here, as a fraction of the height (just under the bar)
        const val LEFT_LANDSCAPE = 0.40f
        const val TOP_LANDSCAPE = 0.36f   // below the bar and its labels
        val AMBER = Color.rgb(242, 178, 60)
        const val GB = 1024L * 1024 * 1024
    }
}
