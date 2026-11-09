package com.droiddeck.launcher.runtime.container;

/** Result of one root-shell command. */
public final class ExecResult {
    public final int exitCode;
    public final String stdout;
    public final String stderr;
    public final boolean timedOut;
    public final String command;
    public final long durationMs;

    public ExecResult(String command, int exitCode, String stdout, String stderr,
                      boolean timedOut, long durationMs) {
        this.command = command == null ? "" : command;
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.timedOut = timedOut;
        this.durationMs = durationMs;
    }

    public static ExecResult failure(String command, String message) {
        return new ExecResult(command, -1, "", message, false, 0);
    }

    /** A command that the root-side watchdog (or the App-side fallback) had to kill. */
    public static ExecResult timeout(String command, String stdout, String stderr, long durationMs) {
        return new ExecResult(command, -1, stdout, stderr, true, durationMs);
    }

    public boolean ok() {
        return exitCode == 0 && !timedOut;
    }

    public String combined() {
        if (stdout.isEmpty()) return stderr;
        if (stderr.isEmpty()) return stdout;
        return stdout + (stdout.endsWith("\n") ? "" : "\n") + stderr;
    }

    public String stderrTail(int max) {
        if (stderr.length() <= max) return stderr;
        return "…" + stderr.substring(stderr.length() - max);
    }

    public String describe() {
        return describe(2000);
    }

    public String describe(int tail) {
        StringBuilder out = new StringBuilder();
        out.append("command: ").append(command);
        out.append("\nexit: ").append(exitCode);
        if (timedOut) out.append(" (timed out after ").append(durationMs).append(" ms)");
        String err = stderrTail(tail);
        if (!err.isEmpty()) out.append("\nstderr:\n").append(err);
        return out.toString();
    }

    @Override
    public String toString() {
        return "ExecResult{exit=" + exitCode + ", timedOut=" + timedOut + ", command=" + command + "}";
    }
}
