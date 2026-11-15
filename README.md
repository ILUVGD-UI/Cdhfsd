# Lindroid — Arch Linux on Android, with a built-in VNC desktop

Lindroid is an Android app that runs a full **Arch Linux ARM** system on your
phone or tablet **without root**, using `proot`, and shows the desktop in a
**built-in VNC viewer** (noVNC embedded in a WebView). No separate VNC app,
no Termux, no PC required.

```
┌──────────────────────────────┐
│  Android app (WebView)        │
│  noVNC  ◄──ws──►  :6080      │  built-in WebSocket↔TCP bridge (Java)
│                     │         │
│               TigerVNC :5901  │  inside the container
│  ┌──────────────────────────┐ │
│  │ proot ── Arch Linux ARM  │ │  rootfs in /data/data/…/files/archlinux
│  └──────────────────────────┘ │
└──────────────────────────────┘
```

## How to get the APK

**Option A — GitHub Actions (easiest).** Push this repo to GitHub. The
"Build APK" workflow automatically installs the SDK/NDK, cross-compiles
`proot`, fetches noVNC, and uploads `app-arm64-v8a-debug.apk` (and
`app-armeabi-v7a-debug.apk`) as a downloadable artifact.

**Option B — Android Studio.** Open the project, let it sync, then
*Build > Build Bundle(s)/APK(s) > Build APK(s)*. First run
`ANDROID_NDK=/path/to/ndk scripts/build-proot.sh arm64-v8a armeabi-v7a`
so the app has its `proot` binaries.

**Option C — command line.** Same as the CI workflow:
JDK 17 + Android SDK (platform 34, build-tools 34.0.0) + NDK, then:

```bash
sdkmanager --install "platforms;android-34" "build-tools;34.0.0" "ndk;26.1.10909125"
ANDROID_NDK=$ANDROID_HOME/ndk/26.1.10909125 scripts/build-proot.sh arm64-v8a armeabi-v7a
gradle assembleDebug
# APKs land in app/build/outputs/apk/debug/
```

## Using the app

1. **Setup** — downloads the Arch Linux ARM rootfs (~200 MB) and extracts it.
   Requires ~3 GB of free storage. Do this on Wi-Fi.
2. **Start** — boots the container. On the first start it installs TigerVNC +
   XFCE with pacman (~1 GB, 5–20 minutes); the built-in viewer keeps retrying
   until the desktop is up. Later starts take seconds.
3. Use the desktop. **Stop** shuts everything down.

- **VNC password:** `lindroid` (change it in
  `app/src/main/assets/scripts/lindroid-start`, line with `vncpasswd`, or later
  inside the container with `vncpasswd`).
- **Resolution:** edit the `vncserver :1 -geometry 1280x720` line in the same
  script. You can also open `vnc.html?resize=scale` parameters in `MainActivity`.
- **Files:** Android shared storage is mounted at `/sdcard` inside Arch.
- **External viewer:** any VNC app pointing at `127.0.0.1:5901` works while
  the session is running (e.g. if the WebView misbehaves on your device).

## Honest limitations

- **Arch Linux ARM, not x86-64 Arch.** Regular Arch only targets x86, which
  phones don't run; Arch Linux ARM is the same experience (pacman, AUR helpers
  work, though many AUR packages assume x86).
- **No root, no kernel, no systemd.** proot is userspace emulation: file I/O
  and package management are noticeably slower than a real install, and things
  needing kernel modules (Docker, FUSE, full `systemd`) won't work. XFCE runs
  fine; heavy 3D doesn't.
- **Big first-run download**: ~200 MB rootfs + ~1 GB of packages.
- **Battery/background:** the session runs as a foreground service with a
  persistent notification. Exclude the app from battery optimization for long
  sessions, and expect heat/battery use while the desktop is active.
- **The proot cross-compile is the fiddliest step.** The pinned proot version
  builds with the bundled talloc via `scripts/build-proot.sh`; if a future
  proot/NDK combination breaks, use the Termux recipe
  (`termux-packages/packages/proot/build.sh`) as reference and drop binaries
  at `app/src/main/assets/bin/proot-<abi>` — the app only checks that path.

## Project layout

```
app/src/main/java/dev/lindroid/lindroid/
  MainActivity.java    UI + embedded noVNC WebView
  SessionService.java  foreground service holding the whole session
  Bootstrap.java       rootfs download + extraction, proot extraction
  Proot.java           launches proot with the right binds/env
  WebSocketProxy.java  RFC 6455 bridge: noVNC(ws) ↔ TigerVNC(tcp)
  AssetServer.java     serves noVNC over http://127.0.0.1:8765
app/src/main/assets/scripts/lindroid-start   runs INSIDE the container
scripts/build-proot.sh                       NDK cross-compile of proot
.github/workflows/build-apk.yml              CI that produces the APKs
```

## Licenses

The app code is MIT. It downloads (does not redistribute) the Arch Linux ARM
rootfs at runtime, fetches noVNC (MPL-2.0) at build time, and bundles proot
(GPL-2.0) binaries that the CI compiles from source.
