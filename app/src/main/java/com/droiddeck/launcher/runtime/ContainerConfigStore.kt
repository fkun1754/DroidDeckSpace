package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.runtime.container.SuShell

/**
 * DroidDeckSpace starts its containers with --reset, which makes DroidSpaces regenerate
 * container.config. Only the options needed to boot a DroidDeck session are enforced there; every
 * other DroidSpaces setting is snapshotted before the start and restored afterwards, so changing
 * them in DroidSpaces is safe ("fixed required options are overwritten at boot, the rest is not").
 */
object ContainerConfigStore {
    /** Options DroidDeckSpace sets itself when booting the container; everything else is preserved. */
    private val ENFORCED = setOf(
        "name", "hostname", "rootfs_path", "custom_init", "bind_mounts",
        "net_mode", "enable_gpu_mode", "uuid", "run_at_boot",
    )

    private fun config(context: Context, container: String) =
        "/data/local/Droidspaces/Containers/" + SuShell.quote(container) + "/container.config"

    fun snapshot(context: Context, container: String): Map<String, String> {
        val result = SuShell.exec("cat " + config(context, container) + " 2>/dev/null", 15_000)
        if (!result.ok()) return emptyMap()
        val values = LinkedHashMap<String, String>()
        result.stdout.lineSequence().forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val i = line.indexOf('=')
            if (i > 0) values[line.substring(0, i)] = line.substring(i + 1)
        }
        return values
    }

    /** Re-applies the non-enforced options after DroidSpaces rewrote the file. */
    fun restore(context: Context, container: String, saved: Map<String, String>) {
        if (saved.isEmpty()) return
        val command = StringBuilder()
        saved.forEach { (key, value) ->
            if (key in ENFORCED || value.contains('\n')) return@forEach
            command.append("sed -i 's|^").append(key).append("=.*|").append(key).append('=').append(value)
                .append("|' ").append(config(context, container)).append("; ")
        }
        if (command.isNotEmpty()) SuShell.exec(command.toString() + "true", 15_000)
    }
}
