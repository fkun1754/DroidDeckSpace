package com.droiddeck.launcher.runtime.container;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

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
