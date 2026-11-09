package com.droiddeck.launcher.runtime.container;

import org.junit.Assert;
import org.junit.Test;

public class ContainerSessionTest {
    @Test
    public void shutdownLineCarriesTheExitCode() {
        Assert.assertEquals(Integer.valueOf(0), ContainerSession.exitCodeOf(
                "[2026-11-09 12:00:00.000] [monitor] Detected container SHUTDOWN (exit: 0)"));
        Assert.assertEquals(Integer.valueOf(7), ContainerSession.exitCodeOf(
                "[2026-11-09 12:00:00.000] [monitor] Detected container SHUTDOWN (exit: 7)"));
    }

    @Test
    public void signalDeathMapsToTheShellConvention() {
        Assert.assertEquals(Integer.valueOf(137), ContainerSession.exitCodeOf(
                "[2026-11-09 12:00:00.000] [monitor] Intermediate killed by signal: 9 (Killed)"));
        Assert.assertEquals(Integer.valueOf(143), ContainerSession.exitCodeOf(
                "Intermediate killed by signal: 15 (Terminated)"));
    }

    @Test
    public void unrelatedLinesHaveNoExitCode() {
        Assert.assertNull(ContainerSession.exitCodeOf(null));
        Assert.assertNull(ContainerSession.exitCodeOf(
                "[2026-11-09 12:00:00.000] [main] Container 'droiddeck' started"));
        Assert.assertNull(ContainerSession.exitCodeOf("Detected container SHUTDOWN (exit: nope)"));
    }
}
