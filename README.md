<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="artwork/droiddeck-banner-dark.svg">
    <img alt="DroidDeckSpace" src="artwork/droiddeck-banner-light.svg" width="100%">
  </picture>
</p>

# DroidDeckSpace

**DroidDeck, with the Linux runtime running in a DroidSpaces container instead of proot.**

DroidDeckSpace is a fork of [DroidDeck](https://github.com/Droid-Deck/DroidDeck). The UI, the
SteamOS-style launcher, the in-app Wayland compositor and the Linux runtime image are unchanged —
what changed is *where* the Linux side runs: instead of the bundled proot engine, every session
boots as a real [DroidSpaces](https://github.com/ravindu644/Droidspaces-OSS) container on the
phone's own kernel, supervised by the app through root.

<p align="center">
  <a href="https://github.com/fkun1754/DroidDeckSpace/releases/latest"><img src="https://img.shields.io/badge/Download-DroidDeckSpace%200.7.0%20APK-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Download DroidDeckSpace 0.7.0"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge" alt="License GPLv3"></a>
  <a href="https://github.com/Droid-Deck/DroidDeck"><img src="https://img.shields.io/badge/Based%20on-DroidDeck-5865F2?style=for-the-badge" alt="Based on DroidDeck"></a>
</p>

> **Root required.** Unlike upstream DroidDeck, DroidDeckSpace needs root (KernelSU or Magisk) and
> the separate DroidSpaces app installed. Both are explained in [Install](#install) below.
>
> The first release APK is signed with the repository test key, not the upstream release key.
> That is fine for sideloading, but it means it cannot be installed over an official DroidDeck
> installation — use a different package id (`com.droiddeckspace.launcher`) or uninstall first.

## Features

- The complete DroidDeck experience: Steam Gaming Mode, Linux desktop, GOG/Epic/Xbox/... stores,
  Steam VR, smart glasses, controllers, overlays and the in-app Wayland compositor.
- **Real container runtime**: sessions run as a DroidSpaces container (own namespaces, its own
  `/dev`, host network, GPU nodes and bind mounts) instead of a proot guest.
- **Same official Linux runtime**: the app still downloads the official Ubuntu runtime from
  [DroidDeck-Components](https://github.com/Droid-Deck/DroidDeck-Components) and installs it into the
  container automatically — no separate rootfs setup step.
- **Faster startup**: one root bridge plus a ready-marker fast path bring a session up in about
  **10–16 s** (previously 65–80 s because every `su` call costs seconds under KernelSU).
- **Normal desktop user**: big-picture keeps the historical `root` alias, desktop sessions run as
  the ordinary user `droiddeck` with `HOME=/home/droiddeck`.
- **Stable-only in-app updates** from this repository (preview/test channels are removed).

## Install

### 1. Requirements

- Android 9 or newer, **rooted** (KernelSU or Magisk).
- [DroidSpaces](https://github.com/ravindu644/Droidspaces-OSS/releases/latest) installed and its
  backend set up (install the APK, grant it root, open it once; reboot if it asks).
- Same GPU constraints as upstream DroidDeck (Adreno 730+ recommended; Adreno 6xx experimental;
  Mali/Xclipse/PowerVR unsupported).
- About 3 GB free for the Linux runtime plus space for games.

### 2. Install the app

Download the APK from [Releases](https://github.com/fkun1754/DroidDeckSpace/releases/latest) and
install it. Package id: `com.droiddeckspace.launcher`.

### 3. Grant root

In KernelSU/Magisk, allow **DroidDeckSpace** to use root. (The app's first run also triggers a root
probe, so the request may appear automatically.)

### 4. Set up the runtime

Open DroidDeckSpace → **Setup → Overview**. The new **DroidSpaces runtime** section shows:

1. **Root access** — green once KernelSU/Magisk allows the app; “Grant root” opens the manager.
2. **DroidSpaces** — green when the backend is reachable; “Install DroidSpaces” opens the release
   page (or the app if installed).
3. **Linux runtime** — press **Install**. It downloads the official runtime from
   `Droid-Deck/DroidDeck-Components`, extracts it into the DroidSpaces container rootfs and is ready
   for sessions — this is the same official image upstream uses, installed automatically.

Then press **Play** (Steam Gaming Mode) or **Desktop**.

### First session

- Big-picture sessions run with `HOME=/root` (Steam/Proton paths unchanged).
- Desktop sessions run as the normal user `droiddeck` with `HOME=/home/droiddeck` (the app uid is
  shared between modes, so files and sockets stay accessible).

## What changed from DroidDeck

DroidDeckSpace keeps the app-side UI and the runtime image, and replaces the guest execution
layer. The main changes are:

### Runtime layer

| Area | Change |
|---|---|
| proot | Removed (`ProotFastPath`, `proot` binaries/packs, proot build & CI steps) |
| Container runtime | New `runtime/container` package: `ContainerPaths`, `SuShell` (+ root bridge), `DroidspacesCli`, `ContainerEnv`, `ContainerRuntime`, `ContainerSession`, `ContainerControl`, `SessionSpec`, `ExecResult` with unit tests |
| Guest boot | New `droiddeck-container-init`: prepares `/dev`, fake sysfs overlay, bind manifests, named users, then `setpriv` drops to the app uid and runs the session |
| Guest control | New `droiddeck-container-ctl`: ps/stat/signal/freeze/resume for guest processes |
| Session wiring | `SessionService`, `GuestCommand`, `RootSpawner`, `BwrapSpawner` and the session scripts now talk to the container instead of proot |
| Display | Unchanged: the app's in-app Wayland compositor still presents gamescope directly (no Termux:X11) |
| Root access | One-shot `su -c` commands through a persistent root bridge; the interactive `su` shell is not used (KernelSU redirects `su` to `ksud`) |

### Behaviour

- Session startup: root probe and bridge warm-up happen at app start; the app waits for the
  container’s ready marker instead of polling with `su`, and stale containers/configs are cleaned
  up before each start.
- Users: big-picture keeps the historical `root` alias; desktop sessions use the normal user
  `droiddeck` (uid = app uid, so files and sockets stay shared). The desktop home is
  `/home/droiddeck`; `/root` keeps relative compatibility links.
- First-run guide: Setup → Overview explains the root/DroidSpaces/runtime prerequisites and gates
  the Play button until they are ready.
- Updates: the in-app updater points at this repository’s stable channel only. Runtime updates
  remain the official DroidDeck runtime feed and are installed into the container automatically;
  a runtime update keeps `/root`, `/home` and the DroidDeckSpace custom apps, and the migration
  script re-applies any missing apt packages.

See [docs/droidspaces/runtime.md](docs/droidspaces/runtime.md) for the design contract and the
verified DroidSpaces CLI facts.

## Build

Same toolchain as upstream DroidDeck (Docker, Java 17, Android SDK/NDK, Rust, Python 3, zstd):

```bash
tools/build_local.sh
```

The APK lands in `app/build/outputs/apk/release/`. Unit tests:

```bash
./gradlew -PskipRust=true :app:testDebugUnitTest
```

On a host that cannot run the x86_64 native toolchain (for example an aarch64 Linux container),
`./gradlew :app:assembleDebug -PskipRust=true` produces a debug APK; the `tools/build_local.sh`
flow and CI build the complete native libraries.

## Updates

- **App**: stable channel only, from this repository’s
  [catalog](https://raw.githubusercontent.com/fkun1754/DroidDeckSpace/main/catalog.json).
- **Linux runtime**: official DroidDeck runtime feed, installed automatically into the DroidSpaces
  container. Runtime updates keep `/root` and `/home`; packages installed into the system image
  (for example through apt) are replaced by the new image and need to be reinstalled after a major
  runtime update.

## Credits and license

DroidDeckSpace is based on [DroidDeck](https://github.com/Droid-Deck/DroidDeck) (GPL-3.0) and uses
[DroidSpaces](https://github.com/ravindu644/Droidspaces-OSS) as the container runtime. All original
DroidDeck copyrights and licenses apply; changes in this fork are released under the same GPL-3.0
license. See [LICENSE](LICENSE).
