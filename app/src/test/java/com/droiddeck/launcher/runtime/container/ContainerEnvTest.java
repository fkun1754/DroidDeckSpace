package com.droiddeck.launcher.runtime.container;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ContainerEnvTest {
    private static final String APP = "/data/user/0/com.droiddeck.launcher/files";
    private static final String NO_BACKUP = "/data/user/0/com.droiddeck.launcher/no_backup";
    private static final ContainerEnv.Paths PATHS = new ContainerEnv.Paths(
            APP,
            APP + "/linuxfs",
            NO_BACKUP + "/guest-runtime",
            APP + "/.wayland-rt",
            APP + "/hud",
            "/storage/emulated/0");

    @Test
    public void appDataMapsToShortGuestPaths() {
        Assert.assertEquals("/dd/session/android-clipboard",
                ContainerEnv.translate(APP + "/session/android-clipboard", PATHS));
        Assert.assertEquals("/dd",
                ContainerEnv.translate(APP, PATHS));
        Assert.assertEquals("/gd/shm",
                ContainerEnv.translate(NO_BACKUP + "/guest-runtime/shm", PATHS));
        Assert.assertEquals("/run/droiddeck/wayland-0",
                ContainerEnv.translate(APP + "/.wayland-rt/wayland-0", PATHS));
        Assert.assertEquals("/run/droiddeck-hud/frames",
                ContainerEnv.translate(APP + "/hud/frames", PATHS));
    }

    @Test
    public void rootfsPathsDropTheHostPrefix() {
        Assert.assertEquals("/usr/share/vulkan/icd.d/freedreno_icd.json",
                ContainerEnv.translate(APP + "/linuxfs/usr/share/vulkan/icd.d/freedreno_icd.json", PATHS));
    }

    @Test
    public void externalStorageBecomesRootStorage() {
        Assert.assertEquals("/root/Storage/Download",
                ContainerEnv.translate("/storage/emulated/0/Download", PATHS));
    }

    @Test
    public void valuesKeepTheirEnvelope() {
        Assert.assertEquals("unix:/dd/pulseaudio/PS0",
                ContainerEnv.translate("unix:" + APP + "/pulseaudio/PS0", PATHS));
        Assert.assertEquals("/dd/logs/a.log:/dd/logs/b.log",
                ContainerEnv.translate(APP + "/logs/a.log:" + APP + "/logs/b.log", PATHS));
        Assert.assertEquals("C.UTF-8", ContainerEnv.translate("C.UTF-8", PATHS));
        Assert.assertEquals("/usr/bin/env", ContainerEnv.translate("/usr/bin/env", PATHS));
        Assert.assertEquals("org.freedesktop.portal", ContainerEnv.translate("org.freedesktop.portal", PATHS));
    }

    @Test
    public void prefixMatchesRequireAPathBoundary() {
        Assert.assertEquals(APP + "2/session", ContainerEnv.translate(APP + "2/session", PATHS));
        Assert.assertEquals(APP + "x", ContainerEnv.translate(APP + "x", PATHS));
    }

    @Test
    public void envFileLinesAreShellQuoted() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PULSE_SERVER", "unix:" + APP + "/pulseaudio/PS0");
        env.put("XDG_RUNTIME_DIR", "/run/droiddeck");
        env.put("BL_LOG", APP + "/logs/session.log");
        env.put("BAD-KEY", "skipped");
        env.put("BL_QUOTE", "it's");
        List<String> lines = ContainerEnv.envLines(env, PATHS);
        Assert.assertEquals(Arrays.asList(
                "PULSE_SERVER='unix:/dd/pulseaudio/PS0'",
                "XDG_RUNTIME_DIR='/run/droiddeck'",
                "BL_LOG='/dd/logs/session.log'",
                "BL_QUOTE='it'\\''s'"), lines);
    }

    @Test
    public void icdManifestIsContainerView() {
        String manifest = ContainerEnv.icdManifest(
                "/dd/linux_vulkan_drivers/turnip26.3.0-a6xx/libvulkan_freedreno.so", null);
        Assert.assertEquals("{\"file_format_version\":\"1.0.0\",\"ICD\":{\"library_path\":"
                + "\"/dd/linux_vulkan_drivers/turnip26.3.0-a6xx/libvulkan_freedreno.so\","
                + "\"api_version\":\"1.1.274\"}}\n", manifest);
        String withApi = ContainerEnv.icdManifest("/dd/x/libvulkan_panfrost.so", "1.3.290");
        Assert.assertTrue(withApi.contains("\"api_version\":\"1.3.290\""));
    }

    @Test
    public void bindDestinationIsParsedAndDeduplicated() {
        Assert.assertEquals("/dd", ContainerEnv.destinationOf("/data/files:/dd"));
        Assert.assertEquals("/dev/shm", ContainerEnv.destinationOf("/data/shm:/dev/shm"));
        Assert.assertEquals("/usr/local/share/fonts/android",
                ContainerEnv.destinationOf("/system/fonts:/usr/local/share/fonts/android:ro"));
        Assert.assertNull(ContainerEnv.destinationOf("/data/plain"));
        Assert.assertNull(ContainerEnv.destinationOf("relative:/dd"));

        List<String> deduped = ContainerEnv.dedupeBinds(Arrays.asList(
                "/data/files:/dd",
                "/data/shm:/dev/shm",
                "/other/files:/dd"));
        Assert.assertEquals(Arrays.asList("/other/files:/dd", "/data/shm:/dev/shm"), deduped);
    }
}
