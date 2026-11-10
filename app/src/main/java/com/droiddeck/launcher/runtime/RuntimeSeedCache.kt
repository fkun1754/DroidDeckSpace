package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.container.SuShell
import java.io.File

/**
 * Small seed cache that lets a newly created runtime reuse the already downloaded Steam client
 * packages and Steam Linux Runtime / Proton compatibility layers instead of downloading them again.
 * Only the client package cache and the compatibility layers are copied, not game data.
 */
object RuntimeSeedCache {
    private const val TAG = "RuntimeSeedCache"
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

    fun dir(context: Context): File = File(context.filesDir, "runtime-seed")

    /** Refresh the seed from a runtime root that already has the Steam client / compat layers. */
    fun capture(context: Context, rootfs: File) {
        val seed = dir(context)
        val base = rootfs.absolutePath
        val steam = "$base/root/.local/share/Steam"
        if (!File(steam).isDirectory) return
        val common = "$seed.absolutePath/root/.local/share/Steam/steamapps/common"
        val command = "rm -rf " + q(seed.absolutePath) + "; mkdir -p " + q(common) + "; " +
            "cp -a " + q("$steam/package") + " " + q("$seed.absolutePath/root/.local/share/Steam/") + " 2>/dev/null; " +
            "cp -a " + q("$steam/steamapps/common") + "/SteamLinuxRuntime* " + q(common) + "/ 2>/dev/null; " +
            "cp -a " + q("$steam/steamapps/common") + "/Proton* " + q(common) + "/ 2>/dev/null; " +
            "touch " + q(seed.absolutePath) + "; true"
        val result = SuShell.exec(command, 120_000)
        if (!result.ok()) Log.w(TAG, "capture failed: " + result.combined())
    }

    /** Seed a newly installed runtime from the cache (no-op when there is no cache). */
    fun apply(context: Context, rootfs: File) {
        val seed = dir(context)
        if (!File(seed, "root").isDirectory) return
        val command = "mkdir -p " + q(rootfs.absolutePath + "/root") + " && cp -a " +
            q(seed.absolutePath + "/root/.") + " " + q(rootfs.absolutePath + "/root/") + "; true"
        val result = SuShell.exec(command, 120_000)
        if (!result.ok()) Log.w(TAG, "apply failed: " + result.combined())
    }

    fun clean(context: Context) {
        val seed = dir(context)
        if (seed.isDirectory && System.currentTimeMillis() - seed.lastModified() > RETENTION_MS) {
            Log.i(TAG, "removing expired runtime seed cache")
            seed.deleteRecursively()
        }
    }

    private fun q(value: String): String = SuShell.quote(value)
}
