package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.runtime.container.ContainerPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Imported Linux runtimes. A runtime is a DroidSpaces container plus the rootfs it boots: the
 * built-in runtime lives at files/linuxfs, imported ones keep their DroidSpaces container and
 * rootfs path. Switching only changes which entry sessions use; imported containers are not
 * re-validated on every switch.
 */
object RuntimeStore {
    private const val PREFS = "runtime_store"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_ACTIVE = "active"

    data class Entry(
        val container: String,
        val label: String,
        val rootfs: String,
        val imported: Boolean,
        val builtin: Boolean = false,
    )

    fun defaultEntry(context: Context): Entry = Entry(
        container = ContainerPaths.defaultContainerName(),
        label = "DroidDeckSpace Runtime",
        rootfs = LinuxRuntime.defaultRootDir(context).path,
        imported = false,
        builtin = true,
    )

    fun entries(context: Context): List<Entry> {
        val stored = read(context)
        if (stored.isNotEmpty()) return stored
        return if (LinuxRuntime.hasRootfsAt(LinuxRuntime.defaultRootDir(context))) listOf(defaultEntry(context)) else emptyList()
    }

    fun active(context: Context): Entry? {
        val id = prefs(context).getString(KEY_ACTIVE, null)
        val entries = entries(context)
        if (id != null) entries.firstOrNull { it.container == id }?.let { return it }
        return entries.firstOrNull { it.builtin }
    }

    fun activeRootfs(context: Context): File =
        active(context)?.let { File(it.rootfs) } ?: LinuxRuntime.defaultRootDir(context)

    fun setActive(context: Context, container: String) {
        prefs(context).edit().putString(KEY_ACTIVE, container).apply()
    }

    fun add(context: Context, entry: Entry, activate: Boolean) {
        val list = entries(context).filter { it.container != entry.container }.toMutableList()
        list.add(entry)
        write(context, list)
        if (activate) setActive(context, entry.container)
    }

    fun remove(context: Context, container: String) {
        val list = entries(context).filter { it.container != container }
        write(context, list)
        if (prefs(context).getString(KEY_ACTIVE, null) == container) {
            prefs(context).edit().remove(KEY_ACTIVE).apply()
        }
    }

    fun nextLabel(context: Context, taken: Collection<String>): String {
        val base = "DroidDeckSpace Runtime"
        val names = entries(context).map { it.label }.toMutableSet()
        names.addAll(taken)
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }

    fun uniqueContainer(context: Context, taken: Collection<String>): String {
        val base = ContainerPaths.defaultContainerName() + "."
        var n = 1
        while ((base + n) in taken) n++
        return base + n
    }

    fun imported(context: Context, container: String): Boolean = entries(context).any { it.container == container }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val container = o.optString("container")
                val rootfs = o.optString("rootfs")
                if (container.isBlank() || rootfs.isBlank()) return@mapNotNull null
                Entry(container, o.optString("label", container), rootfs, o.optBoolean("imported"), o.optBoolean("builtin"))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(JSONObject()
                .put("container", it.container)
                .put("label", it.label)
                .put("rootfs", it.rootfs)
                .put("imported", it.imported)
                .put("builtin", it.builtin))
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
