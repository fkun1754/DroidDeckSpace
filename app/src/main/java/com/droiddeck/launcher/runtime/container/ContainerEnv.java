package com.droiddeck.launcher.runtime.container;

import android.content.Context;
import android.os.Environment;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.droiddeck.launcher.runtime.LinuxRuntime;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the guest session manifest ({@code session.env}/{@code session.args}/ICD) and computes
 * the DroidSpaces bind list. Guest paths are short container-local paths; see
 * docs/development/container-runtime.md §2.4 and §3.1.
 */
public final class ContainerEnv {
    private static final String TAG = "DroidDeckContainer";
    private static final int DIR_MODE = 0755;
    private static final String LINUX_DRIVERS = "linux_vulkan_drivers";
    private static final String GRAPHICS_DRIVERS = "graphics_driver";
    private static final String DEFAULT_ICD_API = "1.1.274";
    private static final java.util.regex.Pattern ENV_KEY =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private ContainerEnv() {}

    public static File hudDir(Context context) {
        return ContainerPaths.hudDir(context);
    }

    /** Host path -> container-view path for every app-data mapping the container runtime uses. */
    public static String guestPath(Context context, String hostPath) {
        return translate(hostPath, paths(context));
    }

    public static String guestPath(Context context, File hostPath) {
        return hostPath == null ? null : guestPath(context, hostPath.getPath());
    }

    public static File prepareSession(Context context, SessionSpec session) throws IOException {
        ensureDir(ContainerPaths.containerDir(context));
        ensureDir(ContainerPaths.hudDir(context));
        ensureDir(ContainerPaths.waylandDir(context));
        ensureDir(ContainerPaths.guestRuntimeDir(context));

        Paths paths = paths(context);
        Map<String, String> env = new LinkedHashMap<>();
        if (session != null) env.putAll(session.env());

        int uid = session != null && session.uid() > 0 ? session.uid() : Process.myUid();
        int gid = session != null && session.gid() > 0 ? session.gid() : uid;
        int[] groups = session == null ? null : session.groups();
        if (groups == null || groups.length == 0) groups = defaultGroups(uid);
        String mode = session != null && !session.mode().isEmpty()
                ? session.mode() : SessionSpec.MODE_SERVICE;
        String sessionId = session == null ? "" : session.sessionId();
        String sessionBin = session == null ? ContainerPaths.GUEST_SESSION : session.sessionBin();

        env.put("DD_UID", Integer.toString(uid));
        env.put("DD_GID", Integer.toString(gid));
        env.put("DD_GROUPS", joinGroups(groups));
        env.put("DD_MODE", mode);
        env.put("DD_SESSION", sessionId);
        if (!env.containsKey("DD_SESSION_BIN")) env.put("DD_SESSION_BIN", sessionBin);
        if (!env.containsKey("XDG_RUNTIME_DIR")) env.put("XDG_RUNTIME_DIR", ContainerPaths.GUEST_RUNTIME);
        if (!env.containsKey("DROIDDECK_HUD_FRAMES")) {
            env.put("DROIDDECK_HUD_FRAMES", ContainerPaths.GUEST_HUD + "/frames");
        }

        Icd icd = selectIcd(context, env, paths);
        if (icd != null) {
            env.put("VK_ICD_FILENAMES", ContainerPaths.GUEST_ICD);
            env.put("VK_DRIVER_FILES", ContainerPaths.GUEST_ICD);
        } else {
            mirrorLoaderName(env, "VK_ICD_FILENAMES", "VK_DRIVER_FILES", paths);
        }

        writeAtomically(ContainerPaths.sessionEnv(context), joinLines(envLines(env, paths)));
        List<String> args = session == null ? new ArrayList<>() : session.args();
        writeAtomically(ContainerPaths.sessionArgs(context), joinLines(sanitizeArgs(args)));
        if (icd != null) writeAtomically(ContainerPaths.vulkanIcd(context), icd.manifest());
        writeAtomically(ContainerPaths.sysfsBinds(context), joinLines(sysfsBinds(context, session)));
        return ContainerPaths.sessionEnv(context);
    }

    public static List<String> binds(Context context, SessionSpec session) {
        List<String> bindList = new ArrayList<>();
        File files = ContainerPaths.filesDir(context);
        File guestRuntime = ContainerPaths.guestRuntimeDir(context);
        ensureDirQuiet(guestRuntime);
        ensureDirQuiet(ContainerPaths.hudDir(context));
        ensureDirQuiet(ContainerPaths.waylandDir(context));

        add(bindList, files.getPath(), ContainerPaths.GUEST_APP);
        add(bindList, guestRuntime.getPath(), ContainerPaths.GUEST_GUEST_RUNTIME);
        add(bindList, ContainerPaths.waylandDir(context).getPath(), ContainerPaths.GUEST_RUNTIME);
        add(bindList, ContainerPaths.hudDir(context).getPath(), ContainerPaths.GUEST_HUD);

        File shm = new File(guestRuntime, "shm");
        ensureDirQuiet(shm);
        add(bindList, shm.getPath(), ContainerPaths.GUEST_SHM);

        prepareFakeDrm(context);
        File drm = new File(guestRuntime, "drm");
        File dri = new File(drm, "dri");
        if (dri.isDirectory()) add(bindList, dri.getPath(), ContainerPaths.GUEST_DRI);
        String gpuNode = gpuNodeBind(context);
        if (gpuNode != null) bindList.add(gpuNode);

        File input = new File(new File(ContainerPaths.sessionDir(context), "dev"), "input");
        if (input.isDirectory()) add(bindList, input.getPath(), ContainerPaths.GUEST_INPUT);

        File external = Environment.getExternalStorageDirectory();
        if (external != null && external.isDirectory()) {
            add(bindList, external.getPath(), ContainerPaths.GUEST_STORAGE);
        }

        File fonts = new File(ContainerPaths.HOST_SYSTEM_FONTS);
        File fontsParent = new File(ContainerPaths.rootfs(context), "usr/local");
        if (fonts.isDirectory() && fonts.canRead() && fontsParent.isDirectory()) {
            bindList.add(ContainerPaths.bindRo(fonts.getPath(), ContainerPaths.GUEST_FONTS));
        }

        if (session != null) {
            for (String extra : session.extraBinds()) {
                String spec = normalizeExtraBind(extra, paths(context));
                if (spec == null) continue;
                String destination = destinationOf(spec);
                if (destination != null && destination.startsWith("/sys/")) continue;
                if (!bindList.contains(spec)) bindList.add(spec);
            }
        }
        return dedupeBinds(bindList);
    }

    // ---------------------------------------------------------------- fake sysfs

    /**
     * DroidSpaces' /sys is mounted read-only, so a plain --bind onto a missing or symlinked /sys
     * target fails (EROFS or a security refusal; container-runtime.md §3.3b). The guest init
     * overlays /sys and applies this manifest itself, from container-view sources, before it
     * drops privileges. Returns {@code SRC|DEST} lines.
     */
    public static List<String> sysfsBinds(Context context, SessionSpec session) {
        Paths paths = paths(context);
        LinkedHashMap<String, String> byDestination = new LinkedHashMap<>();

        File charSysfs = new File(new File(ContainerPaths.guestRuntimeDir(context), "drm"), "sys");
        ensureDirQuiet(charSysfs);
        if (charSysfs.isDirectory()) {
            putManifest(byDestination, ContainerPaths.GUEST_GUEST_RUNTIME + "/drm/sys",
                    ContainerPaths.GUEST_CHAR_SYSFS);
        }

        File rootfs = ContainerPaths.rootfs(context);
        File backlight = new File(new File(rootfs, "etc/droiddeck-device-controls"), "backlight");
        if (backlight.isDirectory()) {
            putManifest(byDestination, translate(backlight.getPath(), paths), "/sys/class/backlight");
        }
        File empty = new File(rootfs, "etc/droiddeck/empty");
        ensureDirQuiet(empty);
        String emptyGuest = translate(empty.getPath(), paths);
        putManifest(byDestination, emptyGuest, "/sys/fs/selinux");
        if (new File("/sys/class/thermal").list() == null) {
            putManifest(byDestination, emptyGuest, "/sys/class/thermal");
        }
        if (unreadablePciDevice()) putManifest(byDestination, emptyGuest, "/sys/bus/pci/devices");

        addThermalTypeFakes(rootfs, paths, byDestination);
        addSessionSysfsDefaults(context, paths, byDestination);

        if (session != null) {
            for (String extra : session.extraBinds()) {
                String spec = normalizeExtraBind(extra, paths);
                if (spec == null) continue;
                String destination = destinationOf(spec);
                if (destination == null || !destination.startsWith("/sys/")) continue;
                String source = sourceOf(spec);
                if (source == null) continue;
                String guestSource = translate(source, paths);
                if (guestSource.equals(source) && !source.startsWith("/sys/")) {
                    Log.w(TAG, "sysfs bind source is outside the app data dirs: " + spec);
                    continue;
                }
                putManifest(byDestination, guestSource, destination);
            }
        }

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : byDestination.entrySet()) {
            lines.add(sysfsLine(entry.getValue(), entry.getKey()));
        }
        return lines;
    }

    static String sysfsLine(String source, String destination) {
        return source + "|" + destination;
    }

    private static void putManifest(LinkedHashMap<String, String> byDestination,
                                    String source, String destination) {
        if (source == null || destination == null) return;
        if (source.isEmpty() || destination.isEmpty()) return;
        byDestination.put(destination, source);
    }

    private static void addSessionSysfsDefaults(Context context, Paths paths,
                                                LinkedHashMap<String, String> byDestination) {
        File session = ContainerPaths.sessionDir(context);
        addSysfsTree(byDestination, paths, new File(session, "sys/power_supply"), "/sys/class/power_supply");
        addSysfsTree(byDestination, paths, new File(session, "sys/hwmon"), "/sys/class/hwmon");
        addSysfsTree(byDestination, paths, new File(session, "sys/kgsl-3d0"), "/sys/class/kgsl/kgsl-3d0");
        addSysfsTree(byDestination, paths, new File(session, "sys/input/class"), "/sys/class/input");
        addSysfsTree(byDestination, paths, new File(session, "sys/input/devices"), "/sys/devices/virtual/input");
    }

    private static void addSysfsTree(LinkedHashMap<String, String> byDestination, Paths paths,
                                     File source, String destination) {
        if (!source.isDirectory()) return;
        putManifest(byDestination, translate(source.getPath(), paths), destination);
    }

    /**
     * mangohud/mangoapp in the guest expect Linux thermal zone names; Android's zone numbering
     * differs, so seed fake {@code type} files like the proot runtime did.
     */
    private static void addThermalTypeFakes(File rootfs, Paths paths,
                                            LinkedHashMap<String, String> byDestination) {
        File zonesRoot = new File("/sys/class/thermal");
        File[] zones = zonesRoot.listFiles((dir, name) -> name.startsWith("thermal_zone"));
        if (zones == null) return;
        File etc = new File(rootfs, "etc/droiddeck");
        ensureDirQuiet(etc);

        Map<String, File> byType = new TreeMap<>();
        for (File zone : zones) {
            String type = firstLine(new File(zone, "type"));
            if (type != null) byType.putIfAbsent(type.toLowerCase(Locale.ROOT), zone);
        }

        LinkedHashMap<String, String> replaced = new LinkedHashMap<>();
        if (LinuxRuntime.gpuTempSource() != null) {
            replaced.put("thermal_zone28", "gpu-thermal");
            replaced.put("thermal_zone26", "gpu-thermal");
        }
        File ddrZone = byType.get("ddr");
        if (ddrZone == null) {
            for (Map.Entry<String, File> entry : byType.entrySet()) {
                if (entry.getKey().contains("ddr")) {
                    ddrZone = entry.getValue();
                    break;
                }
            }
        }
        if (ddrZone != null) replaced.put(ddrZone.getName(), "ddr-thermal");

        for (File zone : zones) {
            String other = replaced.get(zone.getName());
            if (other != null) {
                File typeFile = new File(etc, other + "-type");
                writeTextIfMissing(typeFile, other + "\n");
                putManifest(byDestination, translate(typeFile.getPath(), paths),
                        new File(zone, "type").getPath());
                continue;
            }
            String type = firstLine(new File(zone, "type"));
            if (type == null) continue;
            type = type.toLowerCase(Locale.ROOT);
            if (!type.contains("cpu") || type.contains("gpu")
                    || type.matches("cpu\\d-(top-)?thermal")) {
                continue;
            }
            File cpuType = new File(etc, "cpu-thermal-type");
            writeTextIfMissing(cpuType, "cpu0-thermal\n");
            putManifest(byDestination, translate(cpuType.getPath(), paths),
                    new File(zone, "type").getPath());
        }
    }

    private static boolean unreadablePciDevice() {
        File[] devices = new File("/sys/bus/pci/devices").listFiles();
        if (devices == null) return false;
        for (File device : devices) {
            if (!new File(device, "vendor").canRead()) return true;
        }
        return false;
    }

    private static String firstLine(File file) {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) return null;
            String line = lines.get(0).trim();
            return line.isEmpty() ? null : line;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeTextIfMissing(File file, String content) {
        if (file.isFile()) return;
        try {
            writeAtomically(file, content);
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + file + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- env text

    static List<String> envLines(Map<String, String> env, Paths paths) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            if (key == null || !ENV_KEY.matcher(key).matches()) continue;
            lines.add(key + "=" + SuShell.quote(translate(entry.getValue(), paths)));
        }
        return lines;
    }

    static String translate(String value, Paths paths) {
        if (value == null || value.isEmpty()) return value;
        List<String[]> mappings = new ArrayList<>();
        if (paths.runtimeDir != null) mappings.add(new String[]{paths.runtimeDir, ContainerPaths.GUEST_RUNTIME});
        if (paths.hudDir != null) mappings.add(new String[]{paths.hudDir, ContainerPaths.GUEST_HUD});
        if (paths.guestRuntimeDir != null) {
            mappings.add(new String[]{paths.guestRuntimeDir, ContainerPaths.GUEST_GUEST_RUNTIME});
        }
        if (paths.rootfsDir != null) mappings.add(new String[]{paths.rootfsDir, ""});
        if (paths.appDir != null) mappings.add(new String[]{paths.appDir, ContainerPaths.GUEST_APP});
        if (paths.externalStorage != null) {
            mappings.add(new String[]{paths.externalStorage, ContainerPaths.GUEST_STORAGE});
        }
        if (!ContainerPaths.HOST_EXTERNAL_STORAGE.equals(paths.externalStorage)) {
            mappings.add(new String[]{ContainerPaths.HOST_EXTERNAL_STORAGE, ContainerPaths.GUEST_STORAGE});
        }

        StringBuilder out = new StringBuilder(value.length() + 16);
        int i = 0;
        while (i < value.length()) {
            String bestFrom = null;
            String bestTo = null;
            for (String[] mapping : mappings) {
                String from = mapping[0];
                if (from.isEmpty() || !value.startsWith(from, i)) continue;
                if (bestFrom == null || from.length() > bestFrom.length()) {
                    bestFrom = from;
                    bestTo = mapping[1];
                }
            }
            if (bestFrom == null) {
                out.append(value.charAt(i));
                i++;
                continue;
            }
            int end = i + bestFrom.length();
            if (end < value.length() && !isPathBoundary(value.charAt(end))) {
                out.append(value.charAt(i));
                i++;
                continue;
            }
            if (i > 0 && !isValueBoundary(value.charAt(i - 1))) {
                out.append(value.charAt(i));
                i++;
                continue;
            }
            out.append(bestTo);
            i = end;
        }
        return out.toString();
    }

    private static boolean isValueBoundary(char c) {
        return c == '=' || c == ':' || c == ',' || c == ';' || c == ' ' || c == '\t'
                || c == '"' || c == '\'';
    }

    private static boolean isPathBoundary(char c) {
        return c == '/' || c == ':' || c == ',' || c == ';' || c == ' ' || c == '\t'
                || c == '"' || c == '\'';
    }

    static String icdManifest(String containerLibraryPath, String apiVersion) {
        String version = apiVersion == null || apiVersion.trim().isEmpty()
                ? DEFAULT_ICD_API : apiVersion.trim();
        return "{\"file_format_version\":\"1.0.0\",\"ICD\":{\"library_path\":\""
                + jsonEscape(containerLibraryPath) + "\",\"api_version\":\""
                + jsonEscape(version) + "\"}}\n";
    }

    static String jsonEscape(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append("\\u00")
                                .append(Character.forDigit((c >> 4) & 0xf, 16))
                                .append(Character.forDigit(c & 0xf, 16));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }

    static String joinGroups(int[] groups) {
        StringBuilder out = new StringBuilder();
        for (int group : groups) {
            if (out.length() > 0) out.append(',');
            out.append(group);
        }
        return out.toString();
    }

    public static int[] defaultGroups(int uid) {
        LinkedHashSet<Integer> groups = new LinkedHashSet<>();
        groups.add(uid);
        groups.add(786); // droidspaces --gpu group; /dev/dri and /dev/kgsl-3d0 are 0660 root:786
        for (int group : processGroups()) groups.add(group);
        int[] result = new int[groups.size()];
        int index = 0;
        for (int group : groups) result[index++] = group;
        return result;
    }

    /** android.system.Os has no getgroups() on older compile SDKs; /proc/self/status does. */
    private static int[] processGroups() {
        try {
            for (String line : Files.readAllLines(new File("/proc/self/status").toPath(),
                    StandardCharsets.UTF_8)) {
                if (!line.startsWith("Groups:")) continue;
                String[] parts = line.substring("Groups:".length()).trim().split("\\s+");
                int[] groups = new int[parts.length];
                int count = 0;
                for (String part : parts) {
                    if (part.isEmpty()) continue;
                    try {
                        groups[count++] = Integer.parseInt(part);
                    } catch (NumberFormatException ignored) {
                    }
                }
                return Arrays.copyOf(groups, count);
            }
        } catch (IOException ignored) {
        }
        return new int[0];
    }

    // ------------------------------------------------------------------- paths

    public static Paths paths(Context context) {
        File external = Environment.getExternalStorageDirectory();
        return new Paths(
                ContainerPaths.filesDir(context).getPath(),
                ContainerPaths.rootfs(context).getPath(),
                ContainerPaths.guestRuntimeDir(context).getPath(),
                ContainerPaths.waylandDir(context).getPath(),
                ContainerPaths.hudDir(context).getPath(),
                external == null ? ContainerPaths.HOST_EXTERNAL_STORAGE : external.getPath());
    }

    /** String-only view of the host path prefixes, so the translation is unit-testable. */
    public static final class Paths {
        public final String appDir;
        public final String rootfsDir;
        public final String guestRuntimeDir;
        public final String runtimeDir;
        public final String hudDir;
        public final String externalStorage;

        public Paths(String appDir, String rootfsDir, String guestRuntimeDir, String runtimeDir,
                     String hudDir, String externalStorage) {
            this.appDir = trimSlash(appDir);
            this.rootfsDir = trimSlash(rootfsDir);
            this.guestRuntimeDir = trimSlash(guestRuntimeDir);
            this.runtimeDir = trimSlash(runtimeDir);
            this.hudDir = trimSlash(hudDir);
            this.externalStorage = trimSlash(externalStorage);
        }

        private static String trimSlash(String value) {
            if (value == null || value.isEmpty()) return "";
            String result = value;
            while (result.length() > 1 && result.endsWith("/")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        }
    }

    // --------------------------------------------------------------------- ICD

    private static final class Icd {
        final String manifest;
        final String containerLibrary;

        Icd(String manifest, String containerLibrary) {
            this.manifest = manifest;
            this.containerLibrary = containerLibrary;
        }

        String manifest() {
            return manifest;
        }

        String containerLibrary() {
            return containerLibrary;
        }
    }

    /**
     * The imported Linux Vulkan driver pack selected through BL_VK_DRIVER gets a container-view
     * manifest: the pack's own icd.json carries a host absolute library_path that uid 10454
     * cannot reach from inside the container (container-runtime.md §2.6).
     */
    private static Icd selectIcd(Context context, Map<String, String> env, Paths paths) {
        File files = ContainerPaths.filesDir(context);
        File linuxRoot = new File(files, LINUX_DRIVERS);
        String selected = env.get("BL_VK_DRIVER");
        if (selected != null && !selected.isEmpty()) {
            Icd icd = packIcd(new File(linuxRoot, selected), ContainerPaths.GUEST_APP + "/"
                    + LINUX_DRIVERS + "/" + selected);
            if (icd != null) return icd;
        }
        if (hasRootfsIcd(context)) return null;
        for (File pack : sortedDirectories(linuxRoot)) {
            Icd icd = packIcd(pack, ContainerPaths.GUEST_APP + "/" + LINUX_DRIVERS + "/" + pack.getName());
            if (icd != null) return icd;
        }
        for (File pack : sortedDirectories(new File(files, GRAPHICS_DRIVERS))) {
            Icd icd = packIcd(pack, ContainerPaths.GUEST_APP + "/" + GRAPHICS_DRIVERS + "/" + pack.getName());
            if (icd != null) return icd;
        }
        return null;
    }

    private static boolean hasRootfsIcd(Context context) {
        File[] manifests = new File(ContainerPaths.rootfs(context), "usr/share/vulkan/icd.d")
                .listFiles((dir, name) -> name.endsWith(".json"));
        return manifests != null && manifests.length > 0;
    }

    private static Icd packIcd(File pack, String containerDir) {
        File manifestFile = new File(pack, "icd.json");
        if (!manifestFile.isFile()) return null;
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(manifestFile.toPath()),
                    StandardCharsets.UTF_8));
            JSONObject body = root.optJSONObject("ICD");
            String library = body != null ? body.optString("library_path", "")
                    : root.optString("library_path", "");
            String apiVersion = body != null ? body.optString("api_version", "")
                    : root.optString("api_version", "");
            if (library.isEmpty()) return null;
            String libraryName = new File(library).getName();
            if (libraryName.isEmpty() || !new File(pack, libraryName).isFile()) return null;
            String containerLibrary = containerDir + "/" + libraryName;
            return new Icd(icdManifest(containerLibrary, apiVersion), containerLibrary);
        } catch (Exception e) {
            Log.w(TAG, "unreadable ICD manifest " + manifestFile + ": " + e.getMessage());
            return null;
        }
    }

    private static List<File> sortedDirectories(File root) {
        File[] entries = root.listFiles(File::isDirectory);
        if (entries == null) return new ArrayList<>();
        Arrays.sort(entries, (left, right) -> left.getName().compareTo(right.getName()));
        return new ArrayList<>(Arrays.asList(entries));
    }

    private static void mirrorLoaderName(Map<String, String> env, String first, String second,
                                         Paths paths) {
        String a = env.get(first);
        String b = env.get(second);
        if (b == null && a != null) env.put(second, translate(a, paths));
        else if (a == null && b != null) env.put(first, translate(b, paths));
    }

    // ---------------------------------------------------------------- bind list

    private static void add(List<String> bindList, String source, String destination) {
        bindList.add(ContainerPaths.bind(source, destination));
    }

    private static String normalizeExtraBind(String raw, Paths paths) {
        if (raw == null) return null;
        String spec = raw.trim();
        if (spec.isEmpty()) return null;
        String source;
        String destination;
        boolean readOnly = false;
        int first = spec.indexOf(':');
        if (first <= 0) {
            String translated = translate(spec, paths);
            if (translated.equals(spec) || !translated.startsWith("/")) {
                Log.w(TAG, "extra bind ignored (not SRC:DST and not a translated app path): " + spec);
                return null;
            }
            source = spec;
            destination = translated;
        } else {
            source = spec.substring(0, first);
            String remainder = spec.substring(first + 1);
            int second = remainder.indexOf(':');
            if (second >= 0) {
                String flag = remainder.substring(second + 1);
                readOnly = "ro".equals(flag);
                destination = remainder.substring(0, second);
            } else {
                destination = remainder;
            }
        }
        if (!source.startsWith("/") || !destination.startsWith("/")) {
            Log.w(TAG, "extra bind ignored (relative path): " + spec);
            return null;
        }
        return readOnly ? ContainerPaths.bindRo(source, destination)
                : ContainerPaths.bind(source, destination);
    }

    static List<String> dedupeBinds(List<String> specs) {
        LinkedHashMap<String, String> byDestination = new LinkedHashMap<>();
        for (String spec : specs) {
            String destination = destinationOf(spec);
            if (destination == null) continue;
            byDestination.put(destination, spec);
        }
        return new ArrayList<>(byDestination.values());
    }

    static String sourceOf(String spec) {
        if (spec == null) return null;
        int first = spec.indexOf(':');
        if (first <= 0) return null;
        return spec.substring(0, first);
    }

    static String destinationOf(String spec) {
        if (spec == null || !spec.startsWith("/")) return null;
        int first = spec.indexOf(':');
        if (first <= 0) return null;
        String remainder = spec.substring(first + 1);
        int second = remainder.indexOf(':');
        String destination = second < 0 ? remainder : remainder.substring(0, second);
        return destination.startsWith("/") ? destination : null;
    }

    private static void prepareFakeDrm(Context context) {
        File base = new File(ContainerPaths.guestRuntimeDir(context), "drm");
        File dri = new File(base, "dri");
        File sys = new File(base, "sys");
        ensureDirQuiet(dri);
        ensureDirQuiet(sys);
        String standIn = LinuxRuntime.gpuStandIn(context);
        if (standIn == null) return;
        String numbers = LinuxRuntime.deviceNumbers(standIn);
        if (numbers == null || !numbers.contains(":")) return;
        String node = "renderD" + numbers.substring(numbers.indexOf(':') + 1);
        boolean kgsl = standIn.endsWith("kgsl-3d0");
        try {
            File nodeFile = new File(dri, node);
            if (!nodeFile.exists() && !nodeFile.createNewFile()) return;
            File device = new File(sys, numbers + "/device");
            File drmNode = new File(device, "drm/" + node);
            ensureDirQuiet(drmNode);
            writeTextIfChanged(new File(drmNode, "dev"), numbers + "\n");
            String driver = kgsl ? "kgsl-3d0" : "mali";
            writeTextIfChanged(new File(device, "uevent"),
                    "DRIVER=" + driver + "\nMODALIAS=platform:" + driver + "\n");
            symlinkIfMissing("/sys/bus/platform", new File(device, "subsystem"));
            if (kgsl) symlinkIfMissing("/sys/bus/platform/drivers/msm_drm", new File(device, "driver"));
        } catch (IOException | ErrnoException e) {
            Log.w(TAG, "fake DRM tree incomplete: " + e.getMessage());
        }
    }

    private static String gpuNodeBind(Context context) {
        String standIn = LinuxRuntime.gpuStandIn(context);
        if (standIn == null) return null;
        String numbers = LinuxRuntime.deviceNumbers(standIn);
        if (numbers == null || !numbers.contains(":")) return null;
        String minor = numbers.substring(numbers.indexOf(':') + 1);
        return ContainerPaths.bind(standIn, ContainerPaths.GUEST_DRI + "/renderD" + minor);
    }

    private static void symlinkIfMissing(String target, File link) throws ErrnoException, IOException {
        if (Files.isSymbolicLink(link.toPath())) return;
        if (link.exists()) return;
        File parent = link.getParentFile();
        if (parent != null) ensureDirQuiet(parent);
        Os.symlink(target, link.getPath());
    }

    // --------------------------------------------------------------------- I/O

    private static void ensureDir(File dir) throws IOException {
        if (dir.isDirectory()) {
            chmodQuiet(dir);
            return;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        chmodQuiet(dir);
    }

    private static void ensureDirQuiet(File dir) {
        if (dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) Log.w(TAG, "cannot create " + dir);
        else chmodQuiet(dir);
    }

    private static void chmodQuiet(File dir) {
        try {
            Os.chmod(dir.getPath(), DIR_MODE);
        } catch (ErrnoException ignored) {
            // Not fatal: the app uid owns the directory and can still traverse it.
        }
    }

    private static void writeTextIfChanged(File file, String content) throws IOException {
        if (file.isFile()) {
            try {
                String existing = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (existing.equals(content)) return;
            } catch (IOException ignored) {
                // Rewrite it.
            }
        }
        writeAtomically(file, content);
    }

    private static void writeAtomically(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) ensureDir(parent);
        File staged = new File(parent, "." + file.getName() + ".tmp");
        Files.write(staged.toPath(), content.getBytes(StandardCharsets.UTF_8));
        chmodQuiet(staged);
        try {
            Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String joinLines(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }

    private static List<String> sanitizeArgs(List<String> args) {
        List<String> lines = new ArrayList<>();
        if (args == null) return lines;
        for (String arg : args) {
            if (arg == null) continue;
            lines.add(arg.replace('\n', ' ').replace('\r', ' '));
        }
        return lines;
    }
}
