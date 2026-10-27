package dev.hiberno

import android.app.Activity
import android.content.Context
import android.util.TypedValue

/** Accent palettes (same set as Doppel) and the pure black background, applied as theme overlays. */
object Colors {
    // id stored in prefs, overlay style (0 = the app theme itself), title
    val PALETTES = listOf(
        Triple("teal", 0, "Teal"),
        Triple("ocean", R.style.ThemeOverlay_Hiberno_Ocean, "Ocean"),
        Triple("berry", R.style.ThemeOverlay_Hiberno_Berry, "Berry"),
        Triple("sky", R.style.ThemeOverlay_Hiberno_Sky, "Sky"),
    )

    /** Call before setContentView; returns what was applied so the activity can tell when to recreate. */
    fun apply(a: Activity): String {
        val p = Prefs(a)
        val style = PALETTES.firstOrNull { it.first == p.palette }?.second ?: 0 // unknown id = default
        if (style != 0) a.theme.applyStyle(style, true)
        if (p.black) a.theme.applyStyle(R.style.ThemeOverlay_Hiberno_Black, true) // a no-op in the light theme
        return applied(a)
    }

    fun applied(c: Context) = Prefs(c).let { "${it.palette}/${it.black}" }
}

fun Context.attrColor(attr: Int): Int {
    val v = TypedValue()
    theme.resolveAttribute(attr, v, true)
    return v.data
}
