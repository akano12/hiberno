package dev.hiberno

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * LSPosed module (System Framework scope). Blocks process starts of hibernated apps for anything
 * but an activity launch, so other apps' broadcasts, service binds, providers and alarms can't wake them.
 */
class Cutoff : XposedModule() {
    private val policy = CutoffPolicy()
    private val prefs: SharedPreferences by lazy { getRemotePreferences(PREFS) }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        var hooked = 0
        for (cls in listOf("com.android.server.am.ProcessList", "com.android.server.am.ActivityManagerService")) {
            try {
                for (m in param.classLoader.loadClass(cls).declaredMethods) {
                    // Only overloads taking ApplicationInfo and returning ProcessRecord: null there means "start failed",
                    // which every caller handles. Boolean overloads are skipped (null would crash on unboxing).
                    if (m.name != "startProcessLocked" || m.returnType.isPrimitive) continue
                    if (ApplicationInfo::class.java !in m.parameterTypes) continue
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(::intercept)
                    hooked++
                }
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "hook $cls failed", t)
            }
        }
        log(Log.INFO, TAG, "wake-up cut-off hooked $hooked methods")
    }

    private fun intercept(chain: XposedInterface.Chain): Any? {
        val args = chain.args
        val info = args.firstNotNullOfOrNull { it as? ApplicationInfo } ?: return chain.proceed()
        val type = hostingType(args.firstOrNull { it != null && it.javaClass.name.endsWith(".HostingRecord") })
        if (policy.shouldBlock(info.packageName, type, hibernated(), stamp())) {
            Log.i(WAKE_TAG, info.packageName) // read back by the app (via root logcat) to count blocked wake-ups
            return null
        }
        return chain.proceed()
    }

    private fun hibernated(): Set<String> =
        runCatching { prefs.getStringSet(KEY_HIBERNATED, emptySet())!! }.getOrDefault(emptySet())

    private fun stamp(): Long = runCatching { prefs.getLong(KEY_STAMP, 0) }.getOrDefault(0)

    private fun hostingType(hosting: Any?): String? =
        hosting?.let { runCatching { it.javaClass.getMethod("getType").invoke(it)?.toString() }.getOrNull() }

    companion object {
        const val PREFS = "cutoff"
        const val KEY_HIBERNATED = "hibernated"
        const val KEY_STAMP = "stamp"
        const val WAKE_TAG = "HibernoCutoff"
    }
}
