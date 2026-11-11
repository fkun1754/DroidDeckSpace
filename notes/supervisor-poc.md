# Supervisor desktop PoC (2026-11-11)

Goal: one persistent KWin desktop in a DroidSpaces container, with two render paths:
VNC (krfb) and the DroidDeck foreground compositor; switching only starts/stops the
render consumer, never restarts KWin.

## Verified

- KWin in the runtime accepts `--virtual` (virtual output backend):
  `kwin_wayland --virtual --socket wayland-vtest --no-lockscreen`.
- GPU rendering works through Zink on Turnip/Adreno 740:

  ```sh
  EGL_PLATFORM=surfaceless \
  GALLIUM_DRIVER=zink MESA_LOADER_DRIVER_OVERRIDE=zink \
  VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.json \
  eglinfo -B
  # OpenGL core profile renderer: zink Vulkan 1.4(Turnip Adreno (TM) 740 (MESA_TURNIP))
  ```

  (plain Mesa/freedreno on `/dev/dri/renderD0` fails because the guest render node is KGSL;
  Zink + the runtime's Turnip ICD is the working GPU path.)
- KWin `--virtual` opens `/dev/dri/renderD0` and emits harmless
  `KMS: DRM_IOCTL_MODE_CREATE_DUMB failed` warnings; EGL initializes and compositing starts.

## Environment for a GPU virtual desktop

```sh
XDG_RUNTIME_DIR=/run/droiddeck-desktop   # mode 700
XDG_SESSION_TYPE=wayland
XDG_CURRENT_DESKTOP=KDE
GALLIUM_DRIVER=zink
MESA_LOADER_DRIVER_OVERRIDE=zink
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.json
kwin_wayland --virtual --socket wayland-desktop --no-lockscreen
```

## Next

1. Start a full KDE session on that KWin (dbus/plasmashell/portal/pipewire) from the supervisor.
2. VNC-only path: krfb/portal screencast of the virtual output; verify remote access without the app.
3. Foreground path: DroidDeck native pipewire consumer + uinput input forwarding; stop krfb while
   foreground to save CPU.
4. Wire the supervisor into the app: keep-running starts only the supervisor; requesting the desktop
   starts KWin/KDE; switching VNC <-> foreground toggles the consumer.

## Headless Plasma + VNC prototype (same day)

- A full Plasma session starts on the virtual KWin: `startplasma-wayland` with a
  `/usr/local/bin/kwin_wayland` shim that injects `--virtual` and the Zink env.
  kwin_wayland, kded6, kactivitymanagerd and plasmashell stay alive.
- `krfb` + `xdg-desktop-portal-kde` install from apt; krfb starts and listens on TCP 5900
  (hex `170C` in /proc/net/tcp).
- Blocker: krfb's capture fails: `krfb.framebuffer.pipewire: Failed to start screencast: 2`.
  The KDE portal backend advertises ScreenCast, but the portal's ScreenCast approval cannot be
  granted in a headless session (there is no dialog UI / permission store entry). Until that is
  pre-authorized, krfb serves a black screen.
- Next options:
  1. Pre-seed the xdg-desktop-portal permission store for krfb's app id.
  2. Bypass the portal with a direct KWin screencast client (`zkde_screencast_unstable_v1`).
  3. Keep krfb only as a fallback and implement the foreground pipewire path first.

## Portal ScreenCast authorization attempt

- The permission store service is `/usr/libexec/xdg-permission-store`; its object path is
  `/org/freedesktop/impl/portal/PermissionStore` (not `/org/freedesktop/portal/desktop`).
  Interface: `SetPermission(s table, b create, s id, s app, as permissions)`.
- Pre-seeding `screencast` entries for `org.kde.krfb` / `krfb` / empty did not help.
- krfb still logs `Failed to start screencast: 2`; `xdg-desktop-portal-kde` crashes while
  handling the request (KCrash), so the portal returns a cancelled/failed response.
- Because the KDE portal backend itself crashes under the headless root session, the practical
  next step is a direct KWin screencast client (`zkde_screencast_unstable_v1`) instead of the
  portal; that also removes the approval dialog dependency for the foreground render path.
