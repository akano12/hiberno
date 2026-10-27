package dev.hiberno

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import com.topjohnwu.superuser.Shell

/** Stays alive only to hear SCREEN_OFF/ON; all real work is done by root shell commands. */
class HibernateService : Service() {
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val p = Prefs(c)
            val alarms = c.getSystemService(AlarmManager::class.java)
            if (i.action == Intent.ACTION_SCREEN_ON) {
                alarms.cancel(HibernateReceiver.pending(c, announce = false))
                if (p.doze) Shell.cmd("dumpsys deviceidle unforce").submit()
                return
            }
            if (p.pauseCharging && c.getSystemService(BatteryManager::class.java).isCharging) return
            if (p.doze) Shell.cmd("dumpsys deviceidle force-idle >/dev/null").submit()
            if (!p.enabled) return
            if (p.delayMin == 0) hibernateNow(c)
            // Inexact on purpose: exact alarms need a special permission, and a few minutes' slack is harmless here.
            else alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + p.delayMin * 60_000L,
                HibernateReceiver.pending(c, announce = false),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Background service", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle("Hibernating selected apps on screen off")
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)

        val f = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        registerReceiver(screen, f, RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(screen)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val CHANNEL = "svc"
        private val PKG = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

        fun start(c: Context) {
            c.startForegroundService(Intent(c, HibernateService::class.java))
        }

        /**
         * Force-stops the selected apps (or just [only], e.g. apps just added), records the result,
         * and toasts it when the user asked (button/widget).
         */
        fun hibernateNow(c: Context, announce: Boolean = false, only: Set<String>? = null, onDone: () -> Unit = {}) {
            val p = Prefs(c)
            val targets = only?.let { it intersect p.apps } ?: p.apps
            val script = buildScript(targets, p.keepActive)
            if (script.isEmpty()) return onDone()
            Shell.cmd(script).submit { r ->
                val (stopped, skipped) = parseResult(r.out)
                // No output and a failed exit: root was denied. Keep the previous result rather than recording "0 asleep".
                val failed = !r.isSuccess && stopped.isEmpty() && skipped.isEmpty()
                if (!failed) {
                    if (only == null) {
                        p.lastStopped = stopped
                        p.lastSkipped = skipped
                        p.lastRun = System.currentTimeMillis()
                    } else { // a partial run only updates its own apps and doesn't start a new round
                        p.lastStopped = p.lastStopped - targets + stopped
                        p.lastSkipped = p.lastSkipped - targets + skipped
                    }
                    App.publishHibernated(p.lastStopped, newRound = only == null)
                    HibernateWidget.refreshAll(c)
                }
                if (announce && p.confirm) {
                    val msg = if (failed) "Hibernation failed: root not granted"
                    else "Hibernated ${stopped.size} apps · ${skipped.size} kept awake"
                    Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
                }
                onDone()
            }
        }

        /** Prints "+pkg" for each app stopped and "-pkg" for each one kept awake because it's actively in use. */
        fun buildScript(pkgs: Set<String>, keepActive: Boolean) = buildString {
            for (pkg in pkgs) {
                if (!PKG.matches(pkg)) continue // never pass untrusted text to a root shell
                val stop = "am force-stop $pkg && echo +$pkg"
                if (keepActive) append("if dumpsys activity services $pkg | grep -q isForeground=true; then echo -$pkg; else $stop; fi\n")
                else append("$stop\n")
            }
        }

        fun parseResult(out: List<String>): Pair<Set<String>, Set<String>> =
            out.filter { it.startsWith("+") }.map { it.drop(1) }.toSet() to
                out.filter { it.startsWith("-") }.map { it.drop(1) }.toSet()
    }
}
