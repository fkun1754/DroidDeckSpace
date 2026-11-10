package com.droiddeck.launcher.runtime

import android.content.Context

/**
 * Result of the environment check (root + DroidSpaces CLI). It is detected once during the first
 * configuration and then persisted, so later app launches read the stored result instead of running
 * root probes again. The explicit "Re-check" button refreshes and persists it.
 */
object RuntimeStatus {
    private const val PREFS = "runtime_status"
    private const val KEY_CHECKED = "checked"
    private const val KEY_ROOT = "root"
    private const val KEY_DROIDSPACES = "droidspaces"

    @Volatile var checked: Boolean = false
        private set
    @Volatile var root: Boolean = false
        private set
    @Volatile var droidspaces: Boolean = false
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        checked = prefs.getBoolean(KEY_CHECKED, false)
        root = prefs.getBoolean(KEY_ROOT, false)
        droidspaces = prefs.getBoolean(KEY_DROIDSPACES, false)
    }

    fun publish(context: Context, rootOk: Boolean, droidspacesOk: Boolean) {
        root = rootOk
        droidspaces = droidspacesOk
        checked = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CHECKED, true)
            .putBoolean(KEY_ROOT, rootOk)
            .putBoolean(KEY_DROIDSPACES, droidspacesOk)
            .apply()
    }
}
