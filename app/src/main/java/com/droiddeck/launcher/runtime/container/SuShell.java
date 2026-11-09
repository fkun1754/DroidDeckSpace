package com.droiddeck.launcher.runtime.container;

import android.content.Context;
import android.util.Log;

import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingQueue;
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
    private static final Object BRIDGE_LOCK = new Object();
    private static volatile File bridgeDir;
    private static RootBridge bridge;

    private SuShell() {}

    /**
     * Enables the persistent root bridge. Every `su -c` costs ~6 s on this device (KernelSU
     * redirects su through ksud), so the app starts one root shell script that serves commands
     * over FIFOs in its own private directory; later commands cost milliseconds.
     */
    public static void init(Context context) {
        if (context == null) return;
        bridgeDir = new File(context.getFilesDir(), "container/root-bridge");
    }

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
            lastProbeAtMs = System.currentTimeMillis();
            int attempt = 0;
            for (String candidate : SU_CANDIDATES) {
                // The first attempt may wait for the user to answer a root prompt.
                int timeout = attempt++ == 0 ? PROBE_TIMEOUT_FIRST_MS : PROBE_TIMEOUT_MS;
                List<String> argv = Arrays.asList(candidate, "-c",
                        watchdogScript("id -u", timeout));
                ExecResult result = runRaw(argv, candidate + " -c 'id -u'",
                        saturatedAdd(timeout, WATCHDOG_GRACE_MS), null, null, null, false, true);
                                if (!result.timedOut && result.exitCode == 0
                        && "0".equals(result.stdout.trim())) {
                    suBinary = candidate;
                    rootAvailable = true;
                    Log.i(TAG, "root shell available via " + candidate
                            + " (" + result.durationMs + "ms)");
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
        if (onLine == null && stdin == null && cancelled == null) {
            RootBridge rootBridge = ensureBridge();
            if (rootBridge != null) {
                ExecResult bridged = rootBridge.exec(command, timeoutMs);
                if (bridged != null) return bridged;
            }
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

    private static final String BRIDGE_SCRIPT = "#!/system/bin/sh\n"
            + "export PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:$PATH\n"
            + "out=$1; in=$2; exec 3>\"$out\" 2>/dev/null || exit 1; exec 4<\"$in\" 2>/dev/null || exit 3\n"
            + "while IFS= read -r line <&4; do\n"
            + "  [ -n \"$line\" ] || continue\n"
            + "  nonce=$(printf '%s' \"$line\" | cut -d'|' -f1);"
            + " tmo=$(printf '%s' \"$line\" | cut -d'|' -f2);"
            + " b64=$(printf '%s' \"$line\" | cut -d'|' -f3)\n"
            + "  cmd=$(printf '%s' \"$b64\" | /system/bin/base64 -d 2>/dev/null) || cmd=\n"
            + "  tmp=/data/local/tmp/.ddroot.$nonce\n"
            + "  { /system/bin/timeout \"$tmo\" /system/bin/sh -c \"$cmd\"; } > \"$tmp\" 2>&1\n"
            + "  rc=$?\n"
            + "  printf '__DD_BEGIN_%s__\\n' \"$nonce\" >&3\n"
            + "  cat \"$tmp\" >&3 2>/dev/null\n"
            + "  printf '__DD_END_%s rc=%s\\n' \"$nonce\" \"$rc\" >&3\n"
            + "  rm -f \"$tmp\"\n"
            + "done\n"
            + "exit 0\n";

    /** One root shell script started with a single `su -c`, serving commands over a FIFO pair. */
    private static final class RootBridge {
        final File inFifo;
        final File outFifo;
        final Process process;
        final FileOutputStream in;
        final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final Thread reader;
        volatile boolean broken;
        long generation;

        private RootBridge(File inFifo, File outFifo, Process process, FileOutputStream in) {
            this.inFifo = inFifo;
            this.outFifo = outFifo;
            this.process = process;
            this.in = in;
            this.reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        new FileInputStream(outFifo), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) lines.offer(line);
                } catch (IOException ignored) {
                }
                lines.offer(END_OF_STREAM);
            }, "root-bridge-out");
            reader.setDaemon(true);
            reader.start();
        }

        static RootBridge start(File dir) {
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File script = new File(dir, "bridge.sh");
            try {
                byte[] want = BRIDGE_SCRIPT.getBytes(StandardCharsets.UTF_8);
                if (!script.isFile() || script.length() != want.length
                        || !Arrays.equals(java.nio.file.Files.readAllBytes(script.toPath()), want)) {
                    java.nio.file.Files.write(script.toPath(), want);
                }
                Os.chmod(script.getPath(), 0700);
            } catch (Exception e) {
                Log.w(TAG, "cannot stage the root bridge script: " + e.getMessage());
                return null;
            }
            long generation = System.currentTimeMillis();
            File in = new File(dir, "in-" + generation + ".fifo");
            File out = new File(dir, "out-" + generation + ".fifo");
            for (File fifo : new File[]{in, out}) {
                fifo.delete();
                try {
                    Os.mkfifo(fifo.getPath(), 0600);
                } catch (Exception e) {
                    Log.w(TAG, "cannot create the root bridge fifo: " + e.getMessage());
                    return null;
                }
            }
            Process process;
            try {
                process = new ProcessBuilder("su", "-c", "exec /system/bin/sh "
                        + quote(script.getPath()) + " " + quote(out.getPath()) + " "
                        + quote(in.getPath())).redirectErrorStream(true).start();
            } catch (IOException e) {
                Log.w(TAG, "cannot start the root bridge: " + e.getMessage());
                return null;
            }
            // Opening the fifos blocks until the root script reaches the same point; do it off
            // the caller thread with a deadline so a denied `su` cannot hang the app.
            final Object lock = new Object();
            final FileOutputStream[] writer = new FileOutputStream[1];
            final boolean[] opened = new boolean[1];
            Thread opener = new Thread(() -> {
                try (FileInputStream ignoredOut = new FileInputStream(out)) {
                    FileOutputStream inWriter = new FileOutputStream(in);
                    synchronized (lock) {
                        writer[0] = inWriter;
                        opened[0] = true;
                        lock.notifyAll();
                    }
                } catch (IOException e) {
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            }, "root-bridge-open");
            opener.setDaemon(true);
            opener.start();
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + 25_000;
                while (!opened[0] && System.currentTimeMillis() < deadline) {
                    try {
                        lock.wait(Math.max(50, deadline - System.currentTimeMillis()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (!opened[0] || !process.isAlive()) {
                    process.destroyForcibly();
                    in.delete();
                    out.delete();
                    return null;
                }
                RootBridge bridge = new RootBridge(in, out, process, writer[0]);
                bridge.generation = generation;
                return bridge;
            }
        }

        boolean alive() {
            return !broken && process.isAlive();
        }

        ExecResult exec(String command, int timeoutMs) {
            synchronized (this) {
                if (!alive()) return null;
                String nonce = String.valueOf(System.nanoTime());
                int seconds = timeoutMs > 0 ? Math.max(1, (timeoutMs + 999) / 1000) : 86400;
                String b64 = Base64.getEncoder().encodeToString(
                        command.getBytes(StandardCharsets.UTF_8));
                long started = System.nanoTime();
                try {
                    in.write((nonce + "|" + seconds + "|" + b64 + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    in.flush();
                } catch (IOException e) {
                    broken = true;
                    return null;
                }
                StringBuilder stdout = new StringBuilder();
                boolean sawBegin = false;
                boolean timedOut = false;
                int exit = -1;
                long deadline = started + TimeUnit.SECONDS.toNanos(seconds + 10);
                while (true) {
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) {
                        timedOut = true;
                        break;
                    }
                    String line;
                    try {
                        line = lines.poll(Math.max(10, Math.min(remaining, 250)),
                                TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        broken = true;
                        return null;
                    }
                    if (line == null) {
                        if (!process.isAlive()) break;
                        continue;
                    }
                    if (END_OF_STREAM.equals(line)) break;
                    String begin = "__DD_BEGIN_" + nonce + "__";
                    String endPrefix = "__DD_END_" + nonce + " rc=";
                    if (line.equals(begin)) {
                        sawBegin = true;
                    } else if (line.startsWith(endPrefix)) {
                        try {
                            exit = Integer.parseInt(line.substring(endPrefix.length()).trim());
                        } catch (NumberFormatException ignored) {
                        }
                        break;
                    } else if (sawBegin) {
                        stdout.append(line).append('\n');
                    }
                }
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (timedOut) {
                    broken = true;
                    return new ExecResult(command, -1, stdout.toString(), "",
                            true, elapsed);
                }
                if (!sawBegin) {
                    broken = true;
                    return null;
                }
                return new ExecResult(command, exit, stdout.toString(), "", false, elapsed);
            }
        }

        void close() {
            broken = true;
            try {
                in.close();
            } catch (IOException ignored) {
            }
            try {
                process.destroyForcibly();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static final String END_OF_STREAM = "\u0000__DD_EOS__";

    private static RootBridge ensureBridge() {
        if (bridgeDir == null) return null;
        synchronized (BRIDGE_LOCK) {
            if (bridge != null && bridge.alive()) return bridge;
            bridge = RootBridge.start(bridgeDir);
            if (bridge == null) {
                Log.w(TAG, "root bridge unavailable; falling back to one-shot su");
            } else {
                Log.i(TAG, "root bridge ready (generation " + bridge.generation + ")");
            }
            return bridge;
        }
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
