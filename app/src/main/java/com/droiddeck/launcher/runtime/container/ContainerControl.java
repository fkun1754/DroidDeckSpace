package com.droiddeck.launcher.runtime.container;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Guest process control through droiddeck-container-ctl over {@code droidspaces run}. */
public final class ContainerControl {
    private static final String TAG = "DroidDeckContainer";
    private static final int DEFAULT_TIMEOUT_MS = 20_000;

    public static final int SIGHUP = 1;
    public static final int SIGINT = 2;
    public static final int SIGKILL = 9;
    public static final int SIGCONT = 18;
    public static final int SIGSTOP = 19;
    public static final int SIGTERM = 15;

    private ContainerControl() {}

    public static List<GuestProcess> ps(Context context) {
        ExecResult result = ctl(context, DEFAULT_TIMEOUT_MS, "ps");
        return parsePs(result.stdout);
    }

    /** "boot=<epoch> pids=<n> uptime=<s>" as a map. */
    public static java.util.Map<String, String> stat(Context context) {
        ExecResult result = ctl(context, DEFAULT_TIMEOUT_MS, "stat");
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String token : result.stdout.trim().split("\\s+")) {
            int separator = token.indexOf('=');
            if (separator <= 0) continue;
            values.put(token.substring(0, separator), token.substring(separator + 1));
        }
        return values;
    }

    public static void signal(Context context, int signal, int... pids) {
        if (pids == null || pids.length == 0) throw new IllegalArgumentException("no pids");
        List<String> argv = new ArrayList<>();
        argv.add("signal");
        argv.add(signalName(signal));
        for (int pid : pids) argv.add(Integer.toString(pid));
        ctl(context, DEFAULT_TIMEOUT_MS, argv.toArray(new String[0]));
    }

    public static void kill(Context context, int... pids) {
        if (pids == null || pids.length == 0) throw new IllegalArgumentException("no pids");
        List<String> argv = new ArrayList<>();
        argv.add("kill");
        for (int pid : pids) argv.add(Integer.toString(pid));
        ctl(context, DEFAULT_TIMEOUT_MS, argv.toArray(new String[0]));
    }

    /** Best-effort signal for cancellation paths: never throws, returns false on failure. */
    public static boolean signalQuietly(Context context, int signal, int pid) {
        if (pid <= 1) return false;
        try {
            signal(context, signal, pid);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not signal guest pid " + pid + ": " + e.getMessage());
            return false;
        }
    }

    /** Best-effort SIGKILL by guest pid, used when a host-side su cannot be killed. */
    public static boolean killQuietly(Context context, int pid) {
        return signalQuietly(context, SIGKILL, pid);
    }

    public static void freeze(Context context) {
        ctl(context, DEFAULT_TIMEOUT_MS, "freeze");
    }

    public static void resume(Context context) {
        ctl(context, DEFAULT_TIMEOUT_MS, "resume");
    }

    static String signalName(int signal) {
        switch (signal) {
            case SIGHUP:
                return "HUP";
            case SIGINT:
                return "INT";
            case SIGTERM:
                return "TERM";
            case SIGKILL:
                return "KILL";
            case SIGSTOP:
                return "STOP";
            case SIGCONT:
                return "CONT";
            default:
                throw new IllegalArgumentException("unsupported signal " + signal);
        }
    }

    /** One line per process: {@code <pid> <ppid> <uid> <stat> <comm> <args...>} */
    static List<GuestProcess> parsePs(String output) {
        List<GuestProcess> processes = new ArrayList<>();
        if (output == null) return processes;
        for (String line : output.split("\n")) {
            GuestProcess process = parsePsLine(line);
            if (process != null) processes.add(process);
        }
        return processes;
    }

    static GuestProcess parsePsLine(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        String[] fields = trimmed.split("\\s+", 6);
        if (fields.length < 5) return null;
        try {
            int pid = Integer.parseInt(fields[0]);
            int ppid = Integer.parseInt(fields[1]);
            int uid = Integer.parseInt(fields[2]);
            return new GuestProcess(pid, ppid, uid, fields[3], fields[4],
                    fields.length > 5 ? fields[5] : "");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ExecResult ctl(Context context, int timeoutMs, String... argv) {
        List<String> command = new ArrayList<>();
        command.add(ContainerPaths.GUEST_CTL);
        command.addAll(Arrays.asList(argv));
        ExecResult result = DroidspacesCli.exec(context, ContainerPaths.CONTAINER_NAME, null,
                command, null, timeoutMs, null, null);
        if (!result.ok()) {
            throw new IllegalStateException("guest control '" + String.join(" ", command)
                    + "' failed\n" + result.describe() + "\n"
                    + ContainerRuntime.diagnostics(context, ContainerPaths.CONTAINER_NAME));
        }
        return result;
    }

    public static final class GuestProcess {
        public final int pid;
        public final int ppid;
        public final int uid;
        public final String stat;
        public final String comm;
        public final String args;

        GuestProcess(int pid, int ppid, int uid, String stat, String comm, String args) {
            this.pid = pid;
            this.ppid = ppid;
            this.uid = uid;
            this.stat = stat;
            this.comm = comm;
            this.args = args;
        }

        public boolean zombie() {
            return stat != null && stat.startsWith("Z");
        }

        @Override
        public String toString() {
            return pid + " " + ppid + " " + uid + " " + stat + " " + comm + " " + args;
        }
    }
}
