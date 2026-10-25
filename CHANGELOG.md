# LineageEMU changelog

## 1.0

- First LineageEMU build (`com.enderbk.LineageEMU`), a standalone app reusing the AEmulator engine.
- First-run flow: accessibility/microphone/camera permissions, resumable 779 MB download of the
  LineageOS 24.0 GSI in a foreground service, install through the existing importer, "All set".
- Home: Start LineageOS, session toggles (camera, microphone, audio, network), reinstall, uninstall
  with a full-data warning.
- Kiosk run: the machine alone over the whole screen; the guest's own logcat fills the screen
  (terminal font, no scrolling, full width) and stays transparent so the boot animation shows
  through until LineageOS is up.
- Hidden key gesture for the in-app menu (Install APK / Minimize / Shutdown VM).
- Guest power button as a double press of both volume keys; the second press may be held for the
  power menu. The host turns black while the guest display sleeps.
- Minimize hides the overlay window instead of detaching it: the machine's SurfaceView, and with it
  the running system, survive a trip to the phone's home screen. The keep-alive service is sticky.
- Install APK hides the overlay for the document picker the same way.
- Host battery mirroring follows the phone live (receiver + change-only pushes) into both the fake
  sysfs and `dumpsys battery`: percentage, charging state and USB/AC.
- Popups, menus and dialogs are drawn inside the overlay window (no separate windows under it).
