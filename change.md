# Twoyi — Rootless Kitsune-Magisk Integration (v0.5.4-r101)

Engineering log for running Kitsune Mask/Magisk 26.4 **inside the Twoyi guest** on a
non-rooted host. Everything below applies only to the guest ROM bundled in the APK
(`assets/rootfs.7z`) — the host kernel, boot image, /system, /sbin and PID 1 are never touched.
Root inside the guest is a uid-0 emulation implemented by an `LD_PRELOAD` syscall shim.

## Architecture

- Host app `io.twoyi` runs `rootfs/init` via `libloader.so` / Rust launcher (`app/rs/src/lib.rs`, env `TYLOADER`).
- The guest has **no chroot**: guest PID 1 sees the host root; path translation happens in guest libc through the shim (`system/lib64/libtwoyi_magisk_rootless.so`).
- Guest ADB listens on TCP **22122** (`adb connect 127.0.0.1:22122` after `adb forward tcp:22122 tcp:22122`).
- Kitsune Mask package: `io.github.huskydg.magisk`; the ROM ships a legacy `com.thirdparty.superuser` stub that `su`/`magisk` replace.
- The shim maps `/system`, `/data/adb` etc. to the sandbox (`/data/data/io.twoyi/rootfs/…`), interposes execve/stat/fork bookkeeping for the Magisk daemon, maps the app uid for peer credentials, and keeps the mapping idempotent (paths already under the sandbox pass through unchanged).

## Guest ROM contents relevant to Magisk (current bake r101)

- **Shim v12** (`libtwoyi_magisk_rootless.so`, md5 `30cf1e71a2fd81fe226ad34799f6067e`): dynamic app-uid discovery (never hardcode — reinstall changes the uid), argv0-basename cmdline matching, orphan-only daemon cleanup at ctor, busybox-sh redirect, FIFO remap.
- **Pristine magisk64** (`/system/bin/su`, `/system/xbin/magisk`) — NOT the DB-relative patched build.
- `init.zygote64.rc` / `init.zygote32.rc`: `setenv ZYGISK_ENABLED 1`, guest-PATH `setenv`.
- `magisk.rc` with `start logd` commented; `logd.rc` disabled (guest logd crash-loops on /dev/kmsg; logcat rides the HOST logd).
- `data/adb/magisk/util_functions.sh` — three installer patches: physical TMPDIR (a private subdir, NEVER /data/adb — `rm -rf $TMPDIR` would erase the DB dir), `ensure_bb` without re-exec, physical `set_nvbase` (line 774 resets NVBASE to a guest-view path).
- `system/bin/twoyi-kitsune-install` boot script: self-heal the Manager via `pm path io.github.huskydg.magisk` (no marker files), install KitsuneMask **only after `sys.boot_completed=1`** (installing mid-boot trips the SystemServer watchdog → packages.xml rollback), overlay emulation (copy sandbox /system + state tracking), `pm disable com.google.android.gms/org.microg.gms.ui.SettingsActivity` to hide the microG icon while keeping the Settings entry.
- `system/etc/twoyi/KitsuneMask.apk` and, since r101, **`system/priv-app/KitsuneMask/KitsuneMask.apk`** — a priv-app is re-scanned by PMS every time system_server starts, so the Manager survives any packages.xml rollback (this fixed "Magisk disappears when re-entering the app").
- `data/system/packages.xml` seed (since r100): 32-package snapshot that keeps the Play Store record (`/system/priv-app/com.android.vending609` ships 32-bit-only libs while the guest is arm64-only — a fresh scan fails with `INSTALL_FAILED_NO_MATCHING_ABIS (-113)`; the seeded record with matching `ft` makes PM skip the rescan). The signed APK cannot be repackaged (v2 signature covers the whole file).
- Seeded `data/adb/magisk.db` pulled from a verified live guest.

## Baking a new ROM revision

1. **Never rebuild from scratch.** Stage only changed files as `<tmp>/stage/rootfs/…` and update the previous archive in place: `7z u prev.7z -snl -mf=off .\rootfs` from the staging PARENT. A full Windows extract drops Unix symlinks ("dangerous link" refused); the in-place update leaves untouched entries byte-identical. No ARM/ARM64 filters (they corrupt the archive).
2. Verify with `7z l`: entries must keep the `rootfs\` prefix and the entry count must be previous+N. Compressing from the wrong cwd bakes prefix-less entries → the extractor scatters the ROM → guest init never found.
3. Copy artifacts into `debug/` immediately — Windows Storage Sense wipes %TEMP% overnight.
4. APK: copy the ROM over `app/src/main/assets/rootfs.7z`, build with `gradlew.bat assembleDebug` (via a PowerShell `-File` script; `cmd /c gradlew.bat` silently no-ops on this host), copy the APK to `debug/`, record md5s here.

## Fresh-install acceptance (must pass with zero hand-patching)

`pm uninstall io.twoyi` → `adb install -r` → launch → poll **ps for system_server** (not `sys.boot_completed`, which can read 1 while system_server is dead; the main zygote process is named `main`) → `/sbin/su -c id` = `uid=0` → KitsuneMask auto-installed → install a test module via the driver → relaunch → module merged, boot scripts ran, overlay file visible in /system. A first boot may sit 1–2 min in a zygote crash-loop during first dexopt — force-stop and relaunch once before treating it as a defect.

## Known host-side issues and mitigations

- **Phantom Process Killer**: the guest legitimately runs 60+ children of the host app while `max_phantom_processes` defaults to 32. Runtime mitigation (host, reversible, plain `adb shell` — uid 2000 is enough, no root needed):
  ```
  device_config put activity_manager max_phantom_processes 2147483647
  settings put global settings_enable_monitor_phantom_procs false
  ```
  The flag **resets on every host reboot**. Permanent no-root option (tested on Android 16): declare `android.permission.WRITE_SECURE_SETTINGS` in the manifest and grant once via `adb shell pm grant io.twoyi android.permission.WRITE_SECURE_SETTINGS` — the grant persists across reboots and the app can then re-apply both flags at every launch.
- Attribution discipline: before blaming the phantom killer for a guest death, check the dropbox for `system_server_wtf` — the observed mid-session deaths traced to binder/system_server crash cascades, and in the entire debugging history no "Trimming phantom processes" kill was ever logged. Architecture experiments (App Zygote, isolatedProcess, QEMU single-PID) are documented in the session log below.
- Guest runtime quirks: `kill -9` on magiskd hangs the init service (recover only via force-stop + relaunch); the su_info cache lives ~3 s (`kill $(pidof magiskd)` to re-test the grant dialog); Kitsune policy enum QUERY=0 / DENY=1 / ALLOW=2 — always read the DB back.
- Host SELinux (untrusted_app_27) blocks exec'ing real /system binaries — the shim maps /system to sandbox copies.

## Release inventory (r101)

| Artifact | md5 |
|---|---|
| `twoyi-0.5.4-r101-rootless-kitsune.apk` (308,645,780 B) | `274af04e2db4bf4a880b438fa7b811b6` |
| `rootfs-rootless-kitsune-r101.7z` | `b19356d3f788fa34d958dcad930f4fbb` |
| shim v12 `libtwoyi_magisk_rootless.so` | `30cf1e71a2fd81fe226ad34799f6067e` |

## Condensed session history

- **r89–r97 (Sep 18–20)**: rootless baseline; syscall shim evolution (path translation, execve mapping, stat interpose, FIFO remap, daemon supervision); sqlite + su policy chain end-to-end; dialog fixes (thread-pool ctor kill, orphan-only cleanup, argv0 parsing); Zygisk DB→rc sync; Manager 26.4 shows correctly; r97 packaged and fresh-install verified.
- **r98 (Sep 22)**: bake pipeline fixed (prefix `rootfs\` lost when compressing from `.` — switched to `7z u` delta bakes); shim v12 with dynamic app-uid discovery (reinstall changes the uid; hardcoded 10402 silently broke su); module installer patches (physical TMPDIR, `set_nvbase` line 774); boot script rewrite (post-boot_completed install, `pm path` self-heal).
- **r99 (Sep 22 PM)**: mid-session system_server crash cascade diagnosed (watchdog wtf + orphan webview_zygote64 holding a dead binder + `vbinder: Broken pipe` flood); phantom flags re-applied; `pm disable` replaces `pm hide` for the microG icon (hide also removed the Settings entry); full fresh-install pass with 4-minute stability soak.
- **r100 (Sep 22 late PM)**: Play Store record loss root-caused to NO_MATCHING_ABIS (32-bit-only APK vs arm64-only guest); packages.xml seeding introduced; vision-verified Play Store + microG entries after fresh install.
- **r101 (Sep 22 evening)**: KitsuneMask promoted to `/system/priv-app` so PMS re-scans it on every system_server start — fixes "Magisk app disappears when re-entering Twoyi" (the guest could stay alive across app re-entry while packages.xml had rolled back, so the boot-script self-heal never re-ran).
- **Phantom-killer architecture study (Sep 22 night)**: dedicated test app (`io.twoyi.phantomtest`, built with aapt2+d8 without gradle), host flags reset to stock. Results: 60 idle children of a cached activity-app survived 7+ min; 8 CPU burners survived home + screen-off/Doze; zero phantom kills ever logged. isolatedProcess+useAppZygote services are reaped by AMS ("isolated not needed") once the app caches — not viable. QEMU-TCG single-PID kills the problem class by construction but costs a full engine rewrite (minutes to boot, ~10% native) — spike material only.
