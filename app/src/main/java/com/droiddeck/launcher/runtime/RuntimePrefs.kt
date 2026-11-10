package com.droiddeck.launcher.runtime

import android.content.Context

/** User preferences for the active Linux runtime. */
object RuntimePrefs {
    private const val PREFS = "runtime_prefs"
    private const val KEY_RUN_AT_BOOT = "run_at_boot"
    private const val KEY_KEEP_RUNNING = "keep_running"

    fun runAtBoot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RUN_AT_BOOT, false)

    fun setRunAtBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RUN_AT_BOOT, enabled).apply()
    }

    fun keepRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_RUNNING, false)

    fun setKeepRunning(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_RUNNING, enabled).apply()
    }
}
