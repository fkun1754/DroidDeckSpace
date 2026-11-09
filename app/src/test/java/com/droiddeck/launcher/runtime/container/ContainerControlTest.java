package com.droiddeck.launcher.runtime.container;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class ContainerControlTest {
    @Test
    public void psLinesAreParsed() {
        String output = "1 0 10454 S droiddeck-session /usr/local/bin/droiddeck-session steam\n"
                + "42 1 10454 Sl gamescope gamescope --backend wayland -e\n"
                + "77 42 10454 Z steam steam -bigpicture\n"
                + "garbage line\n"
                + "\n";
        List<ContainerControl.GuestProcess> processes = ContainerControl.parsePs(output);
        Assert.assertEquals(3, processes.size());
        Assert.assertEquals(1, processes.get(0).pid);
        Assert.assertEquals(0, processes.get(0).ppid);
        Assert.assertEquals(10454, processes.get(0).uid);
        Assert.assertEquals("S", processes.get(0).stat);
        Assert.assertEquals("droiddeck-session", processes.get(0).comm);
        Assert.assertEquals("/usr/local/bin/droiddeck-session steam", processes.get(0).args);
        Assert.assertEquals("gamescope", processes.get(1).comm);
        Assert.assertTrue(processes.get(2).zombie());
    }

    @Test
    public void argsKeepTheirSpaces() {
        ContainerControl.GuestProcess process = ContainerControl.parsePsLine(
                " 12   1  10454 S proot -b /a b:/c d -- /bin/true ");
        Assert.assertNotNull(process);
        Assert.assertEquals(12, process.pid);
        Assert.assertEquals("-b /a b:/c d -- /bin/true", process.args);
    }

    @Test
    public void signalNamesMatchTheCtlProtocol() {
        Assert.assertEquals("TERM", ContainerControl.signalName(15));
        Assert.assertEquals("KILL", ContainerControl.signalName(9));
        Assert.assertEquals("STOP", ContainerControl.signalName(19));
        Assert.assertEquals("CONT", ContainerControl.signalName(18));
        Assert.assertEquals("INT", ContainerControl.signalName(2));
        Assert.assertEquals("HUP", ContainerControl.signalName(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownSignalsAreRejected() {
        ContainerControl.signalName(3);
    }
}
