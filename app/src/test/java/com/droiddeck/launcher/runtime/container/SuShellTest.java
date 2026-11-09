package com.droiddeck.launcher.runtime.container;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SuShellTest {
    @Test
    public void plainArgumentsAreSingleQuoted() {
        Assert.assertEquals("'droidspaces'", SuShell.quote("droidspaces"));
        Assert.assertEquals("'--bind=/a b:/c d'", SuShell.quote("--bind=/a b:/c d"));
        Assert.assertEquals("''", SuShell.quote(""));
        Assert.assertEquals("''", SuShell.quote(null));
    }

    @Test
    public void singleQuotesSurviveTheShell() {
        Assert.assertEquals("'a'\\''b'", SuShell.quote("a'b"));
    }

    @Test
    public void commandJoinsQuotedArguments() {
        List<String> argv = Arrays.asList("droidspaces", "-n", "droiddeck", "-r", "/data/a b/linuxfs",
                "--bind=/data/My Files/x:/dd", "start");
        Assert.assertEquals("'droidspaces' '-n' 'droiddeck' '-r' '/data/a b/linuxfs' "
                + "'--bind=/data/My Files/x:/dd' 'start'", SuShell.command(argv));
        Assert.assertEquals("'a' 'b'", SuShell.command("a", "b"));
    }

    @Test
    public void watchdogKillsALongCommandAtItsDeadline() throws Exception {
        Assume.assumeTrue(new File("/bin/sh").isFile());
        long started = System.nanoTime();
        Process process = new ProcessBuilder("/bin/sh", "-c",
                SuShell.watchdogScript("sleep 30", 400)).start();
        Assert.assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        Assert.assertTrue("watchdog should stop the wrapper early, took " + elapsedMs + "ms",
                elapsedMs < 8_000);
        Assert.assertTrue("watchdog marker missing: " + stderr,
                stderr.contains("__DROIDDECK_WATCHDOG_TIMEOUT__"));
    }

    @Test
    public void watchdogPreservesStdin() throws Exception {
        Assume.assumeTrue(new File("/bin/sh").isFile());
        Process process = new ProcessBuilder("/bin/sh", "-c",
                SuShell.watchdogScript("cat", 5_000)).start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write("hello watchdog\n".getBytes(StandardCharsets.UTF_8));
        }
        Assert.assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assert.assertEquals(0, process.exitValue());
        Assert.assertEquals("hello watchdog\n", stdout);
    }

    @Test
    public void watchdogKeepsTheCommandExitCode() throws Exception {
        Assume.assumeTrue(new File("/bin/sh").isFile());
        Process process = new ProcessBuilder("/bin/sh", "-c",
                SuShell.watchdogScript("exit 7", 5_000)).start();
        Assert.assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        Assert.assertEquals(7, process.exitValue());
    }

    @Test
    public void watchdogPassesThroughOutputOfFastCommands() throws Exception {
        Assume.assumeTrue(new File("/bin/sh").isFile());
        Process process = new ProcessBuilder("/bin/sh", "-c",
                SuShell.watchdogScript("echo fast", 5_000)).start();
        Assert.assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assert.assertEquals(0, process.exitValue());
        Assert.assertEquals("fast\n", stdout);
    }

    @Test
    public void quotedCommandRoundTripsThroughSh() throws Exception {
        Assume.assumeTrue(new File("/bin/sh").isFile());
        List<String> argv = Arrays.asList("printf", "%s\n", "it's a '$HOME' \"test\"", "", "a b");
        String script = "printf '%s\\n' " + SuShell.command(argv);
        Process process = new ProcessBuilder("/bin/sh", "-c", script).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        Assert.assertEquals(0, process.waitFor());
        Assert.assertEquals("printf\n%s\n\nit's a '$HOME' \"test\"\n\na b\n", output.toString());
    }
}
