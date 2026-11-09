# Optional customisation bundle (desktop apps, OneDrive, VNC, workspace)

The container is a normal Ubuntu rootfs, so applications can be installed into it. The reference
setup on the development device adds:

| Item | Where | Notes |
|---|---|---|
| Node.js + DeepSeek Harness (`dsh`) | `/opt/node-v24.9.0-linux-arm64`, `/usr/local/bin/{node,npm,npx,dsh}` | config in `~/.dsh` |
| Obsidian | `/opt/obsidian` + `/usr/local/bin/obsidian` | config in `~/.config/obsidian` |
| Spark Store | `spark-store` package + `/usr/local/bin/spark-store` | |
| OneDrive (rclone) | `rclone mount` to `/root/OneDrive` | config `~/.config/rclone/rclone.conf`; mounted automatically by the container hook |
| VNC | `krfb` (KDE Wayland through the desktop portal) on 5900 | VNC password from the old `.vnc/passwd` |
| Workspace | `/root/Agent` (+ `~/Agent` link) | |

A self-contained migration bundle (tarballs + `install.sh`) can be produced from a configured
device and restored into a freshly installed container:

```sh
adb push DroidDeckSpace-migrate /storage/emulated/0/
adb shell "droidspaces -n <container> run -- sh /root/Storage/DroidDeckSpace-migrate/install.sh"
```

`install.sh --check` verifies the result. The bundle is intentionally not part of the release APK;
it only contains optional extras and personal configuration.
