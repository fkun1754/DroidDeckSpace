package com.droiddeck.launcher.runtime.container;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Orchestrates one DroidSpaces container per session (prepare -> start -> awaitReady -> stop). */
public final class ContainerRuntime {
    private static final String TAG = "DroidDeckContainer";
    public static final int READY_TIMEOUT_MS = 90_000;
    public static final int READY_MARKER_GRACE_MS = 20_000;
    /** A stop waits for droidspaces' own SIGTERM/SIGKILL dance plus process teardown. */
    public static final int STOP_TIMEOUT_MS = 30_000;
    private static final int READY_POLL_MS = 500;
    private static final int STATE_CONFIRM_ATTEMPTS = 3;
    private static final int STATE_CONFIRM_DELAY_MS = 300;
    private static final Object START_LOCK = new Object();

    private static volatile Boolean available;

    private ContainerRuntime() {}

    /** Last known availability (null until the warm-up probe has run); the UI must not block on su. */
    public static Boolean cachedAvailable() {
        return available;
    }

    public static boolean isAvailable(Context context) {
        SuShell.init(context);
        Boolean known = available;
        if (known != null) return known;
        synchronized (START_LOCK) {
            if (available != null) return available;
            boolean root = SuShell.isRootAvailable(context);
            boolean cli = root && DroidspacesCli.installed();
            available = cli;
            Log.i(TAG, "DroidSpaces runtime available=" + cli + " (root=" + root + ", cli=" + cli + ")");
            return cli;
        }
    }

    /**
     * Removes the DroidSpaces container records this app owns (runtime uninstall). DroidSpaces has no
     * CLI delete command, so this is the host-side equivalent of its "Uninstall Container" action:
     * stop the container, then drop its registry entry, logs and pidfile. Legacy names from earlier
     * package ids are cleaned too, because re-installing under a new id must not leave orphaned
     * containers that point at a deleted rootfs.
     */
    /** Stop and unregister one DroidSpaces container (runtime removal "with container"). */
    public static void removeContainer(Context context, String name) {
        try {
            if (DroidspacesCli.installed()) DroidspacesCli.stop(name);
        } catch (Exception e) {
            Log.w(TAG, "could not stop container " + name + ": " + e);
        }
        String quoted = SuShell.quote(name);
        ExecResult result = SuShell.exec(
                "rm -rf " + ContainerPaths.WORKSPACE + "/Containers/" + quoted + " "
                        + ContainerPaths.WORKSPACE + "/Logs/" + quoted + " "
                        + ContainerPaths.WORKSPACE + "/Pids/" + quoted + ".pid; true",
                20_000);
        if (!result.ok()) Log.w(TAG, "container removal exited " + result.exitCode + ": " + result.combined());
        DroidspacesCli.forget();
    }

    public static void removeContainers(Context context) {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(ContainerPaths.containerName(context));
        names.add("droiddeck.com.droiddeck.launcher.droidspace");
        names.add("droiddeck.com.droiddeck.launcher");
        names.add(ContainerPaths.LEGACY_CONTAINER_NAME);
        StringBuilder command = new StringBuilder("for n in ");
        for (String name : names) command.append(SuShell.quote(name)).append(' ');
        command.append("; do if [ -d ").append(ContainerPaths.WORKSPACE).append("/Containers/$n ]; then ")
                .append(ContainerPaths.CLI).append(" -n \"$n\" stop >/dev/null 2>&1; fi; ")
                .append("rm -rf ").append(ContainerPaths.WORKSPACE).append("/Containers/$n ")
                .append(ContainerPaths.WORKSPACE).append("/Logs/$n ")
                .append(ContainerPaths.WORKSPACE).append("/Pids/$n.pid; done; ")
                .append("for d in ").append(ContainerPaths.WORKSPACE).append("/Containers/droiddeck.com.droiddeck.* ")
                .append(ContainerPaths.WORKSPACE).append("/Logs/droiddeck.com.droiddeck.*; do ")
                .append("[ -e \"$d\" ] && rm -rf \"$d\"; done; true");
        ExecResult result = SuShell.exec(command.toString(), 30_000);
        if (!result.ok()) Log.w(TAG, "container cleanup exited " + result.exitCode + ": " + result.combined());
        forget();
        DroidspacesCli.forget();
    }

    public static void forget() {
        available = null;
        SuShell.forget();
    }

    public static String state(Context context) {
        return DroidspacesCli.state(ContainerPaths.containerName(context));
    }

    public static boolean isRunning(Context context) {
        return DroidspacesCli.RUNNING.equals(state(context));
    }

    public static void prepare(Context context, SessionSpec session) {
        File rootfs = ContainerPaths.rootfs(context);
        if (!rootfs.isDirectory()) {
            throw new IllegalStateException("runtime rootfs is missing: " + rootfs);
        }
        File init = new File(rootfs, ContainerPaths.GUEST_INIT.substring(1));
        if (!init.isFile()) {
            throw new IllegalStateException("runtime image does not ship " + ContainerPaths.GUEST_INIT
                    + " (expected at " + init + "); update the runtime image");
        }
        try {
            ContainerEnv.prepareSession(context, session);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write container session files: " + e.getMessage(), e);
        }
    }

    public static ContainerSession start(Context context, SessionSpec session) {
        if (!isAvailable(context)) throw new IllegalStateException(unavailableMessage(context));
        SessionSpec spec = session == null
                ? SessionSpec.builder().mode(SessionSpec.MODE_SERVICE).build() : session;
        synchronized (START_LOCK) {
            prepare(context, spec);
            List<String> binds = ContainerEnv.binds(context, spec);
            String name = ContainerPaths.containerName(context);
            Log.i(TAG, "starting container '" + name + "': mode=" + spec.mode() + ", "
                    + binds.size() + " binds");
            String cleanupFailure = "";
            if (DroidspacesCli.RUNNING.equals(DroidspacesCli.state(name))) {
                Log.w(TAG, "container '" + name + "' is still running; stopping it before start");
                try {
                    stopContainer(context, name);
                } catch (RuntimeException e) {
                    cleanupFailure = "stopping the previous container failed:\n" + e.getMessage() + "\n";
                    Log.w(TAG, "previous container could not be stopped", e);
                }
            }
            cleanStaleContainerConfig(context, name);
            DroidspacesCli.ContainerSpec cliSpec = DroidspacesCli.ContainerSpec
                    .builder(name, ContainerPaths.rootfs(context))
                    .init(ContainerPaths.GUEST_INIT)
                    .binds(binds)
                    .build();
            ExecResult result = DroidspacesCli.start(cliSpec);
            if (!result.ok() && looksLikeNameConflict(result)) {
                Log.w(TAG, "start reported a container-name conflict; retrying after stop");
                stopContainer(context, name);
                result = DroidspacesCli.start(cliSpec);
            }
            if (!result.ok()) {
                throw new IllegalStateException("cannot start container '" + name + "'\n"
                        + cleanupFailure + result.describe() + "\n" + diagnostics(context, name));
            }
            try {
                awaitReady(context, name);
            } catch (RuntimeException failure) {
                try {
                    DroidspacesCli.stop(name);
                } catch (RuntimeException ignored) {
                    // Diagnostics are already in the failure.
                }
                throw failure;
            }
            return new ContainerSession(name, spec.sessionId());
        }
    }

    public static void awaitReady(Context context) {
        awaitReady(context, ContainerPaths.containerName(context));
    }

    public static void awaitReady(Context context, String name) {
        long started = System.currentTimeMillis();
        long deadline = started + READY_TIMEOUT_MS;
        // The init writes this marker into the app data dir before it execs the session, so the
        // app can poll it with a plain File check: one root command per 500ms dominated startup.
        File readyMarker = new File(ContainerPaths.waylandDir(context), "ready");
        String lastState = null;
        StringBuilder lastError = new StringBuilder();
        long lastStateCheck = 0;
        boolean execReady = false;
        while (System.currentTimeMillis() < deadline) {
            if (readyMarker.isFile()) {
                ExecResult probe = testExecutable(context, name, ContainerPaths.GUEST_SESSION);
                if (probe.ok()) {
                    Log.i(TAG, "container '" + name + "' is ready (marker after "
                            + (System.currentTimeMillis() - started) + " ms)");
                    return;
                }
                lastError.setLength(0);
                lastError.append(probe.describe());
            }
            long now = System.currentTimeMillis();
            if (now - lastStateCheck > 1_500) {
                lastStateCheck = now;
                lastState = DroidspacesCli.state(name);
                if (DroidspacesCli.STOPPED.equals(lastState) && now - started > 3_000) break;
            }
            if (!execReady && now - started > READY_MARKER_GRACE_MS) {
                // Older runtime images never write the marker; fall back to probing the session.
                ExecResult probe = testExecutable(context, name, ContainerPaths.GUEST_SESSION);
                if (probe.ok()) {
                    Log.w(TAG, "container '" + name + "' has no ready marker; continuing (older image)");
                    return;
                }
                execReady = true;
            }
            sleep(100);
        }
        throw new IllegalStateException("container '" + name + "' did not become ready within "
                + READY_TIMEOUT_MS / 1000 + "s (state=" + lastState + ")\n" + lastError
                + "\n" + diagnostics(context, name));
    }

    public static void stop(Context context) {
        stop(context, ContainerPaths.containerName(context));
    }

    public static void stop(Context context, String name) {
        synchronized (START_LOCK) {
            stopContainer(context, name);
        }
    }

    /**
     * Reuses a running container (session or maintenance) and only starts one when the state is
     * confirmed STOPPED. It never replaces a running container: a one-shot command that lands
     * while a game session runs must not stop it.
     */
    public static ContainerSession ensureRunning(Context context, SessionSpec session) {
        if (!isAvailable(context)) throw new IllegalStateException(unavailableMessage(context));
        SessionSpec spec = session == null ? maintenanceSpec() : session;
        synchronized (START_LOCK) {
            String state = confirmedState(context);
            if (DroidspacesCli.RUNNING.equals(state)) {
                return new ContainerSession(ContainerPaths.containerName(context), spec.sessionId());
            }
            if (!DroidspacesCli.STOPPED.equals(state)) {
                throw new IllegalStateException("cannot confirm the state of container '"
                        + ContainerPaths.containerName(context) + "' (state=" + state + ")\n"
                        + diagnostics(context, ContainerPaths.containerName(context)));
            }
            return startWithoutReplacing(context, spec);
        }
    }

    /** Keeps a container alive for one-shot commands while no session runs (DD_MODE=service). */
    /** True when the named container is running the supervisor (not a legacy session container). */
    public static boolean isSupervisorRunning(Context context) {
        String name = ContainerPaths.containerName(context);
        if (!DroidspacesCli.RUNNING.equals(DroidspacesCli.state(name))) return false;
        String status = readSupervisorStatus(context);
        return !status.isEmpty();
    }

    private static android.content.SharedPreferences supervisorPrefs(Context context) {
        return context.getSharedPreferences("runtime_store", Context.MODE_PRIVATE);
    }

    private static long supervisorInstallTime(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    private static java.io.File supervisorFile(Context context, String name) {
        return new java.io.File(new java.io.File(context.getFilesDir(), "container"), name);
    }

    private static void writeSupervisorCommand(Context context, String command) {
        java.io.File commandFile = supervisorFile(context, "supervisor.cmd");
        java.io.File temp = supervisorFile(context, "supervisor.cmd.tmp");
        try {
            temp.getParentFile().mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                out.write((command + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(commandFile)) {
                throw new java.io.IOException("cannot replace " + commandFile);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot send '" + command + "' to the supervisor: " + e, e);
        }
    }

    private static String readSupervisorStatus(Context context) {
        java.io.File file = supervisorFile(context, "supervisor.status");
        try {
            return file.isFile() ? new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim() : "";
        } catch (java.io.IOException e) {
            return "";
        }
    }

    public static void stopSupervisorSession(Context context) {
        if (isSupervisorRunning(context)) writeSupervisorCommand(context, "stop");
    }

    private static void stopPreviousSupervisedSession(Context context) {
        if (!isSupervisorRunning(context)) return;
        String current = readSupervisorStatus(context);
        if (!current.startsWith("running") && !current.startsWith("starting")) return;
        Log.i(TAG, "stopping the previous supervised session before switching modes");
        writeSupervisorCommand(context, "stop");
        long idleDeadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < idleDeadline) {
            if (readSupervisorStatus(context).startsWith("idle")) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static volatile Thread supervisorPrewarm;

    /** Start the supervisor container in the background while the session is still being staged. */
    public static void prewarmSupervisorSession(Context context, SessionSpec session) {
        if (session == null || !isAvailable(context) || isSupervisorRunning(context)) return;
        if (supervisorPrewarm != null && supervisorPrewarm.isAlive()) return;
        supervisorPrewarm = new Thread(() -> {
            try {
                synchronized (START_LOCK) {
                    ensureSupervisorContainer(context, session);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "supervisor prewarm failed", e);
            }
        }, "supervisor-prewarm");
        supervisorPrewarm.start();
    }

    private static void ensureSupervisorContainer(Context context, SessionSpec spec) {
        String name = ContainerPaths.containerName(context);
        long appUpdated = supervisorInstallTime(context);
        long supervisorUpdated = supervisorPrefs(context).getLong("app_updated", 0);
        if (isSupervisorRunning(context) && supervisorUpdated != appUpdated) {
            Log.i(TAG, "the supervisor container predates this app update; restarting it");
            stopContainer(context, name);
        }
            if (!isSupervisorRunning(context)) {
                if (DroidspacesCli.RUNNING.equals(DroidspacesCli.state(name))) {
                    Log.w(TAG, "stopping the legacy container '" + name + "' before starting the supervisor");
                    stopContainer(context, name);
                }
                supervisorFile(context, "supervisor.status").delete();
                supervisorFile(context, "supervisor.cmd").delete();
                supervisorFile(context, "session.pid").delete();
                supervisorFile(context, "session.exit").delete();
                SessionSpec supervisorSpec = SessionSpec.builder()
                        .mode("supervisor")
                        .sessionId(spec.sessionId())
                        .uid(spec.uid())
                        .gid(spec.gid())
                        .groups(spec.groups())
                        .build();
                prepare(context, supervisorSpec);
                List<String> binds = ContainerEnv.binds(context, spec);
                cleanStaleContainerConfig(context, name);
                DroidspacesCli.ContainerSpec cliSpec = DroidspacesCli.ContainerSpec
                        .builder(name, ContainerPaths.rootfs(context))
                        .init(ContainerPaths.GUEST_INIT)
                        .binds(binds)
                        .build();
                ExecResult result = DroidspacesCli.start(cliSpec);
                if (!result.ok() && looksLikeNameConflict(result)) {
                    stopContainer(context, name);
                    result = DroidspacesCli.start(cliSpec);
                }
                if (!result.ok()) {
                    throw new IllegalStateException("cannot start the supervisor container '" + name + "'\n"
                            + result.describe() + "\n" + diagnostics(context, name));
                }
                awaitReady(context, name);
                supervisorPrefs(context).edit().putLong("app_updated", appUpdated).apply();
            }
    }

    /**
     * Starts a session inside the persistent supervisor container. The container itself is left
     * running, so a finished session no longer takes the runtime (and any base service such as
     * DSH) down with it.
     */
    public static ContainerSession startSupervisorSession(Context context, SessionSpec session) {
        if (!isAvailable(context)) throw new IllegalStateException(unavailableMessage(context));
        SessionSpec spec = session == null
                ? SessionSpec.builder().mode(SessionSpec.MODE_DESKTOP).build() : session;
        Thread prewarm = supervisorPrewarm;
        if (prewarm != null && prewarm.isAlive()) {
            try {
                prewarm.join(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (START_LOCK) {
            String name = ContainerPaths.containerName(context);
            stopPreviousSupervisedSession(context);
            ensureSupervisorContainer(context, spec);
            // Wait until the supervisor has written its first status line.
            long readyDeadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < readyDeadline && readSupervisorStatus(context).isEmpty()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (readSupervisorStatus(context).isEmpty()) {
                throw new IllegalStateException("the supervisor did not become ready");
            }
            // The supervisor reads these just before launching the session child.
            prepare(context, spec);
            writeSupervisorCommand(context, "start");
            long deadline = System.currentTimeMillis() + 15_000;
            boolean started = false;
            while (System.currentTimeMillis() < deadline) {
                String status = readSupervisorStatus(context);
                if (status.startsWith("running") || status.startsWith("starting")) {
                    started = true;
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!started) {
                throw new IllegalStateException("the supervisor did not start the session (status: "
                        + readSupervisorStatus(context) + ")");
            }
            return new ContainerSession(name, spec.sessionId(), true);
        }
    }

    public static ContainerSession startMaintenance(Context context) {
        return ensureRunning(context, maintenanceSpec());
    }

    /**
     * Stops a leftover maintenance container (DD_MODE=service) when the App has no active session.
     * Callers must only use it when no session is running; a session container is left alone.
     */
    public static boolean stopIfIdle(Context context) {
        if (!isAvailable(context)) return false;
        if (!isRunning(context)) return false;
        if (!isMaintenance(context)) {
            Log.i(TAG, "not stopping the running session container");
            return false;
        }
        try {
            stop(context);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not stop the idle maintenance container", e);
            return false;
        }
    }

    /** The last prepared session.env says DD_MODE=service: the container only serves one-shots. */
    public static boolean isMaintenance(Context context) {
        File env = ContainerPaths.sessionEnv(context);
        try {
            String mode = envValue(new String(java.nio.file.Files.readAllBytes(env.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8), "DD_MODE");
            return SessionSpec.MODE_SERVICE.equals(mode);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Reads a {@code KEY='value'} line from a session manifest. */
    static String envValue(String manifest, String key) {
        if (manifest == null || key == null) return null;
        String prefix = key + "=";
        for (String line : manifest.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(prefix)) continue;
            String value = trimmed.substring(prefix.length()).trim();
            if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1).replace("'\\''", "'");
            }
            return value;
        }
        return null;
    }

    private static SessionSpec maintenanceSpec() {
        return SessionSpec.builder()
                .mode(SessionSpec.MODE_SERVICE)
                .sessionId("service")
                .uid(Process.myUid())
                .gid(Process.myUid())
                .build();
    }

    private static String confirmedState(Context context) {
        String state = DroidspacesCli.state(ContainerPaths.containerName(context));
        for (int attempt = 1; attempt < STATE_CONFIRM_ATTEMPTS && state == null; attempt++) {
            sleep(STATE_CONFIRM_DELAY_MS);
            state = DroidspacesCli.state(ContainerPaths.containerName(context));
        }
        return state;
    }

    /** Maintenance start: a reported name conflict means the container is already running. */
    private static ContainerSession startWithoutReplacing(Context context, SessionSpec spec) {
        String name = ContainerPaths.containerName(context);
        prepare(context, spec);
        List<String> binds = ContainerEnv.binds(context, spec);
        cleanStaleContainerConfig(context, name);
        java.util.Map<String, String> preservedConfig =
                com.droiddeck.launcher.runtime.ContainerConfigStore.INSTANCE.snapshot(context, name);
        DroidspacesCli.ContainerSpec cliSpec = DroidspacesCli.ContainerSpec
                .builder(name, ContainerPaths.rootfs(context))
                .init(ContainerPaths.GUEST_INIT)
                .binds(binds)
                .build();
        ExecResult result = DroidspacesCli.start(cliSpec);
        if (!result.ok()) {
            if (looksLikeNameConflict(result) || isRunning(context)) {
                com.droiddeck.launcher.runtime.ContainerBootConfig.INSTANCE.restore(context, name);
                com.droiddeck.launcher.runtime.ContainerConfigStore.INSTANCE.restore(context, name, preservedConfig);
                return new ContainerSession(name, spec.sessionId());
            }
            throw new IllegalStateException("cannot start container '" + name + "'\n"
                    + result.describe() + "\n" + diagnostics(context, name));
        }
        try {
            awaitReady(context, name);
            com.droiddeck.launcher.runtime.ContainerBootConfig.INSTANCE.restore(context, name);
            com.droiddeck.launcher.runtime.ContainerConfigStore.INSTANCE.restore(context, name, preservedConfig);
        } catch (RuntimeException failure) {
            try {
                DroidspacesCli.stop(name);
            } catch (RuntimeException ignored) {
                // Diagnostics are already in the failure.
            }
            throw failure;
        }
        return new ContainerSession(name, spec.sessionId());
    }

    /** Background warm-up so the first session does not pay the root probe/su start-up cost. */
    public static void warmUp(Context context) {
        try {
            if (!isAvailable(context)) return;
            SuShell.exec("true", 10_000);
        } catch (RuntimeException e) {
            Log.w(TAG, "runtime warm-up failed: " + e);
        }
    }

    public static ExecResult exec(Context context, List<String> argv, int timeoutMs) {
        return DroidspacesCli.run(context, ContainerPaths.containerName(context), argv, timeoutMs);
    }

    public static ExecResult execAsUser(Context context, int uid, List<String> argv, int timeoutMs) {
        return DroidspacesCli.runAsUser(context, ContainerPaths.containerName(context), uid, argv, timeoutMs);
    }

    public static String tailLog(Context context, String name, int lines) {
        int count = Math.max(1, lines);
        ExecResult log = SuShell.exec("tail -n " + count + " "
                + SuShell.quote(ContainerPaths.containerLog(name).getPath()), 15_000);
        if (log.ok()) return log.stdout;
        ExecResult console = SuShell.exec("tail -n " + count + " "
                + SuShell.quote(ContainerPaths.consoleLog(name).getPath()), 15_000);
        if (console.ok()) return console.stdout;
        return log.stderr;
    }

    public static String diagnostics(Context context, String name) {
        StringBuilder out = new StringBuilder();
        try {
            out.append("state: ").append(DroidspacesCli.state(name)).append('\n');
            out.append("pid: ").append(DroidspacesCli.pid(name)).append('\n');
            ExecResult show = SuShell.exec(
                    SuShell.command(ContainerPaths.CLI, "show", "--json"), 15_000);
            out.append("show --json (exit ").append(show.exitCode).append("):\n")
                    .append(trim(show.combined())).append('\n');
            ExecResult info = DroidspacesCli.info(name);
            out.append("info --json (exit ").append(info.exitCode).append("):\n")
                    .append(trim(info.combined())).append('\n');
            out.append("log tail (").append(ContainerPaths.containerLog(name)).append("):\n");
            out.append(tailLog(context, name, 80));
        } catch (RuntimeException e) {
            out.append("diagnostics unavailable: ").append(e.getMessage());
        }
        return out.toString();
    }

    /**
     * DroidSpaces persists the first start's flags in Containers/<name>/container.config and later
     * starts merge over them. A stale config from an earlier experiment kept binds and a hostname
     * the CLI arguments do not set, so every start recreates it from the CLI flags. Logs/ and
     * Pids/ are left alone: the monitor needs them.
     */
    private static void cleanStaleContainerConfig(Context context, String name) {
        if (DroidspacesCli.RUNNING.equals(DroidspacesCli.state(name))) return;
        File configDir = new File(ContainerPaths.WORKSPACE + "/Containers", name);
        // droidspaces auto-detects a config next to the rootfs (dirname(rootfs)/container.config)
        // and loads it as the base before the CLI flags; rootfs is filesDir/linuxfs, so that file
        // has to go as well or an old experiment's binds survive every start.
        File autoConfig = new File(ContainerPaths.rootfs(context).getParentFile(), "container.config");
        String command = "rm -rf " + SuShell.quote(configDir.getPath())
                + " " + SuShell.quote(autoConfig.getPath());
        ExecResult result = SuShell.exec(command, 15_000);
        if (!result.ok()) {
            Log.w(TAG, "could not clear the stale container config (" + configDir + ", "
                    + autoConfig + "): " + result.stderrTail(200));
        }
        // Earlier builds derived other names from the application id (renames, debug suffixes).
        // Their configs are dead weight and can carry old binds; only stopped ones are touched.
        File containers = new File(ContainerPaths.WORKSPACE, "Containers");
        File[] staleDirs = containers.listFiles();
        if (staleDirs != null) {
            for (File dir : staleDirs) {
                String staleName = dir.getName();
                if (!staleName.startsWith("droiddeck.")
                        || staleName.equals(name)
                        || DroidspacesCli.RUNNING.equals(DroidspacesCli.state(staleName))) {
                    continue;
                }
                ExecResult removed = SuShell.exec("rm -rf " + SuShell.quote(dir.getPath()), 15_000);
                if (removed.ok()) Log.i(TAG, "removed stale container config '" + staleName + "'");
            }
        }
        String legacy = ContainerPaths.LEGACY_CONTAINER_NAME;
        if (!legacy.equals(name)) {
            try {
                if (DroidspacesCli.RUNNING.equals(DroidspacesCli.state(legacy))) {
                    Log.i(TAG, "stopping the legacy container '" + legacy + "'");
                    DroidspacesCli.stop(legacy);
                }
                SuShell.exec("rm -rf " + SuShell.quote(ContainerPaths.WORKSPACE + "/Containers/" + legacy),
                        15_000);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not retire the legacy container '" + legacy + "': " + e.getMessage());
            }
        }
    }

    public static void stopContainer(Context context, String name) {
        if (DroidspacesCli.STOPPED.equals(DroidspacesCli.state(name))) return;
        ExecResult result = DroidspacesCli.stop(name);
        long deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!DroidspacesCli.RUNNING.equals(DroidspacesCli.state(name))) return;
            sleep(READY_POLL_MS);
        }
        throw new IllegalStateException("container '" + name + "' did not stop within "
                + STOP_TIMEOUT_MS / 1000 + "s\n" + result.describe() + "\n"
                + diagnostics(context, name));
    }

    private static ExecResult testExecutable(Context context, String name, String guestPath) {
        ExecResult result = DroidspacesCli.run(context, name,
                Arrays.asList("test", "-x", guestPath), 15_000);
        if (result.exitCode == 127 || result.combined().contains("Failed to execute")) {
            result = DroidspacesCli.run(context, name,
                    Arrays.asList("/bin/sh", "-c", "test -x " + SuShell.quote(guestPath)), 15_000);
        }
        return result;
    }

    private static boolean looksLikeNameConflict(ExecResult result) {
        String text = result.combined().toLowerCase(Locale.ROOT);
        return text.contains("already") || text.contains("exists");
    }

    private static String unavailableMessage(Context context) {
        return "DroidSpaces runtime is unavailable: root=" + SuShell.rootState()
                + ", cliInstalled=" + (SuShell.isRootMemoized() && DroidspacesCli.installed())
                + ", cli=" + ContainerPaths.CLI;
    }

    private static String trim(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= 4000 ? trimmed : "…" + trimmed.substring(trimmed.length() - 4000);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
