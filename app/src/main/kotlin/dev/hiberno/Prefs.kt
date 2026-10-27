package dev.hiberno

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** All app settings and last-run state, typed. Keys "apps" and "doze" predate this class; keep them. */
class Prefs(c: Context) {
    val raw: SharedPreferences = c.getSharedPreferences("hiberno", Context.MODE_PRIVATE)

    var apps by Pref("apps", emptySet<String>())
    var enabled by Pref("enabled", true)
    var delayMin by Pref("delayMin", 0)
    var keepActive by Pref("keepActive", true)
    var pauseCharging by Pref("pauseCharging", false)
    var doze by Pref("doze", false)
    var countWakeups by Pref("countWakeups", true)
    var confirm by Pref("confirm", true)
    var theme by Pref("theme", 0) // UiModeManager.MODE_NIGHT_AUTO / NO / YES
    var palette by Pref("palette", "teal") // see Colors.PALETTES
    var black by Pref("black", false)

    var lastRun by Pref("lastRun", 0L)
    var lastStopped by Pref("lastStopped", emptySet<String>())
    var lastSkipped by Pref("lastSkipped", emptySet<String>())

    private class Pref<T>(val key: String, val default: T) : ReadWriteProperty<Prefs, T> {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(thisRef: Prefs, property: KProperty<*>): T = (thisRef.raw.all[key] ?: default) as T

        @Suppress("UNCHECKED_CAST")
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: T) {
            thisRef.raw.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, HashSet(value as Set<String>)) // copy: prefs must not share our set
                }
            }.apply()
        }
    }
}
