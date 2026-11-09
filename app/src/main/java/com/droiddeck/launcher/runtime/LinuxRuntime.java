package com.droiddeck.launcher.runtime;

import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import com.droiddeck.launcher.gpu.SystemVulkanDriver;
import com.droiddeck.launcher.runtime.container.ContainerPaths;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class LinuxRuntime {
    public static final String DIR = "linuxfs";
    public static final String SESSION_SCRIPT = "/usr/local/bin/droiddeck-session";
    public static final String MODE_DESKTOP = "desktop";
    public static final String MODE_STEAM = "steam";
    public static final String MODE_RUN = "run";
    public static final String EXTRA_LINUX_MODE = "linux_mode";
    private static final String KGSL_DEVICE = "/dev/kgsl-3d0";
    public static final String GUEST_RUNTIME_DIR = "/run/droiddeck";
    public static final String GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games";
    private static final String SYSTEM_FONTS = "/system/fonts";
    private static final String GUEST_SYSTEM_FONTS = "/usr/local/share/fonts/android";
    public static final String DEBUG_LOG_DIR = "DroidDeck";

    public static File logDir(Context context) {
        return new File(context.getFilesDir(), "logs");
    }

    public static File legacyLogDir() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), DEBUG_LOG_DIR);
    }

    private LinuxRuntime() {}

    public static File rootDir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    public static File sessionRoot(Context context) {
        return new File(context.getFilesDir(), "session");
    }

    public static File liveDir(Context context) {
        File dir = new File(context.getNoBackupFilesDir(), "guest-runtime");
        dir.mkdirs();
        return dir;
    }

    /** Host side of the container's /run/droiddeck-hud bind. */
    public static File hudDir(Context context) {
        File dir = new File(context.getFilesDir(), "hud");
        dir.mkdirs();
        return dir;
    }

    public static boolean isInstalled(Context context) {
        return hasRootfs(context);
    }

    public static boolean hasRootfs(Context context) {
        if (LinuxRuntimeInstaller.isRemoving() || LinuxRuntimeInstaller.hasRemovalPending(context)) return false;
        File root = rootDir(context);
        return (new File(root, "usr/bin/gamescope").isFile() || new File(root, "usr/games/gamescope").isFile())
                && (new File(root, SESSION_SCRIPT.substring(1)).isFile()
                        || new File(root, LegacyNames.SESSION_SCRIPT.substring(1)).isFile());
    }

    public static File vulkanIcd(Context context) {
        File icdDir = new File(rootDir(context), "usr/share/vulkan/icd.d");
        File[] manifests = icdDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (manifests == null) return null;
        for (File manifest : manifests) {
            if (manifest.getName().contains("freedreno")) return manifest;
        }
        return manifests.length > 0 ? manifests[0] : null;
    }

    /**
     * Compatibility wrapper for callers that still start a guest command as a host process.
     * The returned argv runs the command inside the container as root through `su`; bind
     * arguments are ignored because the container's mount set is fixed when it starts.
     */
    public static List<String> command(Context context, File sessionRoot, File runtimeDir,
                                       File externalStorage, List<String> guestCommand) {
        return command(context, sessionRoot, runtimeDir, externalStorage, null, guestCommand);
    }

    public static List<String> command(Context context, File sessionRoot, File runtimeDir,
                                       File externalStorage, List<String> extraBinds,
                                       List<String> guestCommand) {
        return containerCommand(guestCommand);
    }

    public static List<String> commandWithBinds(Context context, File root, List<String> binds,
                                                List<String> guestCommand) {
        return containerCommand(guestCommand);
    }

    /** argv for `su -c "<droidspaces run ...>"`, suitable for ProcessBuilder/HostProcess.start. */
    public static List<String> containerCommand(List<String> guestCommand) {
        List<String> cmd = new ArrayList<>();
        cmd.add(suBinary());
        cmd.add("-c");
        cmd.add(containerCommandLine(guestCommand));
        return cmd;
    }

    /** Shell command line that runs [guestCommand] inside the container as root. */
    public static String containerCommandLine(List<String> guestCommand) {
        List<String> argv = new ArrayList<>();
        argv.add(ContainerPaths.CLI);
        argv.add("-n");
        argv.add(ContainerPaths.CONTAINER_NAME);
        argv.add("run");
        argv.add("--");
        argv.addAll(guestCommand);
        return shellJoin(argv);
    }

    public static String suBinary() {
        for (String path : new String[]{"/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su"}) {
            if (new File(path).canExecute()) return path;
        }
        return "su";
    }

    public static String shellQuote(String value) {
        if (value.isEmpty()) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String shellJoin(List<String> argv) {
        StringBuilder out = new StringBuilder();
        for (String arg : argv) {
            if (out.length() > 0) out.append(' ');
            out.append(shellQuote(arg));
        }
        return out.toString();
    }

    private static volatile List<String> lastBinds;

    /** Container-view bind specs of the running session; guest path -> host path mapping. */
    public static void setLastBinds(List<String> binds) {
        lastBinds = binds == null ? null : new ArrayList<>(binds);
    }

    public static List<String> lastBinds(Context context) {
        List<String> binds = lastBinds;
        return binds != null ? binds : new ArrayList<>();
    }

    /**
     * Bind specs for the host-side emulation files the container does not provide itself
     * (fake proc/sysfs entries, thermal and GPU stats, backlight). Base mounts (/dd, /gd,
     * /run/droiddeck*, /dev, /proc, /sys, fonts, external storage, /dev/shm and the fake
     * DRM tree) are owned by the container runtime and must not be repeated here.
     */
    public static List<String> binds(Context context, File sessionRoot, File runtimeDir,
                                     File externalStorage, List<String> extraBinds) {
        File root = rootDir(context);
        LegacyNames.migrateEtc(root);
        List<String> specs = new ArrayList<>();
        if (com.droiddeck.launcher.gpu.SystemVulkanDriver.isPowerVr()) {
            // The glibc bridge loads the device's existing Android HAL and its dependencies.
            // These read-only Android partitions remain subject to the app's normal permissions.
            for (String path : new String[]{"/system", "/vendor", "/apex", "/system_ext", "/product", "/odm"})
                if (new File(path).isDirectory()) specs.add(path + ":" + path);
            if (new File("/linkerconfig/ld.config.txt").isFile()) specs.add("/linkerconfig/ld.config.txt:/linkerconfig/ld.config.txt");
        }
        specs.add(DeviceControlsComponent.prepareBacklight(context, root).getPath() + ":/sys/class/backlight");
        specs.add("/dev/urandom:/dev/random");
        // A kernel that refuses to list /sys/class/thermal (Pixel 10, Android 17) made mangoapp abort
        // at start ("filesystem_error ... Permission denied: '/sys/class/thermal/'") until its
        // wrapper gave up: it lists the directory for CPU temperatures. Empty there instead.
        if (new File("/sys/class/thermal").list() == null) specs.add(new File(root, "etc/droiddeck/empty").getPath() + ":/sys/class/thermal");

        File fakeProc = new File(root, "etc/droiddeck/proc");
        File pciDevices = new File(fakeProc, "pci_devices");
        if (!pciDevices.isFile()) {
            try {
                pciDevices.getParentFile().mkdirs();
                pciDevices.createNewFile();
            } catch (java.io.IOException e) {
            }
        }
        String[][] seeded = {
                {"file-max", "9223372036854775807\n"},
                {"overcommit_memory", "0\n"},
        };
        for (String[] entry : seeded) {
            File fake = new File(fakeProc, entry[0]);
            if (fake.isFile()) continue;
            try {
                Files.write(fake.toPath(), entry[1].getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
            }
        }
        String[][] procFiles = {
                {"pci_devices", "/proc/bus/pci/devices"},
                {"stat", "/proc/stat"},
                {"version", "/proc/version"},
                {"loadavg", "/proc/loadavg"},
                {"uptime", "/proc/uptime"},
                {"vmstat", "/proc/vmstat"},
                {"cap_last_cap", "/proc/sys/kernel/cap_last_cap"},
                {"overflowuid", "/proc/sys/kernel/overflowuid"},
                {"overflowgid", "/proc/sys/kernel/overflowgid"},
                {"file-max", "/proc/sys/fs/file-max"},
                {"overcommit_memory", "/proc/sys/vm/overcommit_memory"},
        };
        for (String[] entry : procFiles) {
            File fake = new File(fakeProc, entry[0]);
            boolean live = false;
            if (extraBinds != null) for (String spec : extraBinds) live |= spec.endsWith(":" + entry[1]);
            if (!live && fake.isFile() && !new File(entry[1]).canRead()) {
                specs.add(fake.getPath() + ":" + entry[1]);
            }
        }
        if (unreadablePciDevice()) {
            specs.add(new File(root, "etc/droiddeck/empty").getPath() + ":/sys/bus/pci/devices");
        }
        if (com.droiddeck.launcher.gpu.SystemVulkanDriver.isPowerVr()) {
            specs.addAll(powerVrGpuNode(context));
        }
        specs.addAll(adrenoStats(extraBinds));
        specs.addAll(cpuTemps(root));
        if (extraBinds != null) {
            for (String spec : extraBinds) specs.add(spec);
        }
        return specs;
    }

    private static List<String> powerVrGpuNode(Context context) {
        List<String> specs = new ArrayList<>();
        if (new File("/dev/dri").list() != null) return specs;
        String node = SystemVulkanDriver.renderNodePath();
        if (node == null) return specs;
        String name = new File(node).getName();
        File dri = new File(context.getCacheDir(), "powervr-drm/" + name + "/dri");
        try {
            if (!dri.isDirectory() && !dri.mkdirs()) return specs;
            new File(dri, name).createNewFile();
        } catch (IOException e) {
            android.util.Log.w("LinuxRuntime", "Cannot expose the PowerVR render node", e);
            return specs;
        }
        specs.add(dri.getPath() + ":/dev/dri");
        specs.add(node + ":" + node);
        android.util.Log.i("LinuxRuntime", "PowerVR: exposing real " + name + " through a listable directory");
        return specs;
    }

    private static boolean unreadablePciDevice() {
        File[] devices = new File("/sys/bus/pci/devices").listFiles();
        if (devices == null) return false;
        for (File device : devices) {
            if (!new File(device, "vendor").canRead()) return true;
        }
        return false;
    }

    public static final String MALI_DEVICE = "/dev/mali0";

    static final String[] DRM_RENDER_NODES = {
        "/dev/dri/renderD128", "/dev/dri/renderD129", "/dev/dri/renderD130", "/dev/dri/renderD131",
    };

    public static String gpuStandIn(Context context) {
        if (new File(KGSL_DEVICE).exists()) return KGSL_DEVICE;
        if (com.droiddeck.launcher.core.DeviceSupport.INSTANCE.openDriverActive(context) && new File(MALI_DEVICE).exists()) return MALI_DEVICE;
        if (!com.droiddeck.launcher.core.DeviceSupport.INSTANCE.vkBridge(context)) return null;
        return bridgeStandIn(path -> new File(path).exists());
    }

    static String bridgeStandIn(java.util.function.Predicate<String> exists) {
        if (exists.test(MALI_DEVICE)) return MALI_DEVICE;
        for (String node : DRM_RENDER_NODES) if (exists.test(node)) return node;
        return null;
    }

    public static String deviceNumbers(String path) {
        try {
            long dev = Os.stat(path).st_rdev;
            long major = ((dev >> 8) & 0xfff) | ((dev >> 32) & ~0xfffL);
            long minor = (dev & 0xff) | ((dev >> 12) & ~0xffL);
            return major + ":" + minor;
        } catch (ErrnoException e) {
            return null;
        }
    }

    public static String cpuTempSource() {
        for (File zone : cpuTempZones()) {
            File temp = new File(zone, "temp");
            if (temp.canRead()) return temp.getPath();
        }
        return null;
    }

    public static String gpuTempSource() {
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        return firstReadable(kgsl + "temp", kgsl + "devfreq/temp", thermalZone("gpu"));
    }

    public static String gpuLoadSource() {
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        return firstReadable(kgsl + "gpu_busy_percentage", kgsl + "devfreq/gpu_load");
    }

    private static List<String> adrenoStats(List<String> extraBinds) {
        List<String> specs = new ArrayList<>();
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        String gpuTemp = gpuTempSource();
        String[][] stats = {
                {gpuLoadSource(), "/sys/kernel/debug/dri/0/perf_now"},
                {firstReadable(kgsl + "devfreq/cur_freq", kgsl + "gpuclk"),
                        "/sys/devices/platform/soc@0/3d00000.gpu/devfreq/3d00000.gpu/cur_freq"},
                {gpuTemp, "/sys/class/thermal/thermal_zone28/temp"},
                {gpuTemp, "/sys/class/thermal/thermal_zone26/temp"},
                {ddrTempSource(), "/sys/class/thermal/thermal_zone22/temp"},
        };
        for (String[] stat : stats) {
            boolean fed = false;
            if (extraBinds != null) for (String spec : extraBinds) fed |= spec.endsWith(":" + stat[1]);
            if (stat[0] != null && !fed) specs.add(stat[0] + ":" + stat[1]);
        }
        android.util.Log.i("LinuxRuntime", "hud sources: gpu load " + orNone(stats[0][0]) + ", gpu clock "
                + orNone(stats[1][0]) + ", gpu temp " + orNone(gpuTemp) + ", ddr temp " + orNone(stats[4][0])
                + ", thermal zones " + thermalZones().size()
                + ", kgsl gpubusy " + (new File(kgsl + "gpubusy").canRead() ? "readable" : "refused"));
        return specs;
    }

    private static String orNone(String path) {
        return path != null ? path : "none";
    }

    private static String ddrTempSource() {
        return thermalZone("ddr");
    }

    private static java.util.Map<String, String> replacedZoneTypes() {
        java.util.Map<String, String> replaced = new java.util.HashMap<>();
        if (gpuTempSource() != null) {
            replaced.put("thermal_zone28", "gpu-thermal");
            replaced.put("thermal_zone26", "gpu-thermal");
        }
        if (ddrTempSource() != null) replaced.put("thermal_zone22", "ddr-thermal");
        return replaced;
    }

    public static List<File> cpuTempZones() {
        java.util.Map<String, String> replaced = replacedZoneTypes();
        List<File> zones = new ArrayList<>();
        for (java.util.Map.Entry<String, File> zone : new java.util.TreeMap<>(thermalZones()).entrySet()) {
            String type = zone.getKey();
            if (type.contains("cpu") && !type.contains("gpu") && !replaced.containsKey(zone.getValue().getName())) {
                zones.add(zone.getValue());
            }
        }
        return zones;
    }

    private static List<String> cpuTemps(File root) {
        List<String> specs = new ArrayList<>();
        File name = new File(root, "etc/droiddeck/cpu-thermal-type");
        try {
            if (!name.isFile()) Files.write(name.toPath(), "cpu0-thermal\n".getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            return specs;
        }
        java.util.Map<String, String> replaced = replacedZoneTypes();
        for (java.util.Map.Entry<String, File> zone : thermalZones().entrySet()) {
            String type = zone.getKey();
            String other = replaced.get(zone.getValue().getName());
            if (other != null) {
                File typeFile = new File(root, "etc/droiddeck/" + other + "-type");
                try {
                    if (!typeFile.isFile()) Files.write(typeFile.toPath(), (other + "\n").getBytes(StandardCharsets.US_ASCII));
                    specs.add(typeFile.getPath() + ":" + new File(zone.getValue(), "type").getPath());
                } catch (IOException ignored) {
                }
            } else if (type.contains("cpu") && !type.contains("gpu") && !type.matches("cpu\\d-(top-)?thermal")) {
                specs.add(name.getPath() + ":" + new File(zone.getValue(), "type").getPath());
            }
        }
        return specs;
    }

    private static String firstReadable(String... paths) {
        for (String path : paths) {
            if (path != null && new File(path).canRead()) return path;
        }
        return null;
    }

    private static String thermalZone(String sensor) {
        java.util.Map<String, File> zones = thermalZones();
        File zone = zones.get(sensor);
        for (java.util.Map.Entry<String, File> e : new java.util.TreeMap<>(zones).entrySet()) {
            if (zone == null && e.getKey().contains(sensor)) zone = e.getValue();
        }
        return zone != null ? new File(zone, "temp").getPath() : null;
    }

    private static java.util.Map<String, File> thermalZones() {
        java.util.Map<String, File> byType = new java.util.HashMap<>();
        File[] zones = new File("/sys/class/thermal").listFiles((dir, n) -> n.startsWith("thermal_zone"));
        if (zones == null) return byType;
        for (File zone : zones) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new File(zone, "type")))) {
                byType.putIfAbsent(String.valueOf(r.readLine()).trim().toLowerCase(java.util.Locale.ROOT), zone);
            } catch (IOException ignored) {
            }
        }
        return byType;
    }

    public static synchronized void writeAccounts(Context context) throws IOException {
        File root = rootDir(context);
        int uid = Process.myUid();
        mergeAccounts(new File(root, "etc/passwd"), 2,
                "root:x:" + uid + ":" + uid + ":root:/root:/bin/bash", "root:x:0:0:root:/root:/bin/bash", uid);
        mergeAccounts(new File(root, "etc/group"), 2,
                "root:x:" + uid + ":", "root:x:0:", uid);
        File group = new File(root, "etc/group");
        String groups = new String(Files.readAllBytes(group.toPath()), StandardCharsets.UTF_8);
        writeIfChanged(group, groups, withStorageGroups(groups));
    }

    private static final String[][] STORAGE_GROUPS = {
            {"sdcard_rw", "1015"}, {"media_rw", "1023"}, {"everybody", "9997"}};

    static String withStorageGroups(String groups) {
        StringBuilder out = new StringBuilder();
        List<String> present = new ArrayList<>();
        for (String line : groups.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split(":", -1);
            if (fields.length >= 4) {
                for (String[] storage : STORAGE_GROUPS) {
                    if (fields[0].equals(storage[0])) present.add(storage[0]);
                    if (!fields[2].equals(storage[1])) continue;
                    present.add(storage[0]);
                    if (!java.util.Arrays.asList(fields[3].split(",")).contains("root")) {
                        fields[3] = fields[3].isEmpty() ? "root" : fields[3] + ",root";
                        line = String.join(":", fields);
                    }
                }
            }
            out.append(line).append('\n');
        }
        for (String[] storage : STORAGE_GROUPS) {
            if (!present.contains(storage[0])) {
                out.append(storage[0]).append(":x:").append(storage[1]).append(":root\n");
            }
        }
        return out.toString();
    }

    static String mergedAccounts(String existing, int idField, String own, String zero, int uid) {
        StringBuilder out = new StringBuilder(own).append('\n').append(zero).append('\n');
        if (existing == null) return out.toString();
        String ownId = Integer.toString(uid);
        for (String line : existing.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split(":", -1);
            if (fields.length <= idField) continue;
            if (fields[0].equals("root") || fields[idField].equals("0") || fields[idField].equals(ownId)) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void mergeAccounts(File file, int idField, String own, String zero, int uid) throws IOException {
        String existing = file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : null;
        writeIfChanged(file, existing, mergedAccounts(existing, idField, own, zero, uid));
    }

    private static void writeIfChanged(File file, String existing, String merged) throws IOException {
        if (merged.equals(existing)) return;
        File staged = File.createTempFile(file.getName(), ".staged", file.getParentFile());
        Files.write(staged.toPath(), merged.getBytes(StandardCharsets.UTF_8));
        Files.move(staged.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
