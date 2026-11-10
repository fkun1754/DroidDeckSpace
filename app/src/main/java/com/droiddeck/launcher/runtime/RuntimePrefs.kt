package com.droiddeck.launcher.runtime

import android.content.Context

/** User preferences for the active Linux runtime. */
object RuntimePrefs {
    private const val PREFS = "runtime_prefs"
    private const val KEY_RUN_AT_BOOT = "run_at_boot"
    private const val KEY_KEEP_RUNNING = "keep_running"
    private const val KEY_CACHE = "cache_enabled"
    private const val KEY_CACHE_DAYS = "cache_days"

    /** Cache for runtime archives, desktop packages and the Steam/compat seed. */
    fun cacheEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CACHE, true)

    fun setCacheEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CACHE, enabled).apply()
    }

    fun cacheRetentionDays(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CACHE_DAYS, 7)

    /** 0 or a negative value means "keep forever". */
    fun setCacheRetentionDays(context: Context, days: Int) {
        val value = if (days <= 0) 0 else days.coerceIn(1, 90)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CACHE_DAYS, value).apply()
    }

    fun cacheKeepsForever(context: Context): Boolean = cacheRetentionDays(context) <= 0

    fun cacheRetentionMs(context: Context): Long =
        if (cacheKeepsForever(context)) Long.MAX_VALUE
        else cacheRetentionDays(context) * 24L * 60L * 60L * 1000L

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
