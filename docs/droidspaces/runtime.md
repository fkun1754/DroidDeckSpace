# DroidSpaces runtime contract

DroidDeckSpace runs the Linux guest as a [DroidSpaces](https://github.com/ravindu644/Droidspaces-OSS)
container on the phone's own kernel. This page is the short version of the design contract; the
detailed implementation notes live in the development branch history.

## Verified DroidSpaces facts

- `droidspaces -n NAME -r ROOTFS --init=INIT --net=host --gpu --bind=SRC:DST[:ro]` boots a
  container with a mandatory PID namespace, a private `/dev` (no `/dev/shm`, so the app binds one),
  host networking and mirrored GPU nodes.
- `--stdin` must be placed before `run`: `droidspaces -n NAME --stdin run -- CMD`.
- `show --json` is authoritative for state; `info` exits 0 when the container is stopped and
  `pid` prints `NONE`.
- Guest processes run as the app uid through `setpriv --reuid/--regid/--groups/--inh-caps=-all
  --bounding-set=-all`; the app-side Wayland socket and PulseAudio socket are owned by that uid, so
  this is required for the compositor hand-off.
- `su -c` is the only reliable root command channel. KernelSU redirects `su` through `ksud`; an
  interactive root shell cannot be used (stdin is not available), and every one-shot `su -c` costs
  seconds, so short commands share a single root bridge script over a FIFO pair.
- DroidSpaces auto-loads `dirname(ROOTFS)/container.config` before applying CLI flags; the app
  removes that file and the stale container config directory, then starts with `--reset` so the
  CLI arguments are the whole truth.

## Path mapping

| App side (host) | Container view |
|---|---|
| `filesDir` | `/dd` |
| `noBackupFilesDir/guest-runtime` | `/gd` |
| `filesDir/.wayland-rt` | `/run/droiddeck` |
| `filesDir/hud` | `/run/droiddeck-hud` |
| `noBackupFilesDir/guest-runtime/shm` | `/dev/shm` |
| fake `drm` tree | `/dev/dri` |
| `/storage/emulated/0` | `/root/Storage` |
| `filesDir/linuxfs` | `/` (rootfs) |

## Session lifecycle

1. The app probes root once (first probe allows 20 s for the KernelSU prompt), starts the root
   bridge and confirms the DroidSpaces CLI.
2. `ContainerEnv` writes `session.env` (container-view paths), `session.args`, the container-view
   Vulkan ICD manifest and the fake-sysfs bind manifest; `SessionFiles` stages the guest scripts.
3. `ContainerRuntime.start` retires a stale container/config, starts the container with the CLI
   flags, and `awaitReady` waits for the ready marker file the init writes before exec'ing the
   session (no `su` polling).
4. The guest init prepares `/dev`, overlays `/sys` with a tmpfs to create fake hardware entries,
   applies the sysfs binds, hands `/run/dbus` to the session uid, drops to the app uid and execs
   `droiddeck-session`.
5. On stop the app sends the container stop, then falls back to `ContainerControl` signals for guest
   pids; the monitor log provides the exit code.

## Guest user names

The session always uses the app uid. The container init rewrites `/etc/passwd`/`/etc/group` so that
uid has a mode-appropriate name: big-picture keeps the historical `root` alias (`HOME=/root`),
desktop modes use the ordinary `droiddeck` alias (`HOME=/home/droiddeck`). Both map to one uid, so
installed applications and files are shared between modes. The rewrite restores app-uid ownership
and 0644 mode because the app still merges its guest accounts into `/etc/passwd`.

## Startup cost model

Every `su -c` costs seconds under KernelSU, so the app counts container commands per session:
the root bridge makes short commands milliseconds, `awaitReady` watches a marker file, and the app
warms the probe/bridge when it starts. Measured on the target phone: steam ~15.6 s, desktop
~10.4 s from request to first frame.
