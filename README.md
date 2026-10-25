# LineageEMU

**LineageOS on your phone, over your current system.**

LineageEMU downloads a LineageOS 24.0 (Android 16, arm64) GSI once, installs it as a virtual
machine and runs it over the whole screen through an accessibility overlay. Your own Android keeps
running underneath; shutting LineageOS down brings it straight back.

LineageEMU is its own app (`com.enderbk.LineageEMU`) — not a build or a flavor of AEmulator. The
emulator engine and the look of the screen come from [AEmulator](https://github.com/uxazu/aemulator)
(GPL-3.0, in turn based on SENSES); this app is the LineageOS-first packaging around it.

## First run

1. **Permissions** — accessibility (the overlay that draws LineageOS over everything), microphone,
   camera, and optionally notifications and battery optimization.
2. **Download** — the 779 MB GSI is fetched once into the app, resumably, by a foreground service;
   the archive is removed as soon as it has been installed.
3. **All set** — the system is ready.

## Home

- **Start LineageOS** boots the machine over the whole screen.
- Session toggles: **disable camera, microphone, audio, network** for that run.
- **Reinstall ROM** — wipes the machine and downloads/installs it again.
- **Uninstall ROM** — deletes the machine and everything inside it (apps, accounts, files).

## While LineageOS runs

The overlay shows only the machine. During boot the aemu log takes the whole screen until the guest
is up.

**Hidden menu** (volumes are pressed one after another, then both together):

```
VOL UP, VOL DOWN, VOL UP, VOL DOWN, (UP+DOWN)
VOL DOWN, VOL UP, VOL DOWN, VOL UP, (UP+DOWN)
```

It offers **Install APK**, **Minimize** (drops the overlay, keeps the machine running — the
"LineageOS is running" notification brings it back) and **Shutdown VM**.

**Guest power button:** inside LineageOS press both volume keys together twice. A phone does not
hand the real power key to apps, so this is what powers the machine's screen off/on.

## Build

```sh
JAVA_HOME=<jdk17> ANDROID_HOME=<sdk> ./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. First run needs about 5 GB of free
storage (about 3 GB stay used after the install).

## License

GPL-3.0 — see `LICENSE`. The engine and host code are derived from AEmulator; LineageOS itself and
the GSI are the work of their respective projects.
