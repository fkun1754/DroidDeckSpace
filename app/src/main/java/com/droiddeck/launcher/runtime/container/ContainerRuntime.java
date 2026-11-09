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

    public static boolean isAvailable(Context context) {
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

    public static void forget() {
        available = null;
        SuShell.forget();
    }

    public static String state(Context context) {
        return DroidspacesCli.state(ContainerPaths.CONTAINER_NAME);
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
            String name = ContainerPaths.CONTAINER_NAME;
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
        awaitReady(context, ContainerPaths.CONTAINER_NAME);
    }

    public static void awaitReady(Context context, String name) {
        long started = System.currentTimeMillis();
        long deadline = started + READY_TIMEOUT_MS;
        boolean execReady = false;
        long execReadyAt = 0;
        String lastState = null;
        String lastError = "";
        while (System.currentTimeMillis() < deadline) {
            String state = DroidspacesCli.state(name);
            lastState = state;
            if (DroidspacesCli.RUNNING.equals(state)) {
                if (!execReady) {
                    ExecResult probe = testExecutable(context, name, ContainerPaths.GUEST_SESSION);
                    if (probe.ok()) {
                        execReady = true;
                        execReadyAt = System.currentTimeMillis();
                        Log.i(TAG, "container '" + name + "' can execute " + ContainerPaths.GUEST_SESSION);
                    } else {
                        lastError = probe.describe();
                    }
                } else {
                    ExecResult marker = DroidspacesCli.run(context, name,
                            Arrays.asList("test", "-f", ContainerPaths.GUEST_READY), 10_000);
                    if (marker.ok()) return;
                    if (System.currentTimeMillis() - execReadyAt > READY_MARKER_GRACE_MS) {
                        Log.w(TAG, "container '" + name + "' never wrote "
                                + ContainerPaths.GUEST_READY + "; continuing (older runtime image)");
                        return;
                    }
                }
            } else if (DroidspacesCli.STOPPED.equals(state)
                    && System.currentTimeMillis() - started > 3_000) {
                break;
            }
            sleep(READY_POLL_MS);
        }
        throw new IllegalStateException("container '" + name + "' did not become ready within "
                + READY_TIMEOUT_MS / 1000 + "s (state=" + lastState + ", sessionExecutable="
                + execReady + ")\n" + lastError + "\n" + diagnostics(context, name));
    }

    public static void stop(Context context) {
        stop(context, ContainerPaths.CONTAINER_NAME);
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
            String state = confirmedState();
            if (DroidspacesCli.RUNNING.equals(state)) {
                return new ContainerSession(ContainerPaths.CONTAINER_NAME, spec.sessionId());
            }
            if (!DroidspacesCli.STOPPED.equals(state)) {
                throw new IllegalStateException("cannot confirm the state of container '"
                        + ContainerPaths.CONTAINER_NAME + "' (state=" + state + ")\n"
                        + diagnostics(context, ContainerPaths.CONTAINER_NAME));
            }
            return startWithoutReplacing(context, spec);
        }
    }

    /** Keeps a container alive for one-shot commands while no session runs (DD_MODE=service). */
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

    private static String confirmedState() {
        String state = DroidspacesCli.state(ContainerPaths.CONTAINER_NAME);
        for (int attempt = 1; attempt < STATE_CONFIRM_ATTEMPTS && state == null; attempt++) {
            sleep(STATE_CONFIRM_DELAY_MS);
            state = DroidspacesCli.state(ContainerPaths.CONTAINER_NAME);
        }
        return state;
    }

    /** Maintenance start: a reported name conflict means the container is already running. */
    private static ContainerSession startWithoutReplacing(Context context, SessionSpec spec) {
        String name = ContainerPaths.CONTAINER_NAME;
        prepare(context, spec);
        List<String> binds = ContainerEnv.binds(context, spec);
        cleanStaleContainerConfig(context, name);
        DroidspacesCli.ContainerSpec cliSpec = DroidspacesCli.ContainerSpec
                .builder(name, ContainerPaths.rootfs(context))
                .init(ContainerPaths.GUEST_INIT)
                .binds(binds)
                .build();
        ExecResult result = DroidspacesCli.start(cliSpec);
        if (!result.ok()) {
            if (looksLikeNameConflict(result) || isRunning(context)) {
                return new ContainerSession(name, spec.sessionId());
            }
            throw new IllegalStateException("cannot start container '" + name + "'\n"
                    + result.describe() + "\n" + diagnostics(context, name));
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

    public static ExecResult exec(Context context, List<String> argv, int timeoutMs) {
        return DroidspacesCli.run(context, ContainerPaths.CONTAINER_NAME, argv, timeoutMs);
    }

    public static ExecResult execAsUser(Context context, int uid, List<String> argv, int timeoutMs) {
        return DroidspacesCli.runAsUser(context, ContainerPaths.CONTAINER_NAME, uid, argv, timeoutMs);
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
        ExecResult result = SuShell.exec("rm -rf " + SuShell.quote(configDir.getPath()), 15_000);
        if (!result.ok()) {
            Log.w(TAG, "could not clear the stale container config " + configDir + ": "
                    + result.stderrTail(200));
        }
    }

    private static void stopContainer(Context context, String name) {
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
