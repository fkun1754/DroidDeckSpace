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

## Runtime image utilities (l2s links)

The official runtime ships most coreutils as `.l2s.*` symlink chains pointing at one patched
uutils multi-call binary. That binary checks the requested utility name against
`/proc/self/exe` and refuses to run when the executable is the shared payload; under proot the
translation layer hides this, but a plain container sees the payload name and every `ls`, `mkdir`,
`cat`, … fails ("Security violation: Requested utility … does not match executable name").

`LinuxRuntimeInstaller.ensureL2sHardlinks` therefore converts the chains under `/usr/bin`,
`/usr/sbin`, `/bin`, `/sbin`, `/usr/local/bin` and `/usr/lib/cargo/bin` into **hard links** to the
payload, so the executable name matches `argv[0]`. It runs after a runtime install and once per
rootfs at session preparation (marker `.droiddeck-l2s-hardlinks`), which also repairs runtimes
installed before the fix.

## Runtime removal

Removing the Linux runtime in the app also unregisters its DroidSpaces container. DroidSpaces has
no CLI delete command, so `ContainerRuntime.removeContainers` performs the host-side equivalent of
its "Uninstall Container" action: stop the container (current and legacy names), then delete
`/data/local/Droidspaces/{Containers,Logs,Pids}` entries for those names and for any
`droiddeck.com.droiddeck.*` leftovers from earlier package ids. Without this the container list
keeps a dead entry pointing at a rootfs that no longer exists.
