package com.droiddeck.launcher.runtime

import android.content.Context
import android.os.Process
import android.util.Log
import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.runtime.container.ContainerControl
import com.droiddeck.launcher.runtime.container.ContainerEnv
import com.droiddeck.launcher.runtime.container.ContainerPaths
import com.droiddeck.launcher.runtime.container.ContainerRuntime
import com.droiddeck.launcher.runtime.container.DroidspacesCli
import com.droiddeck.launcher.session.SessionFiles
import com.droiddeck.launcher.session.SessionPaths
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object GuestCommand {
    private const val TAG = "GuestCommand"
    private const val ENV_TOOL = "/usr/bin/env"
    private const val PID_MARKER = "DROIDDECK_GUEST_PID"

    fun run(context: Context, argv: List<String>, fakeRoot: Boolean = false, logName: String? = null,
            linkDir: File? = null, extraBinds: List<String>? = null, fastPath: Boolean = false, cancelled: AtomicBoolean? = null,
            onLine: (String) -> Unit): Int {
        val log = logName?.let {
            try {
                File(File(LinuxRuntime.logDir(context), SessionPaths.TOOLS_DIR).apply { mkdirs() }, "$it.log").printWriter()
            } catch (e: Exception) { null }
        }
        log?.println("== ${java.util.Date()} ${argv.joinToString(" ")}")
        try {
            return runLogged(context, argv, fakeRoot, cancelled) { line -> log?.println(line); log?.flush(); onLine(line) }
        } finally {
            log?.close()
        }
    }

    private fun runLogged(context: Context, argv: List<String>, fakeRoot: Boolean,
                          cancelled: AtomicBoolean?, onLine: (String) -> Unit): Int {
        LinuxRuntime.writeAccounts(context)
        SessionFiles.stage(context, LinuxRuntime.rootDir(context))
        val exec = withCleanEnvironment(translateGuestArgs(context, argv), env = defaultEnvironment(context))
        val script = "echo $PID_MARKER:\$\$; exec " + exec.joinToString(" ") { LinuxRuntime.shellQuote(it) }
        val guestPid = AtomicInteger(-1)
        // Cancelling only ends the host-side wait: the root `su`/droidspaces client cannot be
        // signalled by the app uid, so the guest command tree is killed through the ctl script.
        val killer = cancelled?.let { flag ->
            Thread({
                try {
                    while (!flag.get()) Thread.sleep(100)
                    val deadline = System.currentTimeMillis() + 3_000
                    var pid = guestPid.get()
                    while (pid <= 1 && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50)
                        pid = guestPid.get()
                    }
                    if (pid > 1) {
                        Log.i(TAG, "cancelled: killing guest command $pid")
                        killGuestProcess(context, pid)
                    }
                } catch (e: InterruptedException) {
                }
            }, "guest-cancel").apply { isDaemon = true; start() }
        }
        return try {
            ensureContainer(context)
            val uid = if (fakeRoot) null else Process.myUid()
            val result = DroidspacesCli.execUnlimited(context, ContainerPaths.CONTAINER_NAME, uid,
                listOf("/bin/sh", "-c", script), { line ->
                    if (guestPid.get() <= 0 && line.startsWith("$PID_MARKER:")) {
                        line.removePrefix("$PID_MARKER:").trim().toIntOrNull()?.takeIf { it > 1 }?.let { guestPid.set(it) }
                    } else {
                        onLine(line)
                    }
                }, cancelled)
            result.exitCode
        } catch (e: Exception) {
            Log.e(TAG, "guest command failed: ${argv.joinToString(" ")}", e)
            onLine("droiddeck: ${e.message ?: e.javaClass.simpleName}")
            -1
        } finally {
            killer?.interrupt()
        }
    }

    /**
     * Callers built their arguments while proot kept host paths identical inside the guest; the
     * container has its own view, so host paths that point into app data are translated here.
     */
    private fun translateGuestArgs(context: Context, argv: List<String>): List<String> {
        val prefixes = listOf(
            context.filesDir.path,
            context.noBackupFilesDir.path,
            context.cacheDir.path,
            android.os.Environment.getExternalStorageDirectory().path,
        )
        fun translate(value: String): String {
            if (prefixes.none { value == it || value.startsWith("$it/") }) return value
            return runCatching { ContainerEnv.guestPath(context, value) }.getOrDefault(value)
        }
        return argv.map { arg ->
            val separator = arg.indexOf('=')
            if (separator > 0 && !arg.startsWith("/")) {
                arg.substring(0, separator + 1) + translate(arg.substring(separator + 1))
            } else {
                translate(arg)
            }
        }
    }

    /** The clean environment one-shot guest commands used to get from proot's `env -i`. */
    fun withCleanEnvironment(argv: List<String>, envTool: String = ENV_TOOL,
                             env: Map<String, String> = emptyMap()): List<String> {
        val out = ArrayList<String>(argv.size + env.size + 2)
        out.add(envTool)
        out.add("-i")
        env.forEach { (key, value) -> out.add("$key=$value") }
        out.addAll(argv)
        return out
    }

    fun defaultEnvironment(context: Context): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["HOME"] = "/root"
        env["USER"] = "root"
        env["LANG"] = "C.UTF-8"
        env["PATH"] = LinuxRuntime.GUEST_PATH
        env["XDG_RUNTIME_DIR"] = LinuxRuntime.GUEST_RUNTIME_DIR
        env["XDG_DATA_HOME"] = "/root/.local/share"
        env["FLATPAK_BWRAP"] = FlatpakManager.BWRAP
        env["DROIDDECK_BWRAP_SOCKET"] = BwrapSpawner.socket(context)
        env["XDG_DATA_DIRS"] = "/root/.local/share/flatpak/exports/share:/usr/local/share:/usr/share"
        DownloadSource.proxyHostPort(context).takeIf { it.isNotEmpty() }?.let { hostPort ->
            for (key in listOf("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY")) env[key] = "http://$hostPort"
            for (key in listOf("no_proxy", "NO_PROXY")) env[key] = "localhost,127.0.0.1,::1"
        }
        return env
    }

    /** Runs [argv] with [env] in the container, applying [cwd] first when set. */
    fun containerArgv(envTool: String, env: Map<String, String>, cwd: String?, argv: List<String>): List<String> {
        val exec = withCleanEnvironment(argv, envTool, env)
        if (cwd == null) return exec
        val line = "cd " + LinuxRuntime.shellQuote(cwd) + " && exec " +
            exec.joinToString(" ") { LinuxRuntime.shellQuote(it) }
        return listOf("/bin/sh", "-c", line)
    }

    /** `["su", "-c", "<droidspaces ... run -- ...>"]` for callers that spawn a host process. */
    fun hostProcessCommand(argv: List<String>, stdin: Boolean = false): List<String> {
        val cli = ArrayList<String>()
        cli.add(ContainerPaths.CLI)
        cli.add("-n")
        cli.add(ContainerPaths.CONTAINER_NAME)
        if (stdin) cli.add("--stdin")
        cli.add("run")
        cli.add("--")
        cli.addAll(argv)
        return listOf(LinuxRuntime.suBinary(), "-c", cli.joinToString(" ") { LinuxRuntime.shellQuote(it) })
    }

    /** Shell for the in-app terminal: the container's login environment plus the session's env file. */
    fun terminalCommand(): List<String> {
        val env = ContainerPaths.GUEST_SESSION_ENV
        val pidFile = ContainerPaths.GUEST_APP + "/session/terminal.pid"
        // The app cannot signal the root su wrapper, so the shell records its guest pid here and
        // SessionService stops the shell through the container control script when the panel closes.
        val line = "echo \$\$ > $pidFile; set -a; [ -f $env ] && . $env; exec /bin/bash -i"
        return hostProcessCommand(listOf("/bin/bash", "-c", line), stdin = true)
    }

    /** Kills a guest process through the ctl script and confirms it disappeared from `ps`. */
    fun killGuestProcess(context: Context, pid: Int): Boolean {
        if (pid <= 1) return true
        ContainerControl.killQuietly(context, pid)
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val alive = runCatching { ContainerControl.ps(context) }.getOrNull()?.any { it.pid == pid } ?: false
            if (!alive) return true
            try { Thread.sleep(100) } catch (e: InterruptedException) { return false }
        }
        Log.w(TAG, "guest process $pid is still present after kill")
        return false
    }

    fun ensureContainer(context: Context) {
        if (ContainerRuntime.isRunning(context)) return
        // A maintenance container can be the first one ever started, and droidspaces checks the
        // init path inside the rootfs: the staged session scripts must be there first.
        SessionFiles.stage(context, LinuxRuntime.rootDir(context))
        ContainerRuntime.startMaintenance(context)
    }
}
