package dev.hiberno

import android.app.Application
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

const val TAG = "Hiberno"

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) { xposed = service }
            override fun onServiceDied(service: XposedService) { xposed = null }
        })
    }

    companion object {
        // Bound by LSPosed when the module is enabled; null otherwise (root-only mode).
        @Volatile var xposed: XposedService? = null

        val cutoffLabel get() = if (xposed != null) "Active" else "Off"

        /**
         * Tells the system_server hook which apps are hibernated. A new round gets a new stamp, which resets
         * "opened by user"; a partial run keeps it so apps the user opened stay awake.
         */
        fun publishHibernated(pkgs: Set<String>, newRound: Boolean = true) {
            val x = xposed ?: return
            try {
                x.getRemotePreferences(Cutoff.PREFS).edit()
                    .putStringSet(Cutoff.KEY_HIBERNATED, pkgs)
                    .apply { if (newRound) putLong(Cutoff.KEY_STAMP, SystemClock.elapsedRealtime()) }
                    .apply()
            } catch (e: RuntimeException) {
                Log.w(TAG, "LSPosed service unavailable", e)
            }
        }
    }
}
