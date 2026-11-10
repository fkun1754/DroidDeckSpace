package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.runtime.container.SuShell
import java.io.File

/** Lists DroidSpaces containers and checks whether one can boot the DroidDeck session. */
object RuntimeDiscovery {
    data class Container(val name: String, val rootfs: String)

    private const val CACHE_MS = 30_000L
    private var cached: List<Container>? = null
    private var cachedAt = 0L

    fun list(context: Context, force: Boolean = false): List<Container> {
        val now = System.currentTimeMillis()
        cached?.let { if (!force && now - cachedAt < CACHE_MS) return it }
        val result = SuShell.exec(
            "for c in /data/local/Droidspaces/Containers/*/container.config; do " +
                "[ -f \"\$c\" ] || continue; echo __DS__\$c; " +
                "grep -E '^(name|rootfs_path)=' \"\$c\" 2>/dev/null; done",
            20_000,
        )
        if (!result.ok()) return emptyList()
        val containers = ArrayList<Container>()
        var name: String? = null
        var rootfs: String? = null
        fun flush() {
            if (!name.isNullOrBlank() && !rootfs.isNullOrBlank()) containers.add(Container(name!!, rootfs!!))
            name = null; rootfs = null
        }
        result.stdout.lineSequence().forEach { line ->
            if (line.startsWith("__DS__")) {
                flush()
            } else if (line.startsWith("name=")) {
                name = line.removePrefix("name=").trim()
            } else if (line.startsWith("rootfs_path=")) {
                rootfs = line.removePrefix("rootfs_path=").trim()
            }
        }
        flush()
        val resultList = containers.filter { it.name.isNotBlank() && File(it.rootfs).isDirectory }
        cached = resultList
        cachedAt = now
        return resultList
    }

    /** null when the container can be imported, otherwise a human-readable reason. */
    fun compatibility(context: Context, container: Container): String? {
        val root = File(container.rootfs)
        if (!root.isDirectory) return "根目录不存在"
        val osRelease = File(root, "etc/os-release")
        if (!osRelease.isFile) return "缺少 /etc/os-release，不是完整 Linux rootfs"
        val os = runCatching { osRelease.readText() }.getOrDefault("")
        val id = Regex("(?m)^ID=(\\S+)").find(os)?.groupValues?.get(1)?.trim('"')
        if (id !in setOf("ubuntu", "debian")) return "不支持的系统：${id ?: "未知"}（仅支持 Ubuntu/Debian）"
        if (!File(root, "bin/bash").isFile && !File(root, "usr/bin/bash").isFile) return "缺少 bash"
        if (!File(root, "usr/bin/gamescope").isFile && !File(root, "usr/games/gamescope").isFile) {
            return "缺少 gamescope（不是 DroidDeck 兼容运行时）"
        }
        val probe = File(root, ".droiddeckspace-write-test")
        return try {
            if (!probe.createNewFile()) "容器 rootfs 不可写" else { probe.delete(); null }
        } catch (e: Exception) {
            "容器 rootfs 不可写：${e.message}"
        }
    }
}
