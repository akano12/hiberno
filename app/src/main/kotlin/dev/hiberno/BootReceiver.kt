package dev.hiberno

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service after boot, and after an app update (which kills it) without waiting for the app to be opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) HibernateService.start(c)
    }
}
