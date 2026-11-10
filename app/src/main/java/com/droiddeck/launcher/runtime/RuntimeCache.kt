package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.runtime.container.SuShell
import java.io.File
import java.util.Locale

/**
 * Runtime-related caches shared by all runtimes: downloaded runtime archives, desktop/package
 * downloads and the Steam client/compat seed. Shown on the Storage page and cleaned automatically
 * after the user-selected retention period (or immediately when caching is disabled).
 */
object RuntimeCache {
    enum class Kind { RUNTIME, DESKTOP, SEED }

    data class Item(val kind: Kind, val file: File, val bytes: Long)

    fun items(context: Context): List<Item> {
        val result = ArrayList<Item>()
        context.cacheDir.listFiles()?.forEach { f ->
            if (!f.isFile || f.name.endsWith(".resume.properties")) return@forEach
            val name = f.name
            val runtime = name.startsWith("linuxfs") && name.endsWith(".tar.zst")
            val desktop = name.startsWith("pkg-") && (name.endsWith(".download") || name.endsWith(".tar.zst"))
            if (runtime || desktop) result.add(Item(if (runtime) Kind.RUNTIME else Kind.DESKTOP, f, f.length()))
        }
        RuntimeSeedCache.dir(context).takeIf { it.isDirectory }?.let {
            result.add(Item(Kind.SEED, it, sizeOf(it)))
        }
        return result
    }

    fun totalBytes(context: Context): Long = items(context).sumOf { it.bytes }

    fun bytes(context: Context, kind: Kind): Long = items(context).filter { it.kind == kind }.sumOf { it.bytes }

    /** Deletes every cached runtime/desktop/seed file. */
    fun clear(context: Context) {
        items(context).forEach {
            if (it.kind == Kind.SEED) {
                runCatching { SuShell.exec("rm -rf " + SuShell.quote(it.file.absolutePath), 120_000) }
                it.file.deleteRecursively()
            } else {
                it.file.delete()
                File(it.file.path + ".resume.properties").delete()
            }
        }
    }

    /** Enforces the user's keep/retention settings; call from app start and after installs. */
    fun cleanExpired(context: Context) {
        if (!RuntimePrefs.cacheEnabled(context)) {
            clear(context)
            return
        }
        val cutoff = System.currentTimeMillis() - RuntimePrefs.cacheRetentionMs(context)
        items(context).filter { it.kind != Kind.SEED && it.file.lastModified() < cutoff }
            .filterNot { it.kind == Kind.DESKTOP && File(it.file.path + ".resume.properties").exists() }
            .forEach { it.file.delete() }
        RuntimeSeedCache.clean(context)
    }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f MB", bytes.toDouble() / (1L shl 20))
        bytes > 0 -> String.format(Locale.ROOT, "%.0f KB", bytes.toDouble() / 1024)
        else -> "0 MB"
    }

    private fun sizeOf(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
