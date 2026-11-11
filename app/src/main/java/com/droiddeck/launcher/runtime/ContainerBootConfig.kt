package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.runtime.container.SuShell

/**
 * The "run at boot" switch maps to the DroidSpaces container's own run_at_boot option, so the
 * container is started by DroidSpaces' boot module, not by a DroidDeck broadcast receiver.
 */
object ContainerBootConfig {
    private fun config(context: Context, container: String) =
        "/data/local/Droidspaces/Containers/" + SuShell.quote(container) + "/container.config"

    /** DroidSpaces regenerates container.config when the app starts a container with --reset, so the
     * user's boot preference is kept in RuntimePrefs and written back after each start. */
    fun restore(context: Context, container: String) {
        if (!RuntimePrefs.containerBoot(context, container)) return
        setRunAtBoot(context, container, true)
    }

    fun runAtBoot(context: Context, container: String): Boolean {
        if (RuntimePrefs.containerBoot(context, container)) return true
        if (container.isBlank()) return false
        val result = SuShell.exec(
            "grep -E '^run_at_boot=' " + config(context, container) + " 2>/dev/null | tail -1",
            10_000,
        )
        return result.stdout.contains("run_at_boot=1")
    }

    /** Returns null on success, or an error message. */
    fun setRunAtBoot(context: Context, container: String, enabled: Boolean): String? {
        if (container.isBlank()) return "没有可用的运行时"
        val path = config(context, container)
        val value = if (enabled) "1" else "0"
        val command = "f=" + path + "; " +
            "[ -f \"\$f\" ] || exit 2; " +
            "sed -i 's/^run_at_boot=.*/run_at_boot=" + value + "/' \"\$f\"; " +
            "grep -q '^run_at_boot=' \"\$f\" || echo 'run_at_boot=" + value + "' >> \"\$f\"; " +
            "grep -E '^run_at_boot=' \"\$f\" | tail -1"
        val result = SuShell.exec(command, 15_000)
        return if (result.ok()) null else "设置 DroidSpaces 开机自启失败：" + result.combined()
    }
}
