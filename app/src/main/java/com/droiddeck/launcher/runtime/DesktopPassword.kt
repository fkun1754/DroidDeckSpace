package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.runtime.container.ContainerRuntime

/** Sets the desktop user's password and sudo membership inside the active runtime. */
object DesktopPassword {
    private const val PREFS = "desktop_password"
    private const val KEY_SET = "set"
    private const val KEY_PROMPTED = "prompted"

    fun configured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SET, false)

    fun prompted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    fun set(context: Context, password: String): String? {
        if (password.length < 6) return "密码至少需要 6 位"
        if (!ContainerRuntime.isAvailable(context)) return "运行时不可用"
        var started = false
        try {
            if (!ContainerRuntime.isRunning(context)) {
                ContainerRuntime.startMaintenance(context)
                started = true
            }
            val escaped = password.replace("'", "'\\''")
            val script = "grep -q '^droiddeck:' /etc/passwd || " +
                "echo 'droiddeck:x:10490:10490::/home/droiddeck:/bin/bash' >> /etc/passwd; " +
                "echo 'droiddeck:$escaped' | chpasswd || exit 1; " +
                "command -v sudo >/dev/null || apt-get install -y -qq sudo >/dev/null 2>&1 || true; " +
                "groupadd -f sudo 2>/dev/null || true; " +
                "usermod -aG sudo droiddeck 2>/dev/null || true; " +
                "if [ -x /usr/bin/sudo ]; then echo 'droiddeck ALL=(ALL:ALL) ALL' > /etc/sudoers.d/90-droiddeck; " +
                "chmod 0440 /etc/sudoers.d/90-droiddeck; fi; " +
                "passwd -S droiddeck 2>/dev/null | head -1; " +
                "grep -q '^droiddeck:' /etc/shadow || exit 3"
            val result = ContainerRuntime.exec(context, listOf("/bin/sh", "-c", script), 180_000)
            if (!result.ok()) return "设置密码失败：" + result.combined()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SET, true).apply()
            return null
        } catch (e: Exception) {
            return "设置密码失败：" + (e.message ?: e.toString())
        } finally {
            if (started && !RuntimePrefs.keepRunning(context)) {
                runCatching { ContainerRuntime.stop(context) }
            }
        }
    }
}
