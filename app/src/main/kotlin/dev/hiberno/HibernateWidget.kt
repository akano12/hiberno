package dev.hiberno

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/** 1×1 home-screen button; [HibernateWidgetWide] adds the asleep count. */
open class HibernateWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) = mgr.updateAppWidget(ids, views(c))

    open fun views(c: Context) = RemoteViews(c.packageName, R.layout.widget).apply {
        setOnClickPendingIntent(R.id.widget_root, HibernateReceiver.pending(c, announce = true))
    }

    companion object {
        fun refreshAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            for (w in listOf(HibernateWidget(), HibernateWidgetWide())) {
                val ids = mgr.getAppWidgetIds(ComponentName(c, w.javaClass))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, w.views(c))
            }
        }
    }
}

class HibernateWidgetWide : HibernateWidget() {
    override fun views(c: Context) = RemoteViews(c.packageName, R.layout.widget_wide).apply {
        val p = Prefs(c)
        setTextViewText(R.id.widget_count, "${(p.lastStopped intersect p.apps).size} asleep")
        setTextViewText(R.id.widget_sub, "Tap to hibernate ${p.apps.size}")
        setOnClickPendingIntent(R.id.widget_root, HibernateReceiver.pending(c, announce = true))
    }
}
