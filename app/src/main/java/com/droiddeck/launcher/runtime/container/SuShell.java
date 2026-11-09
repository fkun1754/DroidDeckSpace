package com.droiddeck.launcher.runtime.container;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs host-side commands through {@code su -c}. DroidSpaces requires real root and the App is
 * not root, so every CLI call goes through here (docs/development/container-runtime.md §2.1).
 */
public final class SuShell {
    private static final String TAG = "DroidDeckContainer";
    // KernelSU and Magisk both install on the PATH or in /system/bin; probing more candidates
    // would cost one timeout each on a device without root.
    private static final String[] SU_CANDIDATES = {"su", "/system/bin/su"};
    private static final int PROBE_TIMEOUT_FIRST_MS = 20_000;
    private static final int PROBE_TIMEOUT_MS = 5_000;
    private static final int OUTPUT_CAP = 256 * 1024;
    private static final String WATCHDOG_MARKER = "__DROIDDECK_WATCHDOG_TIMEOUT__";
    private static final int WATCHDOG_GRACE_MS = 3_000;

    private static final Object LOCK = new Object();
    private static Boolean rootAvailable;
    private static String suBinary;
    private static volatile long lastProbeAtMs;

    private SuShell() {}

    public static boolean isRootAvailable(Context context) {
        synchronized (LOCK) {
            if (rootAvailable != null) return rootAvailable;
        }
        return probe();
    }

    /** Null while nobody has probed yet. */
    public static Boolean rootState() {
        synchronized (LOCK) {
            return rootAvailable;
        }
    }

    public static boolean isRootMemoized() {
        return Boolean.TRUE.equals(rootState());
    }

    /** Wall-clock time of the last probe, or 0 when none has run yet; lets UI throttle retries. */
    public static long lastProbeAtMs() {
        return lastProbeAtMs;
    }

    /** Probes off the main thread; UI code reads rootState() and re-reads it after a delay. */
    public static void probeAsync() {
        if (rootState() != null) return;
        Thread probe = new Thread(() -> isRootAvailable(null), "container-root-probe");
        probe.setDaemon(true);
        probe.start();
    }

    /** Lets a UI refresh pick up a root grant that happened after the first probe. */
    public static void forget() {
        synchronized (LOCK) {
            rootAvailable = null;
            suBinary = null;
        }
        DroidspacesCli.forget();
    }

    private static boolean probe() {
        synchronized (LOCK) {
            if (rootAvailable != null) return rootAvailable;
            for (String candidate : SU_CANDIDATES) {
                List<String> argv = Arrays.asList(candidate, "-c",
                        watchdogScript("id -u", PROBE_TIMEOUT_MS));
                ExecResult result = runRaw(argv, candidate + " -c 'id -u'",
                        PROBE_TIMEOUT_MS + WATCHDOG_GRACE_MS, null, null, null, false, true);
                if (!result.timedOut && result.exitCode == 0 && "0".equals(result.stdout.trim())) {
                    suBinary = candidate;
                    rootAvailable = true;
                    Log.i(TAG, "root shell available via " + candidate);
                    return true;
                }
            }
            suBinary = null;
            rootAvailable = false;
            Log.w(TAG, "no working root shell (su not found or not granted)");
            return false;
        }
    }

    public static ExecResult exec(String command, int timeoutMs) {
        return execStreaming(command, timeoutMs, null, null, null, false);
    }

    public static ExecResult execForOutput(String command, int timeoutMs) {
        return execStreaming(command, timeoutMs, null, null, null, false);
    }

    public static ExecResult execStreaming(String command, int timeoutMs,
                                           Consumer<String> onLine, AtomicBoolean cancelled) {
        return execStreaming(command, timeoutMs, onLine, cancelled, null, true);
    }

    public static ExecResult execStreaming(String command, int timeoutMs, Consumer<String> onLine,
                                           AtomicBoolean cancelled, String stdin) {
        return execStreaming(command, timeoutMs, onLine, cancelled, stdin, true);
    }

    public static ExecResult execStreaming(String command, int timeoutMs, Consumer<String> onLine,
                                           AtomicBoolean cancelled, String stdin, boolean mergeError) {
        if (command == null || command.trim().isEmpty()) {
            return ExecResult.failure("", "empty root command");
        }
        if (!isRootAvailable(null)) {
            return ExecResult.failure(command, "root is not available (su not found or not granted)");
        }
        String binary;
        synchronized (LOCK) {
            binary = suBinary;
        }
        if (binary == null) binary = "su";
        // bounded commands get a root-side watchdog: the App has no permission to kill a root
        // child of its own, so the root shell kills the command itself (it may only kill the
        // host-side droidspaces process; guest children need ContainerControl + a guest pid).
        boolean watchdog = timeoutMs > 0;
        String executed = watchdog ? watchdogScript(command, timeoutMs) : command;
        int waitMs = watchdog ? saturatedAdd(timeoutMs, WATCHDOG_GRACE_MS) : timeoutMs;
        List<String> argv = Arrays.asList(binary, "-c", executed);
        return runRaw(argv, command, waitMs, stdin, onLine, cancelled, mergeError, watchdog);
    }

    /**
     * Wraps one shell command in a root-side timeout: the command runs as a background job with
     * fd 0 duplicated to fd 9 (a background job would otherwise read /dev/null), a watchdog kills
     * it at the deadline, and the wrapper exits with the command's own status.
     */
    static String watchdogScript(String command, int timeoutMs) {
        long seconds = Math.max(1L, (timeoutMs + 999L) / 1000L);
        return "exec 9<&0; { " + command + " ; } <&9 & child=$!; "
                + "( sleep " + seconds + "; "
                + "{ command -v pkill >/dev/null 2>&1 && pkill -9 -P \"$child\" 2>/dev/null; "
                + "kill -9 \"$child\" 2>/dev/null; }; "
                + "echo " + WATCHDOG_MARKER + " >&2 ) & wd=$!; "
                + "wait \"$child\"; rc=$?; kill \"$wd\" 2>/dev/null; exit \"$rc\"";
    }

    private static int saturatedAdd(int value, int extra) {
        long sum = (long) value + extra;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    /**
     * The shell command line for one argv. Each argument is single-quoted for the {@code su -c}
     * shell; callers must not pre-quote.
     */
    public static String command(List<String> argv) {
        StringBuilder out = new StringBuilder();
        for (String arg : argv) {
            if (out.length() > 0) out.append(' ');
            out.append(quote(arg));
        }
        return out.toString();
    }

    public static String command(String... argv) {
        return command(Arrays.asList(argv));
    }

    public static String quote(String argument) {
        String value = argument == null ? "" : argument;
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') out.append("'\\''");
            else out.append(c);
        }
        out.append('\'');
        return out.toString();
    }

    private static ExecResult runRaw(List<String> argv, String display, int timeoutMs, String stdin,
                                     Consumer<String> onLine, AtomicBoolean cancelled,
                                     boolean mergeError, boolean expectWatchdogMarker) {
        long started = System.nanoTime();
        Process process = null;
        Collector out = null;
        Collector err = null;
        AtomicBoolean watchdogFired = new AtomicBoolean(false);
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            process = builder.start();
            out = new Collector(process.getInputStream(), mergeError ? onLine : null, watchdogFired);
            err = new Collector(process.getErrorStream(), mergeError ? onLine : null, watchdogFired);
            out.start();
            err.start();
            writeStdin(process.getOutputStream(), stdin);

            boolean timedOut = false;
            boolean wasCancelled = false;
            int exit;
            if (cancelled == null && timeoutMs <= 0) {
                exit = process.waitFor();
            } else {
                long deadline = timeoutMs > 0
                        ? started + TimeUnit.MILLISECONDS.toNanos(timeoutMs) : Long.MAX_VALUE;
                exit = Integer.MIN_VALUE;
                while (true) {
                    long slice = 200;
                    if (deadline != Long.MAX_VALUE) {
                        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                        if (remainingMs <= 0) {
                            timedOut = true;
                            break;
                        }
                        slice = Math.min(slice, remainingMs);
                    }
                    if (process.waitFor(slice, TimeUnit.MILLISECONDS)) {
                        exit = process.exitValue();
                        break;
                    }
                    if (cancelled != null && cancelled.get()) {
                        wasCancelled = true;
                        break;
                    }
                }
                if (timedOut || wasCancelled) {
                    // Fallback only: the App cannot kill a root child, so this succeeds only if
                    // the root-side watchdog already made the shell exit.
                    process.destroyForcibly();
                    try {
                        process.waitFor(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    exit = wasCancelled && !timedOut ? 130 : -1;
                }
            }
            out.awaitDone();
            err.awaitDone();
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            boolean killedByWatchdog = expectWatchdogMarker && watchdogFired.get();
            return new ExecResult(display, exit, out.text(), err.text(),
                    timedOut || killedByWatchdog, elapsed);
        } catch (IOException e) {
            return ExecResult.failure(display, "cannot start root shell " + argv.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.failure(display, "interrupted while waiting for the root shell");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static void writeStdin(OutputStream stream, String stdin) {
        if (stdin == null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // The child does not read stdin; a closed pipe is the normal outcome.
            }
            return;
        }
        Thread writer = new Thread(() -> {
            try (OutputStream out = stream) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
            }
        }, "container-stdin");
        writer.setDaemon(true);
        writer.start();
    }

    private static final class Collector extends Thread {
        private final InputStream in;
        private final Consumer<String> lineSink;
        private final AtomicBoolean watchdogFired;
        private final StringBuilder text = new StringBuilder();
        private final Object textLock = new Object();
        private volatile boolean done;

        Collector(InputStream in, Consumer<String> lineSink, AtomicBoolean watchdogFired) {
            this.in = in;
            this.lineSink = lineSink;
            this.watchdogFired = watchdogFired;
            setDaemon(true);
            setName("container-output");
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8), 8192)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(WATCHDOG_MARKER)) {
                        watchdogFired.set(true);
                        continue;
                    }
                    synchronized (textLock) {
                        text.append(line).append('\n');
                        if (text.length() > OUTPUT_CAP) text.delete(0, text.length() - OUTPUT_CAP);
                    }
                    if (lineSink != null) {
                        try {
                            lineSink.accept(line);
                        } catch (RuntimeException ignored) {
                            // A broken log callback must not abort the command.
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                done = true;
            }
        }

        void awaitDone() {
            try {
                join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean finished() {
            return done || !isAlive();
        }

        String text() {
            synchronized (textLock) {
                return text.toString();
            }
        }
    }
}
