package dev.hiberno

import android.animation.LayoutTransition
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.topjohnwu.superuser.Shell
import kotlin.concurrent.thread

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private class AppEntry(val label: String, val info: ApplicationInfo) {
        val pkg: String get() = info.packageName
        val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }

    private enum class Filter { USER, SYSTEM, SELECTED }

    private lateinit var prefs: Prefs
    private lateinit var list: ListView
    private val adapter = AppAdapter()
    private val icons = HashMap<String, Drawable>()
    private var apps: List<AppEntry> = emptyList()
    private var adding = false
    private val picked = LinkedHashSet<String>() // chosen in "Add apps", not yet added to the list
    private var filter = Filter.USER
    private var query = ""
    private var collapsed = false
    private var wakeups: Map<String, Int> = emptyMap()
    private var colors = ""

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        colors = Colors.apply(this)
        prefs = Prefs(this)
        HibernateService.start(this)
        setContentView(R.layout.activity_main)
        (findViewById<LinearLayout>(R.id.hero).parent as ViewGroup).layoutTransition.enableTransitionType(LayoutTransition.CHANGING)
        findViewById<LinearLayout>(R.id.hero).layoutTransition.enableTransitionType(LayoutTransition.CHANGING)

        list = findViewById(R.id.list)
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            private var userScrolling = false
            override fun onScrollStateChanged(v: AbsListView, state: Int) { userScrolling = state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE }
            override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) {
                // Collapse only on a real scroll; expand whenever back at the top (also after a relayout).
                if (!v.canScrollVertically(-1)) setCollapsed(false)
                else if (userScrolling && !adding && !collapsed && staysScrollable(v)) setCollapsed(true)
            }
        })

        val openSettings = View.OnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        for (id in intArrayOf(R.id.settings, R.id.tile_root, R.id.tile_cutoff, R.id.tile_doze)) findViewById<View>(id).setOnClickListener(openSettings)
        findViewById<View>(R.id.tab_sleeping).setOnClickListener { adding = false; render() }
        findViewById<View>(R.id.tab_add).setOnClickListener { adding = true; render() }
        findViewById<View>(R.id.done).setOnClickListener { addPicked() }
        findViewById<View>(R.id.hibernate).setOnClickListener { HibernateService.hibernateNow(this, announce = true) }
        mapOf(R.id.chip_user to Filter.USER, R.id.chip_system to Filter.SYSTEM, R.id.chip_selected to Filter.SELECTED).forEach { (id, f) ->
            findViewById<View>(id).setOnClickListener { filter = f; render() }
        }
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); render() }
        })

        thread {
            val pm = packageManager
            val loaded = pm.getInstalledApplications(0)
                .filter { it.packageName != packageName }
                .map { AppEntry(it.loadLabel(pm).toString(), it) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            runOnUiThread { apps = loaded; render() }
        }
        // Async: getting the shell blocks while Magisk shows its grant prompt.
        Shell.getShell { render() }
    }

    override fun onResume() {
        super.onResume()
        if (Colors.applied(this) != colors) return recreate() // palette or black changed in Settings
        prefs.raw.registerOnSharedPreferenceChangeListener(this)
        render()
        loadWakeups()
    }

    override fun onPause() {
        prefs.raw.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == "lastRun") loadWakeups()
        render()
    }

    /** Adds the picked apps to the list, shown as awaiting hibernation, then hibernates just those. */
    private fun addPicked() {
        val added = picked.toSet()
        picked.clear()
        adding = false
        // Forget any stale result from before they were last removed, so they don't show as asleep yet.
        prefs.lastStopped -= added
        prefs.lastSkipped -= added
        prefs.apps += added
        render()
        HibernateService.hibernateNow(this, announce = true, only = added)
    }

    private fun remove(pkg: String) {
        prefs.lastStopped -= pkg
        prefs.lastSkipped -= pkg
        prefs.apps -= pkg
    }

    /**
     * True when the list would still scroll after the card collapses. Without this check, a list only slightly taller
     * than the screen would collapse, fit, snap back to the top and expand again, flickering.
     */
    private fun staysScrollable(v: AbsListView): Boolean {
        val row = v.getChildAt(0) ?: return false // rows share one height
        val content = v.count * (row.height + list.dividerHeight) + v.paddingTop + v.paddingBottom
        val freed = findViewById<View>(R.id.hero_tiles).height + findViewById<View>(R.id.hero_sub).height +
            (40 * resources.displayMetrics.density).toInt() // margins + smaller count text
        return content > v.height + freed
    }

    private fun setCollapsed(c: Boolean) {
        if (c == collapsed) return
        collapsed = c
        show(R.id.hero_sub, !c)
        show(R.id.hero_tiles, !c)
        text(R.id.hero_count).textSize = if (c) 28f else 48f
        text(R.id.hero_label).textSize = if (c) 18f else 22f
    }

    /** Counts the cut-off hook's log lines since the last hibernation round. */
    private fun loadWakeups() {
        if (!prefs.countWakeups || App.xposed == null) { wakeups = emptyMap(); return }
        val since = prefs.lastRun / 1000.0
        Shell.cmd("logcat -d -v epoch -s ${Cutoff.WAKE_TAG}:I").submit { r ->
            wakeups = parseWakeups(r.out, since)
            render()
        }
    }

    private fun visibleApps(): List<AppEntry> {
        val listed = prefs.apps
        if (!adding) return apps.filter { it.pkg in listed }
        return apps.filter {
            it.pkg !in listed && when (filter) { // apps already on the list aren't offered again
                Filter.USER -> !it.system || it.info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                Filter.SYSTEM -> it.system
                Filter.SELECTED -> it.pkg in picked
            } && (query.isEmpty() || it.label.contains(query, true) || it.pkg.contains(query, true))
        }
    }

    private fun render() {
        val listed = prefs.apps
        val asleep = (prefs.lastStopped intersect listed).size
        val kept = (prefs.lastSkipped intersect listed).size
        text(R.id.hero_count).text = asleep.toString()
        text(R.id.hero_label).text = if (asleep == 1) "app asleep" else "apps asleep"
        text(R.id.hero_sub).text = if (prefs.lastRun == 0L) "Runs the next time the screen turns off"
        else "Last run ${lastRunLabel(prefs.lastRun)} · $kept kept awake"

        val root = Shell.isAppGrantedRoot()
        tile(R.id.dot_root, R.id.val_root, root == true, when (root) { null -> "Checking"; true -> "Granted"; false -> "Missing" })
        tile(R.id.dot_cutoff, R.id.val_cutoff, App.xposed != null, if (App.xposed != null) "Active" else "Off")
        tile(R.id.dot_doze, R.id.val_doze, prefs.doze, if (prefs.doze) "Aggressive" else "Normal")

        tab(R.id.tab_sleeping, !adding, "Sleeping · ${listed.size}")
        tab(R.id.tab_add, adding, null)
        chip(R.id.chip_user, filter == Filter.USER)
        chip(R.id.chip_system, filter == Filter.SYSTEM)
        chip(R.id.chip_selected, filter == Filter.SELECTED)
        text(R.id.done).apply {
            // Dim via background/text, not view alpha: the show/hide animation resets alpha to 1.
            isEnabled = picked.isNotEmpty()
            background.mutate().alpha = if (isEnabled) 255 else 70
            setTextColor(if (isEnabled) attrColor(R.attr.hOnAccent) else getColor(R.color.muted))
        }
        text(R.id.done).text = when (picked.size) {
            0 -> "Select apps to hibernate"
            1 -> "Hibernate 1 app"
            else -> "Hibernate ${picked.size} apps"
        }

        adapter.items = visibleApps()
        adapter.notifyDataSetChanged()
        val empty = !adding && adapter.items.isEmpty() && apps.isNotEmpty()
        if (adding) setCollapsed(false)
        show(R.id.hero, !adding)
        show(R.id.add_controls, adding)
        show(R.id.done, adding)
        show(R.id.sleep_dock, !adding)
        show(R.id.list, !empty)
        show(R.id.empty, empty)
    }

    private fun lastRunLabel(time: Long) =
        if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) "just now"
        else DateUtils.getRelativeTimeSpanString(time).toString().replaceFirstChar { it.lowercase() }

    private fun text(id: Int) = findViewById<TextView>(id)
    private fun show(id: Int, visible: Boolean) { findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE }

    private fun tile(dotId: Int, valueId: Int, ok: Boolean, value: String) {
        text(dotId).compoundDrawableTintList = ColorStateList.valueOf(if (ok) attrColor(R.attr.hAccent) else getColor(R.color.muted))
        text(valueId).text = value
    }

    private fun tab(id: Int, on: Boolean, label: String?) = text(id).apply {
        if (label != null) text = label
        setBackgroundResource(if (on) R.drawable.bg_tab_on else 0)
        setTextColor(if (on) attrColor(R.attr.hOnAccent) else getColor(R.color.text))
        isSelected = on
    }

    private fun chip(id: Int, on: Boolean) = text(id).apply {
        setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        setTextColor(getColor(R.color.text))
        isSelected = on
    }

    private inner class AppAdapter : BaseAdapter() {
        var items: List<AppEntry> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.row_app, parent, false)
            val a = items[position]
            row.findViewById<ImageView>(R.id.icon).setImageDrawable(icons.getOrPut(a.pkg) { a.info.loadIcon(packageManager) })
            row.findViewById<TextView>(R.id.name).text = a.label
            val status = row.findViewById<TextView>(R.id.status)
            val remove = row.findViewById<ImageButton>(R.id.remove)
            if (adding) {
                val on = a.pkg in picked
                if (on) row.setBackgroundResource(R.drawable.bg_row_selected) else row.background = null
                row.isSelected = on
                status.set(a.pkg, R.color.muted, 0)
                row.contentDescription = "${a.label}, ${if (on) "selected" else "not selected"}"
                // The row handles its own tap: ListView's item click drops quick taps when it re-binds rows mid-tap.
                row.setOnClickListener {
                    if (!picked.remove(a.pkg)) picked += a.pkg
                    render()
                }
            } else {
                row.setBackgroundResource(R.drawable.bg_row)
                row.isSelected = false
                when (a.pkg) {
                    in prefs.lastSkipped -> status.set("Kept awake · in use", R.color.warn, R.drawable.ic_play)
                    in prefs.lastStopped -> status.set(
                        wakeups[a.pkg]?.let { "Asleep · $it wake-ups blocked" } ?: "Asleep", R.color.muted, R.drawable.ic_moon)
                    else -> status.set("Awaiting hibernation", R.color.muted, 0)
                }
                remove.contentDescription = "Remove ${a.label} from list"
                remove.setOnClickListener { remove(a.pkg) }
                row.contentDescription = null
                row.setOnClickListener(null)
                row.isClickable = false // setOnClickListener(null) still leaves it clickable
            }
            remove.visibility = if (adding) View.GONE else View.VISIBLE
            return row
        }

        private fun TextView.set(s: String, color: Int, icon: Int) {
            text = s
            setTextColor(getColor(color))
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        }
    }

    companion object {
        /** Parses `logcat -v epoch` lines ("  1730000000.123  1000  1234 I Tag: pkg") into per-package counts. */
        // shortcut: logcat's ring buffer rotates, so counts can undercount on busy phones; persist counts in the hook if that matters.
        fun parseWakeups(lines: List<String>, sinceEpochSec: Double): Map<String, Int> = lines
            .mapNotNull { line ->
                val time = line.trim().substringBefore(' ').toDoubleOrNull() ?: return@mapNotNull null
                if (time < sinceEpochSec) null else line.substringAfterLast(": ").trim()
            }
            .groupingBy { it }.eachCount()
    }
}
