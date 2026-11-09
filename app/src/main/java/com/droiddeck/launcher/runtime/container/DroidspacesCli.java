package com.droiddeck.launcher.runtime.container;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Thin wrapper over the DroidSpaces host CLI. Every call goes through {@link SuShell}.
 * Facts marked "verified" come from notes/impl-facts-cli.md (measured on the target phone).
 */
public final class DroidspacesCli {
    public static final String RUNNING = "RUNNING";
    public static final String STOPPED = "STOPPED";

    private static final int STATE_TIMEOUT_MS = 15_000;
    private static final int START_TIMEOUT_MS = 120_000;
    private static final int STOP_TIMEOUT_MS = 60_000;
    private static final int DEFAULT_RUN_TIMEOUT_MS = 60_000;

    private static volatile Boolean installed;

    private DroidspacesCli() {}

    public static boolean installed() {
        Boolean known = installed;
        if (known != null) return known;
        synchronized (DroidspacesCli.class) {
            if (installed != null) return installed;
            boolean available = SuShell.isRootAvailable(null)
                    && SuShell.exec("test -x " + SuShell.quote(ContainerPaths.CLI), 10_000).ok();
            installed = available;
            return available;
        }
    }

    public static void forget() {
        installed = null;
    }

    public static ExecResult start(ContainerSpec spec) {
        List<String> argv = new ArrayList<>();
        argv.add(ContainerPaths.CLI);
        argv.add("-n");
        argv.add(spec.name());
        argv.add("-r");
        argv.add(spec.rootfs().getPath());
        argv.add("--hostname=" + spec.hostname());
        argv.add("--init=" + spec.init());
        if (spec.netHost()) argv.add("--net=host");
        if (spec.gpu()) argv.add("--gpu");
        for (String bind : spec.binds()) argv.add("--bind=" + bind);
        // Wipe whatever config droidspaces auto-detected before the CLI flags land, so a leftover
        // config cannot contribute binds/hostname the current start does not set.
        argv.add("--reset");
        argv.add("start");
        return SuShell.exec(SuShell.command(argv), START_TIMEOUT_MS);
    }

    public static ExecResult stop(String name) {
        List<String> argv = Arrays.asList(ContainerPaths.CLI, "-n", name, "stop");
        return SuShell.exec(SuShell.command(argv), STOP_TIMEOUT_MS);
    }

    /** "RUNNING", "STOPPED", or null when the CLI output cannot be understood. */
    public static String state(String name) {
        ExecResult listed = SuShell.exec(
                SuShell.command(ContainerPaths.CLI, "show", "--json"), STATE_TIMEOUT_MS);
        if (listed.ok()) {
            String parsed = parseShowState(listed.stdout, name);
            if (parsed != null) return parsed;
        }
        return stateFromPid(name);
    }

    /** Authoritative check from {@code show --json}: a container that exited is not listed. */
    static String parseShowState(String json, String name) {
        if (json == null) return null;
        String trimmed = json.trim();
        int start = trimmed.indexOf('{');
        if (start < 0) return null;
        try {
            JSONObject root = new JSONObject(trimmed.substring(start));
            JSONArray running = root.optJSONArray("running");
            if (running == null) return null;
            for (int i = 0; i < running.length(); i++) {
                JSONObject entry = running.optJSONObject(i);
                if (entry == null) continue;
                if (name.equals(entry.optString("name")) && entry.optInt("pid", 0) > 0) return RUNNING;
            }
            return STOPPED;
        } catch (JSONException e) {
            return null;
        }
    }

    private static String stateFromPid(String name) {
        ExecResult result = SuShell.exec(SuShell.command(ContainerPaths.CLI, "-n", name, "pid"),
                STATE_TIMEOUT_MS);
        String output = result.stdout.trim();
        if (result.ok() && output.matches("\\d+")) return RUNNING;
        if ("NONE".equals(output)) return STOPPED;
        if (result.stderr.contains("not running") || result.stdout.contains("not running")) return STOPPED;
        return null;
    }

    /** Host PID of the container init (which becomes the session process after the init execs it). */
    public static int pid(String name) {
        ExecResult result = SuShell.exec(SuShell.command(ContainerPaths.CLI, "-n", name, "pid"),
                STATE_TIMEOUT_MS);
        String output = result.stdout.trim();
        if (result.ok() && output.matches("\\d+")) {
            try {
                return Integer.parseInt(output);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    /** `info --json` only works while the container runs; on a stopped container it fails. */
    public static ExecResult info(String name) {
        return SuShell.exec(SuShell.command(ContainerPaths.CLI, "-n", name, "info", "--json"),
                STATE_TIMEOUT_MS);
    }

    public static ExecResult run(Context context, String name, List<String> argv, int timeoutMs) {
        return exec(context, name, null, argv, null, timeoutMs, null, null);
    }

    public static ExecResult runAsUser(Context context, String name, int uid, List<String> argv,
                                       int timeoutMs) {
        return exec(context, name, uid, argv, null, timeoutMs, null, null);
    }

    /**
     * Runs one command in the running container: root when {@code uid} is null, otherwise wrapped
     * in setpriv (the rootfs has no passwd entry for the Android uid, so `run --user` cannot be
     * used; notes/impl-facts-cli.md). Merged stdout/stderr lines go to {@code onLine}; cancelled
     * runs return exit code 130.
     */
    public static ExecResult exec(Context context, String name, Integer uid, List<String> argv,
                                  String stdin, int timeoutMs, Consumer<String> onLine,
                                  AtomicBoolean cancelled) {
        if (argv == null || argv.isEmpty()) {
            return ExecResult.failure("", "empty guest command");
        }
        List<String> command = new ArrayList<>();
        command.add(ContainerPaths.CLI);
        command.add("-n");
        command.add(name);
        if (stdin != null) command.add("--stdin");
        command.add("run");
        command.add("--");
        if (uid != null) command.addAll(setprivPrefix(uid, uid, ContainerEnv.defaultGroups(uid)));
        command.addAll(argv);
        int timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_RUN_TIMEOUT_MS;
        return SuShell.execStreaming(SuShell.command(command), timeout, onLine, cancelled, stdin, true);
    }

    /** Long-running commands (installers) pass a negative timeout to disable the App-side limit. */
    public static ExecResult execUnlimited(Context context, String name, Integer uid,
                                           List<String> argv, Consumer<String> onLine,
                                           AtomicBoolean cancelled) {
        if (argv == null || argv.isEmpty()) {
            return ExecResult.failure("", "empty guest command");
        }
        List<String> command = new ArrayList<>();
        command.add(ContainerPaths.CLI);
        command.add("-n");
        command.add(name);
        command.add("run");
        command.add("--");
        if (uid != null) command.addAll(setprivPrefix(uid, uid, ContainerEnv.defaultGroups(uid)));
        command.addAll(argv);
        return SuShell.execStreaming(SuShell.command(command), -1, onLine, cancelled, null, true);
    }

    static List<String> setprivPrefix(int uid, int gid, int[] groups) {
        int[] effective = groups == null || groups.length == 0
                ? ContainerEnv.defaultGroups(uid) : groups;
        StringBuilder joined = new StringBuilder();
        for (int group : effective) {
            if (joined.length() > 0) joined.append(',');
            joined.append(group);
        }
        List<String> prefix = new ArrayList<>();
        prefix.add("setpriv");
        prefix.add("--reuid=" + uid);
        prefix.add("--regid=" + gid);
        prefix.add("--groups=" + joined);
        prefix.add("--inh-caps=-all");
        prefix.add("--bounding-set=-all");
        prefix.add("--");
        return prefix;
    }

    public static final class ContainerSpec {
        private final String name;
        private final File rootfs;
        private final String init;
        private final String hostname;
        private final List<String> binds;
        private final boolean netHost;
        private final boolean gpu;

        private ContainerSpec(Builder builder) {
            this.name = builder.name;
            this.rootfs = builder.rootfs;
            this.init = builder.init;
            this.hostname = builder.hostname;
            this.binds = builder.binds;
            this.netHost = builder.netHost;
            this.gpu = builder.gpu;
        }

        public static Builder builder(String name, File rootfs) {
            return new Builder(name, rootfs);
        }

        public String name() {
            return name;
        }

        public File rootfs() {
            return rootfs;
        }

        public String init() {
            return init;
        }

        public String hostname() {
            return hostname;
        }

        public List<String> binds() {
            return binds;
        }

        public boolean netHost() {
            return netHost;
        }

        public boolean gpu() {
            return gpu;
        }

        public static final class Builder {
            private final String name;
            private final File rootfs;
            private String init = ContainerPaths.GUEST_INIT;
            // A fresh config is written on every start, but never let an old config's hostname
            // (e.g. from an earlier experiment) survive a restart.
            private String hostname = "droiddeck";
            private List<String> binds = new ArrayList<>();
            private boolean netHost = true;
            private boolean gpu = true;

            private Builder(String name, File rootfs) {
                this.name = name;
                this.rootfs = rootfs;
            }

            public Builder init(String value) {
                init = value;
                return this;
            }

            public Builder hostname(String value) {
                hostname = value;
                return this;
            }

            public Builder binds(List<String> values) {
                binds = values == null ? new ArrayList<>() : new ArrayList<>(values);
                return this;
            }

            public Builder netHost(boolean value) {
                netHost = value;
                return this;
            }

            public Builder gpu(boolean value) {
                gpu = value;
                return this;
            }

            public ContainerSpec build() {
                return new ContainerSpec(this);
            }
        }
    }
}
