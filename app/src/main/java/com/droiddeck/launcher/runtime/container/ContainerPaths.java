package com.droiddeck.launcher.runtime.container;

import java.io.File;

/**
 * Container identities and guest paths. Guest paths are the container-view paths from
 * docs/development/container-runtime.md §3.1: App data is only reachable through the short
 * bind destinations (/dd, /gd), never through its host path.
 */
public final class ContainerPaths {
    public static final String LEGACY_CONTAINER_NAME = "droiddeck";

    /** Derived per application id so coexisting builds (variants, debug) never share a container. */
    public static final String CONTAINER_NAME = "droiddeck." + com.droiddeck.launcher.BuildConfig.APPLICATION_ID;

    public static final String CLI = "/data/local/Droidspaces/bin/droidspaces";
    public static final String WORKSPACE = "/data/local/Droidspaces";
    public static final String LOGS = WORKSPACE + "/Logs";

    public static final String GUEST_APP = "/dd";
    public static final String GUEST_GUEST_RUNTIME = "/gd";
    public static final String GUEST_RUNTIME = "/run/droiddeck";
    public static final String GUEST_HUD = "/run/droiddeck-hud";
    public static final String GUEST_SESSION_ENV = GUEST_APP + "/container/session.env";
    public static final String GUEST_SESSION_ARGS = GUEST_APP + "/container/session.args";
    public static final String GUEST_ICD = GUEST_APP + "/container/vulkan-icd.json";
    public static final String GUEST_SYSFS_BINDS = GUEST_APP + "/container/sysfs-binds";
    public static final String GUEST_INIT = "/usr/local/bin/droiddeck-container-init";
    public static final String GUEST_SESSION = "/usr/local/bin/droiddeck-session";
    public static final String GUEST_CTL = "/usr/local/bin/droiddeck-container-ctl";
    public static final String GUEST_READY = GUEST_RUNTIME + "/ready";
    public static final String GUEST_STORAGE = "/root/Storage";
    public static final String GUEST_FONTS = "/usr/local/share/fonts/android";
    public static final String GUEST_SHM = "/dev/shm";
    public static final String GUEST_DRI = "/dev/dri";
    public static final String GUEST_CHAR_SYSFS = "/sys/dev/char";
    public static final String GUEST_INPUT = "/dev/input";

    public static final String HOST_ROOTFS_DIR = "linuxfs";
    public static final String HOST_RUNTIME_DIR = ".wayland-rt";
    public static final String HOST_HUD_DIR = "hud";
    public static final String HOST_CONTAINER_DIR = "container";
    public static final String HOST_GUEST_RUNTIME = "guest-runtime";
    public static final String HOST_EXTERNAL_STORAGE = "/storage/emulated/0";
    public static final String HOST_SYSTEM_FONTS = "/system/fonts";
    public static final String HOST_GPU_KGSL = "/dev/kgsl-3d0";
    public static final String HOST_GPU_MALI = "/dev/mali0";

    private ContainerPaths() {}

    public static File filesDir(android.content.Context context) {
        return context.getFilesDir();
    }

    public static File noBackupDir(android.content.Context context) {
        return context.getNoBackupFilesDir();
    }

    public static File rootfs(android.content.Context context) {
        return new File(filesDir(context), HOST_ROOTFS_DIR);
    }

    public static File sessionDir(android.content.Context context) {
        return new File(filesDir(context), "session");
    }

    public static File waylandDir(android.content.Context context) {
        return new File(filesDir(context), HOST_RUNTIME_DIR);
    }

    public static File hudDir(android.content.Context context) {
        return new File(filesDir(context), HOST_HUD_DIR);
    }

    public static File containerDir(android.content.Context context) {
        return new File(filesDir(context), HOST_CONTAINER_DIR);
    }

    public static File guestRuntimeDir(android.content.Context context) {
        return new File(noBackupDir(context), HOST_GUEST_RUNTIME);
    }

    public static File sessionEnv(android.content.Context context) {
        return new File(containerDir(context), "session.env");
    }

    public static File sessionArgs(android.content.Context context) {
        return new File(containerDir(context), "session.args");
    }

    public static File vulkanIcd(android.content.Context context) {
        return new File(containerDir(context), "vulkan-icd.json");
    }

    /** One {@code SRC|DEST} per line; the guest init applies these after overlaying /sys. */
    public static File sysfsBinds(android.content.Context context) {
        return new File(containerDir(context), "sysfs-binds");
    }

    public static File containerLog(String name) {
        return new File(new File(LOGS, name), "log");
    }

    public static File consoleLog(String name) {
        return new File(new File(LOGS, name), "console");
    }

    public static String bind(String source, String destination) {
        return source + ":" + destination;
    }

    public static String bindRo(String source, String destination) {
        return source + ":" + destination + ":ro";
    }
}
