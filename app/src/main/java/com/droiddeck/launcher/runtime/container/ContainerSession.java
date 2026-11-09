package com.droiddeck.launcher.runtime.container;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A container instance that was started for one session. */
public final class ContainerSession {
    private static final int POLL_MS = 500;

    private final String name;
    private final String sessionId;
    private final long startedAt;

    ContainerSession(String name, String sessionId) {
        this.name = name == null || name.isEmpty() ? ContainerPaths.CONTAINER_NAME : name;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.startedAt = System.currentTimeMillis();
    }

    public String name() {
        return name;
    }

    public String sessionId() {
        return sessionId;
    }

    public long startedAt() {
        return startedAt;
    }

    /** show --json is authoritative: a container whose init exited is no longer listed. */
    public boolean isAlive(Context context) {
        if (!DroidspacesCli.RUNNING.equals(DroidspacesCli.state(name))) return false;
        return DroidspacesCli.run(context, name, Arrays.asList("true"), 10_000).ok();
    }

    public void stop(Context context) {
        ExecResult result = DroidspacesCli.stop(name);
        if (waitStopped(30_000)) return;
        throw new IllegalStateException("container '" + name + "' is still running after stop\n"
                + result.describe() + "\n" + ContainerRuntime.diagnostics(context, name));
    }

    /** Returns the exit code read from the container log, or null when it cannot be determined. */
    public Integer waitForExit(Context context, long timeoutMs) {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        while (true) {
            String state = DroidspacesCli.state(name);
            if (!DroidspacesCli.RUNNING.equals(state)) return exitCode(context);
            if (System.currentTimeMillis() >= deadline) return null;
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * DroidSpaces has no wait/exit-code API. The monitor appends "Detected container SHUTDOWN
     * (exit: N)" to the container log, which survives the container, so parse that.
     */
    public Integer exitCode(Context context) {
        for (String line : tailLogLines(context, 400)) {
            Integer code = exitCodeOf(line);
            if (code != null) return code;
        }
        return null;
    }

    static Integer exitCodeOf(String line) {
        if (line == null) return null;
        int marker = line.indexOf("Detected container SHUTDOWN (exit: ");
        if (marker >= 0) {
            String tail = line.substring(marker + "Detected container SHUTDOWN (exit: ".length());
            int close = tail.indexOf(')');
            if (close > 0) {
                try {
                    return Integer.parseInt(tail.substring(0, close).trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }
        marker = line.indexOf("Intermediate killed by signal: ");
        if (marker >= 0) {
            String tail = line.substring(marker + "Intermediate killed by signal: ".length()).trim();
            int space = tail.indexOf(' ');
            String digits = space < 0 ? tail : tail.substring(0, space);
            try {
                return 128 + Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public int pid(Context context) {
        return DroidspacesCli.pid(name);
    }

    public String tailLog(Context context, int lines) {
        return ContainerRuntime.tailLog(context, name, lines);
    }

    public List<String> tailLogLines(Context context, int lines) {
        String text = tailLog(context, lines);
        List<String> result = new ArrayList<>();
        if (text.isEmpty()) return result;
        for (String line : text.split("\n")) result.add(line);
        return result;
    }

    public String diagnostics(Context context) {
        return ContainerRuntime.diagnostics(context, name);
    }

    private boolean waitStopped(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (!DroidspacesCli.RUNNING.equals(DroidspacesCli.state(name))) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
