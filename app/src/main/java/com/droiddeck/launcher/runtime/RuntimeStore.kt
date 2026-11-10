package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.runtime.container.ContainerPaths
import com.droiddeck.launcher.runtime.container.DroidspacesCli
import com.droiddeck.launcher.runtime.container.SuShell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Imported Linux runtimes. A runtime is a DroidSpaces container plus its rootfs; the runtime name and
 * the container name are the same (validated to DroidSpaces' allowed characters), so users never have
 * to keep two names in sync.
 */
object RuntimeStore {
    private const val PREFS = "runtime_store"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_ACTIVE = "active"
    private const val KEY_HIDDEN = "hidden"

    /** DroidSpaces accepts letters, numbers, '.', '_', '-' and spaces. */
    private val NAME = Regex("^[A-Za-z0-9._ -]{1,40}$")

    data class Entry(
        val container: String,
        val label: String,
        val rootfs: String,
        val imported: Boolean,
        val builtin: Boolean = false,
    )

    fun validName(name: String): Boolean = NAME.matches(name.trim())

    fun defaultEntry(context: Context): Entry {
        val name = ContainerPaths.defaultContainerName()
        return Entry(container = name, label = name, rootfs = LinuxRuntime.defaultRootDir(context).path, imported = false, builtin = true)
    }

    fun entries(context: Context): List<Entry> {
        val hidden = hiddenContainers(context)
        val stored = read(context).filter { it.container !in hidden }.toMutableList()
        if (LinuxRuntime.hasRootfsAt(LinuxRuntime.defaultRootDir(context))) {
            val builtin = defaultEntry(context)
            if (stored.none { it.container == builtin.container } && builtin.container !in hidden) stored.add(0, builtin)
        }
        return stored
    }

    /** Names detached from DroidDeckSpace but still present in DroidSpaces. */
    fun hiddenContainers(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_HIDDEN, null) ?: return emptySet()
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Detach a runtime from DroidDeckSpace without touching its DroidSpaces container/rootfs. */
    fun hide(context: Context, container: String) {
        val hidden = hiddenContainers(context).toMutableSet()
        hidden.add(container)
        val array = org.json.JSONArray()
        hidden.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_HIDDEN, array.toString()).apply()
        if (prefs(context).getString(KEY_ACTIVE, null) == container) prefs(context).edit().remove(KEY_ACTIVE).apply()
    }

    fun unhide(context: Context, container: String) {
        val hidden = hiddenContainers(context).toMutableSet()
        hidden.remove(container)
        val array = org.json.JSONArray()
        hidden.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_HIDDEN, array.toString()).apply()
    }

    fun active(context: Context): Entry? {
        val id = prefs(context).getString(KEY_ACTIVE, null)
        val all = entries(context)
        val selected = id?.let { key -> all.firstOrNull { it.container == key } }
        // A runtime whose rootfs was removed/incomplete must never be used for a session: it would
        // trigger a runtime install on every start. Fall back to the built-in runtime when it is valid.
        if (selected != null && LinuxRuntime.hasRootfsAt(File(selected.rootfs))) return selected
        val builtin = all.firstOrNull { it.builtin && LinuxRuntime.hasRootfsAt(File(it.rootfs)) }
        if (builtin != null) return builtin
        return selected ?: all.firstOrNull()
    }

    fun activeRootfs(context: Context): File =
        active(context)?.let { File(it.rootfs) } ?: LinuxRuntime.defaultRootDir(context)

    fun setActive(context: Context, container: String) {
        prefs(context).edit().putString(KEY_ACTIVE, container).apply()
    }

    fun add(context: Context, entry: Entry, activate: Boolean) {
        unhide(context, entry.container)
        val list = read(context).filter { it.container != entry.container }.toMutableList()
        list.add(entry)
        write(context, list)
        if (activate) setActive(context, entry.container)
    }

    /**
     * Renames a runtime and its DroidSpaces container together. Returns an error message when the
     * name is invalid/duplicated or the container cannot be renamed (for example while running).
     */
    fun rename(context: Context, container: String, newName: String): String? {
        val name = newName.trim()
        if (!validName(name)) return "名称只能包含字母、数字、点、下划线、短横线和空格"
        if (name == container) return null
        if (entries(context).any { it.container == name }) return "名称已存在"
        if (RuntimeDiscovery.list(context, true).any { it.name == name }) return "DroidSpaces 中已有同名容器"
        if (DroidspacesCli.RUNNING == DroidspacesCli.state(container)) return "运行时正在运行，请先停止会话再重命名"
        val config = q(ContainerPaths.WORKSPACE + "/Containers/" + name + "/container.config")
        val command = "if [ -d " + q(ContainerPaths.WORKSPACE + "/Containers/" + container) + " ]; then " +
            "mv " + q(ContainerPaths.WORKSPACE + "/Containers/" + container) + " " +
            q(ContainerPaths.WORKSPACE + "/Containers/" + name) + "; " +
            "sed -i 's/^name=.*/name=" + name + "/; s/^hostname=.*/hostname=" + name + "/' " +
            config + " 2>/dev/null; fi; true"
        val result = SuShell.exec(command, 20_000)
        if (!result.ok()) return "重命名 DroidSpaces 容器失败：" + result.combined()
        val list = read(context).map { if (it.container == container) it.copy(container = name, label = name) else it }
        write(context, list)
        if (prefs(context).getString(KEY_ACTIVE, null) == container) setActive(context, name)
        DroidspacesCli.forget()
        return null
    }

    fun remove(context: Context, container: String) {
        val list = entries(context).filter { it.container != container }
        write(context, list)
        if (prefs(context).getString(KEY_ACTIVE, null) == container) {
            prefs(context).edit().remove(KEY_ACTIVE).apply()
        }
    }

    fun nextName(context: Context, taken: Collection<String>): String {
        val base = "Runtime"
        val names = entries(context).map { it.label }.toMutableSet()
        names.addAll(taken)
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }

    fun imported(context: Context, container: String): Boolean = entries(context).any { it.container == container }

    private fun q(value: String) = SuShell.quote(value)

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
                // Unify the name: the runtime label is always the DroidSpaces container name.
                Entry(container, container, rootfs, o.optBoolean("imported"), o.optBoolean("builtin"))
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
