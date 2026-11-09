package com.droiddeck.launcher.runtime.container;

import org.junit.Assert;
import org.junit.Test;

public class ContainerRuntimeTest {
    @Test
    public void containerStartSpecForcesADeterministicHostname() {
        DroidspacesCli.ContainerSpec spec = DroidspacesCli.ContainerSpec
                .builder("droiddeck", new java.io.File("/data/user/0/pkg/files/linuxfs"))
                .build();
        Assert.assertEquals("droiddeck", spec.hostname());
        Assert.assertEquals("ddtest", DroidspacesCli.ContainerSpec
                .builder("droiddeck", new java.io.File("/r"))
                .hostname("ddtest")
                .build()
                .hostname());
    }

    @Test
    public void maintenanceModeIsReadFromTheSessionManifest() {
        String manifest = "DD_UID='10454'\nDD_GID='10454'\nDD_MODE='service'\nDD_SESSION='service'\n";
        Assert.assertEquals("service", ContainerRuntime.envValue(manifest, "DD_MODE"));
        Assert.assertEquals("10454", ContainerRuntime.envValue(manifest, "DD_UID"));
        Assert.assertNull(ContainerRuntime.envValue(manifest, "DD_MISSING"));
        Assert.assertNull(ContainerRuntime.envValue(null, "DD_MODE"));
    }

    @Test
    public void sessionModeIsNotMaintenance() {
        Assert.assertEquals("lxqt",
                ContainerRuntime.envValue("DD_MODE='lxqt'\n", "DD_MODE"));
        Assert.assertEquals("it's",
                ContainerRuntime.envValue("DD_NAME='it'\\''s'\n", "DD_NAME"));
    }
}
