package dev.hiberno

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Non-exported target for the widgets and the delayed-hibernation alarm, so other apps can't trigger it. */
class HibernateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val result = goAsync()
        HibernateService.hibernateNow(c, announce = i.getBooleanExtra(EXTRA_ANNOUNCE, false)) { result.finish() }
    }

    companion object {
        private const val EXTRA_ANNOUNCE = "announce"

        fun pending(c: Context, announce: Boolean): PendingIntent = PendingIntent.getBroadcast(
            c, if (announce) 1 else 0,
            Intent(c, HibernateReceiver::class.java).putExtra(EXTRA_ANNOUNCE, announce),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
