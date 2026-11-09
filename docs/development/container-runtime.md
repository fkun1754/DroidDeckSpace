# Container runtime: running the Linux runtime in DroidSpaces instead of proot

Status: implementation in progress (branch `feat/droidspaces-runtime`).
Owner: DroidDeck fork. This document is the contract for the parallel implementation;
keep it in sync when interfaces change.

## 1. Goal

Remove proot from the runtime path entirely. The guest Linux userland (Ubuntu 26.04
image in `files/linuxfs`) boots as a DroidSpaces container. Android-side services stay in
the app process and keep talking to the guest through the same kinds of channels as
today (Wayland socket, PulseAudio socket, shared files, per-session control commands).

Non-goals for this phase: desktop mode, VR/glasses, stores, bwrap sandboxes, multi-user.
They must keep compiling and fall back to the container exec path where trivially possible.

## 2. Verified facts from on-device experiments (2026-11-09)

These were measured on the target phone (Snapdragon 8 Gen 2, KernelSU, DroidSpaces 7.2.0,
DroidDeck app 0.7.0, runtime `ubuntu-r1-rc5`). Use them as hard constraints.

1. `droidspaces` requires root. The app is not root: every host-side call goes through
   `su -c ...` (KernelSU). A persistent root shell is optional; per-command is acceptable.
2. DroidSpaces always creates a **PID namespace** (`monitor.c:182`, `CLONE_NEWPID` is
   mandatory). `--privileged=shared` only means MS_SHARED mount propagation, not shared
   PID ns. => App cannot see/signal guest processes directly; a **guest-side control
   script** executed through `droidspaces run` replaces `/proc` pokes.
3. The container's private `/dev` (from `--gpu`) has the GPU nodes but **no `/dev/shm`**
   and no `input`/`uinput`/`card0`. `/dev/shm` must be bind-mounted. DroidDeck's fake
   DRM/input/sysfs trees are bind-mounted from the app data dir, exactly like proot did.
4. **Bind destinations that live under synthetic parent directories are unusable for a
   non-root guest uid.** DroidSpaces creates missing parent dirs for bind targets itself
   (`mkdir_p`, root-owned, mode 0700/000), so binding `files/...` to
   `/data/user/0/<pkg>/files/...` makes the mount unreachable for uid 10454. Therefore:
   **all app data is bind-mounted at short container-local paths** (`/dd`, `/gd`,
   `/run/droiddeck`, `/run/droiddeck-hud`). Env vars and manifests must use the
   container-view paths. This supersedes proot's "same path inside and outside" convention.
5. The guest must run as the **app uid** (`Process.myUid()`, observed 10454), not root:
   - the in-app Wayland compositor resolves client names via `/proc/<pid>/comm` and
     expects the same uid; only with uid parity did a container client show up as
     `gamescope (pid ...)` and did the compositor create the window
     (`[color] window 0 (gamescope): colour-management surface created`);
   - rootfs files are owned by the app uid, so guest writes stay app-writable;
   - PulseAudio cookie/socket permissions match.
   Root dropping is done by the container init with
   `setpriv --reuid=UID --regid=GID --groups=... --inh-caps=-all --bounding-set=-all`.
6. Vulkan requires a **container-view ICD manifest**: the manifest shipped by the
   driver pack contains a host absolute `library_path`
   (`/data/user/0/.../libvulkan_freedreno.so`), which is unreachable from the container
   namespace for uid 10454. The app must write its own manifest at session start whose
   `library_path` is `/dd/linux_vulkan_drivers/<pack>/libvulkan_freedreno.so`, and set
   `VK_ICD_FILENAMES`/`VK_DRIVER_FILES` to that file. Verified: Turnip Adreno 740
   (`Vulkan 1.4.367`) enumerates with this manifest under uid 10454.
7. `gamescope --backend wayland` inside the container reaches the app compositor, creates
   window 0, starts Xwayland and libei, and launches a client. (The child client in the
   probe was `vkcube` and died in the gamescope WSI layer; that is a probe artifact, not
   an architecture issue.)
8. The app-data bind set must include the whole `files` dir (`/dd`) so that every
   existing app-produced file (session, driver packs, venc, directaudio, ...) is visible
   at one mount; plus `no_backup/guest-runtime` (`/gd`) for shm/drm/sys fakes.
9. `droidspaces run -n NAME -- <argv>` is the exec mechanism; it returns the command's
   exit status and can be used for both app-uid and root commands (`run` defaults to
   root; the guest session itself is started by the container init, not by `run`).
10. Container state is visible as JSON through `droidspaces -n NAME info --json` and
    `show --json`; the per-container log is at
    `/data/local/Droidspaces/Logs/<name>/log` and is root-readable only.

## 3. Target architecture

```
┌──────────────────────────── App process (unchanged role) ────────────────────────────┐
│ SessionService ──► ContainerRuntime ──► SuShell ──► su -c "droidspaces ..."         │
│        │                │                 │                                          │
│        │                └─ ContainerEnv: session.env + container-view ICD manifests  │
│        ▼                                                                             │
│ Wayland compositor (existing C)   PulseAudio (existing)   HUD/UI (existing)           │
│        ▲                                ▲                                            │
└────────┼────────────────────────────────┼────────────────────────────────────────────┘
         │ bind: files/.wayland-rt → /run/droiddeck (wayland-0 socket)
         │ bind: files/pulseaudio  → /dd/pulseaudio (PS0 socket)
┌────────▼────────────────────────────────▼────────────────────────────────────────────┐
│ DroidSpaces container "droiddeck" (root init = droiddeck-container-init)             │
│  1. prepare /run/droiddeck-hud, machine-id, tmp perms                                │
│  2. source /dd/container/session.env  (BL_* env, DD_* params)                        │
│  3. setpriv → app uid, groups (786=droidspaces-gpu, 1004=input, ...), no caps        │
│  4. exec /usr/local/bin/droiddeck-session <mode>                                     │
│                                                                                      │
│  Myles: droiddeck-session → gamescope → Steam/Proton/FEX (all as app uid)            │
│  Control: droiddeck-container-ctl ps|signal|freeze|resume (via droidspaces run)      │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Guest path mapping (must be used everywhere instead of proot's same-path convention)

| host path (app data) | container path | notes |
|---|---|---|
| `filesDir` | `/dd` | one bind, covers session, drivers, pulse, venc, ... |
| `noBackupFilesDir/guest-runtime` | `/gd` | shm/drm/sys fakes, proot tmp |
| `filesDir/.wayland-rt` | `/run/droiddeck` | `XDG_RUNTIME_DIR`, wayland-0 socket |
| `filesDir/hud` (new dir) | `/run/droiddeck-hud` | `DROIDDECK_HUD_FRAMES=/run/droiddeck-hud/frames` |
| `noBackupFilesDir/guest-runtime/shm` | `/dev/shm` | mandatory |
| `noBackupFilesDir/guest-runtime/drm/dri` | `/dev/dri` | fake DRM (renderD0) |
| `noBackupFilesDir/guest-runtime/drm/sys` | `/sys/dev/char` | fake char sysfs |
| `/dev/kgsl-3d0` | `/dev/dri/renderD0` | **bind before** `/dev/dri` would hide it — order: `/dev/dri` then `/dev/dri/renderD0` |
| `filesDir/session/dev/input` | `/dev/input` | fake evdev |
| `filesDir/session/sys/...` | mirrors proot's sysfs binds | see `LinuxRuntime.binds()` |
| `/storage/emulated/0` | `/root/Storage` | external storage |
| `/system/fonts` | `/usr/local/share/fonts/android` | read-only |
| `filesDir/linuxfs` | (container rootfs, not a bind) | `droidspaces -r` |

Env (`/dd/container/session.env`, sourced by the init):
`DD_UID`, `DD_GID`, `DD_MODE`, `DD_GROUPS`, `DD_SESSION` plus every `BL_*`/`PULSE_SERVER`/
`XDG_*` variable that `SessionService` computes today, with host paths translated to the
container paths above. `PULSE_SERVER=unix:/dd/pulseaudio/PS0`,
`XDG_RUNTIME_DIR=/run/droiddeck`, `WAYLAND_DISPLAY=wayland-0`,
`VK_ICD_FILENAMES=/dd/container/vulkan-icd.json`.

### 3.2 Start / stop sequence

Start:
1. `ContainerRuntime.prepare()`: mkdir `filesDir/container`, `filesDir/hud`;
   write `session.env` and `vulkan-icd.json`; compute binds.
2. `SuShell.exec("droidspaces -n <name> -r <filesDir>/linuxfs --init=/usr/local/bin/droiddeck-container-init --net=host --gpu --bind=... start")`.
   (No `--pulse-audio`: the app-side PulseAudio socket is bind-mounted instead.
   No `-f`: the CLI returns once the init is running.)
3. `ContainerRuntime.awaitReady()`: poll `info --json` for a running state, then
   `run -- test -x /usr/local/bin/droiddeck-session` and
   `run -- cat /run/droiddeck/ready` (the init writes `ready` after env setup).
4. Session state/teardown flows through `ContainerSession` (status poll + log tail).

Stop:
1. `SuShell.exec("droidspaces -n <name> stop")` (SIGTERM to init, then SIGKILL; the
   session script's own teardown runs because init == session process after exec).
2. If the container is gone (`show` doesn't list it), treat as stopped.

### 3.3 Guest control protocol (`/usr/local/bin/droiddeck-container-ctl`)

Invoked as `droidspaces run -n <name> -- droiddeck-container-ctl <op> [args]`.
Line-oriented, stable, easily parsed; exit code 0 on success.

```
ps                       # one line per process: "<pid> <ppid> <uid> <stat> <comm> <args...>"
stat                     # "boot=<epoch> pids=<n> uptime=<s>"
signal <sig> <pid...>     # sig is TERM|KILL|STOP|CONT
freeze                   # STOP all processes except pid 1 and the ctl itself
resume                   # CONT all stopped processes
kill <pid...>            # alias for signal KILL
```

App side: `ContainerControl` wraps `SuShell.exec` + `droidspaces run` and parses the
output. Per-call latency is expected to be tens of ms; if the session needs lower latency
a persistent agent over a unix socket in `/run/droiddeck` can replace it later without
changing the App-side API.

### 3.3b Fake sysfs: overlay `/sys` inside the init (verified on device)

DroidDeck fabricates Steam-Deck hardware by bind-mounting fake `input/drm/kgsl/backlight/
thermal/power_supply/hwmon/hidraw/...` trees from the app data dir. A plain DroidSpaces
`--bind` cannot reproduce this: `/sys` is mounted read-only, so destinations that do not
exist in the kernel (`/sys/class/hwmon`, `/sys/devices/droiddeck`) fail with EROFS, and
bind targets that are symlinks (e.g. `/sys/class/kgsl/kgsl-3d0`) are refused by DroidSpaces.

Verified working approach (root, inside the container, before dropping privileges):

```sh
mount -t tmpfs none /tmp/droiddeck-sys
mkdir -p /tmp/droiddeck-sys/upper /tmp/droiddeck-sys/work
mount -t overlay overlay \
    -o lowerdir=/sys,upperdir=/tmp/droiddeck-sys/upper,workdir=/tmp/droiddeck-sys/work /sys
mkdir -p /sys/class/hwmon /sys/devices/droiddeck     # now creatable
```

After this, real sysfs entries are still visible through the overlay and new fake
directories/files can be created in the tmpfs upper layer. The init then applies the fake
trees itself (`mount --bind <fake> <dest>` from a manifest written by the app) and only
then drops privileges. Consequences:

* `ContainerEnv.binds()` must **not** pass `/sys/**` destinations to `droidspaces --bind`;
  it writes a `sysfs-binds` manifest (one `SRC|DEST` per line) that the init applies after
  the overlay. `/dev/**` binds stay in the CLI bind list.
* The init needs CAP_SYS_ADMIN + CAP_SYS_ADMIN in the container (available; no
  `--privileged` flag required) and must run these mounts as root before `setpriv`.
* Verified: `/dev/input` bind (fake evdev, app-uid owned) is readable by the app uid;
  `droidspaces stop` sweeps the whole descendant tree (no orphan `sleep` after stop), so
  session teardown is reliable.

### 3.4 Session hand-off files (written by `ContainerEnv.prepareSession`, under `filesDir/container/`)

* `session.env` — `KEY='shell-escaped-value'` lines sourced by the container init. Carries
  `DD_UID`, `DD_GID`, `DD_GROUPS`, `DD_MODE`, `DD_SESSION`, `DD_SESSION_BIN` (test override,
  default `/usr/local/bin/droiddeck-session`) and every translated `BL_*`/`XDG_*`/
  `PULSE_SERVER`/`VK_*` variable.
* `session.args` — one raw positional argument per line; the init reads it into `"$@"`, then
  `exec "$DD_SESSION_BIN" "$DD_MODE" "$@"`.
* `vulkan-icd.json` — container-view ICD manifest (guest path `/dd/container/vulkan-icd.json`).
* The init writes `/run/droiddeck/ready` right before dropping privileges; the App polls it.

### 3.5 DroidSpaces CLI facts used by the App side

See `notes/impl-facts-cli.md` (measured on device). Highlights: state comes from
`droidspaces show --json` (`running[]`) or the literal `NONE` printed by `pid`; `info --json`
exits 0 even for a stopped container; `droidspaces -n NAME --stdin run -- CMD` forwards stdin
(`--stdin` must precede `run`); `run` propagates exit codes and executes as root, so app-uid
commands are wrapped in `setpriv`.

## 4. Code layout

New files (own these, do not edit outside your scope):

```
app/src/main/java/com/droiddeck/launcher/runtime/container/
    ContainerPaths.java     constants: guest paths, container name, CLI path
    SuShell.java            root shell (isRootAvailable, exec with timeout/quoting)
    DroidspacesCli.java     start/stop/info/run/exec + JSON parsing
    ContainerEnv.java       session.env + container-view ICD manifests + bind list
    ContainerRuntime.java   prepare/start/awaitReady/stop/status/exec orchestration
    ContainerSession.java   live session handle (alive, stop, logs)
    ContainerControl.java   ps/signal/freeze/resume via ctl script
```

Guest scripts (shipped into the runtime image by `SessionFiles`):

```
tools/linuxfs/overlay/usr/local/bin/droiddeck-container-init
tools/linuxfs/overlay/usr/local/bin/droiddeck-container-ctl
```

Existing files to modify: see `## 6. Work split`.

## 5. App-side API contract

```java
// ContainerPaths
public static final String CONTAINER_NAME = "droiddeck";   // later: suffix per variant
public static final String GUEST_APP = "/dd";
public static final String GUEST_GUEST_RUNTIME = "/gd";
public static final String GUEST_RUNTIME = "/run/droiddeck";
public static final String GUEST_HUD = "/run/droiddeck-hud";
public static final String GUEST_SESSION_ENV = "/dd/container/session.env";
public static final String GUEST_ICD = "/dd/container/vulkan-icd.json";
public static final String GUEST_INIT = "/usr/local/bin/droiddeck-container-init";
public static final String GUEST_CTL = "/usr/local/bin/droiddeck-container-ctl";
public static final String CLI = "/data/local/Droidspaces/bin/droidspaces";

// SuShell
static boolean isRootAvailable(Context);
static boolean isRootMemoized();          // for UI/logging
static ExecResult exec(String command, int timeoutMs);     // su -c <command>
static ExecResult execForOutput(String command, int timeoutMs);

// DroidspacesCli
static boolean installed();
static ExecResult start(ContainerSpec spec);
static ExecResult stop(String name);
static String state(String name);          // "RUNNING", "STOPPED", null
static ExecResult run(Context ctx, String name, List<String> argv, int timeoutMs);
static ExecResult runAsUser(Context ctx, String name, int uid, List<String> argv, int timeoutMs);

// ContainerEnv
static File prepareSession(Context ctx, SessionSpec session);  // writes env + ICD, returns env file
static List<String> binds(Context ctx, SessionSpec session);
static File hudDir(Context ctx);           // host path of filesDir/hud

// ContainerRuntime
static boolean isAvailable(Context);       // root + CLI installed, memoized
static ContainerSession start(Context ctx, SessionSpec session); // prepare + start + awaitReady
static void stop(Context ctx);
static String state(Context ctx);

// ContainerControl
static List<GuestProcess> ps(Context ctx);
static void signal(Context ctx, int sig, int... pids);
static void freeze(Context ctx); static void resume(Context ctx);
```

`SessionSpec` is a small value object carrying: mode (`steam`/`desktop`/`run`), session id,
`Map<String,String> env` (already computed by SessionService, host paths allowed; the env
writer translates), extra binds, uid/gid.

## 6. Work split (disjoint file scopes)

* **container-core**: `runtime/container/**` (new). Implements the contract above.
  Also `app/src/test/java/com/droiddeck/launcher/runtime/container/**` (pure-JVM tests for
  quoting/env translation/ctl parsing where feasible).
* **guest-runtime**: `tools/linuxfs/overlay/usr/local/bin/droiddeck-container-*`,
  `app/src/main/java/com/droiddeck/launcher/session/SessionFiles.kt` (install the two
  scripts into the rootfs; keep the proot copy paths but drop the proot pack copy),
  `app/src/main/java/com/droiddeck/launcher/core/AddonPack.kt` (remove PROOT),
  `app/build.gradle` (remove proot add-on pack from `addonPacks` and packaging excludes),
  `tools/packs/proot/**` references (leave files in place for a later cleanup commit),
  `docs/**` updates if needed.
* **session-wiring**: `session/SessionService.kt`, `runtime/GuestCommand.kt`,
  `runtime/RootSpawner.kt`, `runtime/BwrapSpawner.kt`, `runtime/LinuxRuntime.java`,
  `runtime/ProotFastPath.kt`, `runtime/PhantomProcessLimit.kt`, `hud/*` path use.
  Replace proot process creation with `ContainerRuntime`/`ContainerControl`; delete
  `ProotFastPath`; keep public helpers that other code uses (`rootDir`, `liveDir`,
  `sessionRoot`, `vulkanIcd`, ...) but drop proot command construction. Comment out /
  `// TODO(container): ...` any flow that cannot be converted in this pass, but keep it
  compiling.
* **build-env**: toolchain only; no source edits. See `## 7`.

## 7. Build & verification

Local build host: the kubuntu container on the phone (aarch64). Toolchain:
OpenJDK 21, Android SDK cmdline-tools under `/opt/android-sdk`, `local.properties`
with `sdk.dir=/opt/android-sdk`. Gradle wrapper 8.10.2 downloads on first run.

Known aarch64 host limitation: AGP 8.8 resolves `aapt2` only for `linux-x86_64`
(`aapt2-8.8.0-12006047-linux.jar`), and NDK 27.3 ships x86_64 host toolchains. Full APK
assembly therefore needs a workaround (run the x86_64 tools under box64/qemu, build a
native aarch64 aapt2, or verify via `compileDebugKotlin` only). Minimum bar for this
branch: **`:app:compileDebugKotlin` succeeds** and the new code is unit-tested where it
is pure JVM. Record the exact commands in this file once the toolchain is settled.

On-device validation (after an APK is built by CI): install, grant root, start a steam
session, verify `logcat` shows the compositor window for the container gamescope and that
Steam reaches the big-picture UI.

### 实测命令（aarch64 kubuntu 容器, 2026-11-09）

容器内已装好 Temurin JDK 21（`/opt/jdk21/current`）与 Android SDK（`/opt/android-sdk`：
cmdline-tools、platform-tools、android-34、build-tools 34/35、NDK 27.3、cmake 3.22.1），
`local.properties` 写有 `sdk.dir=/opt/android-sdk` 和 `droiddeck.skipRust=true`。AGP 8.8 的
x86_64 aapt2 经源码编译的 box64 执行，通过 `~/.gradle/gradle.properties` 的
`android.aapt2FromMavenOverride` 接入；`~/.gradle/init.d/` 全局禁用依赖 x86_64 NDK/cargo
工具链的 `buildRustStores`/`buildVkBridgeServer`。所有环境细节与重建脚本在容器内
`notes/build-env.md`。

```sh
cd src/droiddeck
JAVA_HOME=/opt/jdk21/current ./gradlew :app:compileDebugKotlin --no-daemon -PskipRust=true \
  -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx2560m -XX:MaxMetaspaceSize=768m"
```

实测完整 `:app:compileDebugKotlin` 成功耗时约 6 分钟（资源任务已缓存时 Kotlin 编译约 4-5 分钟；
首跑需联网下载 Gradle 与依赖，之后缓存在 `~/.gradle`）。
**同一时间只跑一个 Gradle 构建**：容器只有 14 GB 内存而项目 `gradle.properties` 钉死 4 GB
Gradle 堆，并发构建曾 OOM 掉 Kotlin 编译。纯 JVM 单测用同样参数加
`:app:testDebugUnitTest --tests "com.droiddeck.launcher.runtime.container.*"`。

## 8. Open items / later phases

* Persistent guest agent over a unix socket (replaces per-call `droidspaces run`).
* Desktop mode needs dbus/systemd in the container; decide between "init starts dbus"
  and a systemd-based container (needs a rootfs with `/sbin/init`, which the current
  runtime image does not ship).
* bwrap/store sandboxes inside the container (needs `--allow-sandboxing`).
* Multiple DroidDeck variants sharing one DroidSpaces workspace: container name must be
  derived from the application id to avoid collisions.
* Rootfs updates must run as root (the container touches root-owned files such as
  `/etc/group` and the `/etc/resolv.conf` symlink).
* Remove `tools/proot/**`, `AddonPack.PROOT`, `PROOT_FP_*` env handling and the proot
  pack pin once the container path is the only path.
