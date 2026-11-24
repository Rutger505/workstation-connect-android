# workstation-connect-android

Fork of KDE Connect Android (GPL) = phone side of workstation-connect: own home screen, auto hotspot (Bluetooth RFCOMM control link + Shizuku tethering), trackpad.
`WORKSTATION-CONNECT.md` = layout, upstream hooks, upstream merges, build, updates + publish, log upload, emulator test. `README.md` is upstream's: don't edit.
Link protocol: single source in the laptop repo README (Rutger505/workstation-connect, "Protocol v5"); change it there + `Proto.kt` + `proto.rs` together, bump `Proto.VERSION`.

## Rules

- **Git**: commit + push straight to `main`. Exception: upstream merges in a branch `upstream-vX.Y.Z` + PR (steps in WORKSTATION-CONNECT.md). Remote `upstream` = invent.kde.org.
- Our code only in `workstation-connect/` + the marked upstream hooks listed in WORKSTATION-CONNECT.md. Keep own changes in few separate files/commits so upstream merges stay cheap.
- Never edit upstream strings: "Workstation Connect" rebrand happens at build time in `workstation-connect.gradle`. apply-from scripts can't see AGP classes: load them reflectively.
- App id `com.rutger.workstationconnect` (was `com.rutger.wsconnect`: different app to Android), versionCode = commit count, ship the **debug** build (upstream `debug.keystore`). Build of a dirty tree = `-dirty`, publish refuses it: commit first.
- Publish only via `workstation-connect/publish` on a clean, pushed main (`DRY_RUN=1` to check). Never copy the APK to the CDN by hand: installed apps auto-update from `update.json` (`version.json` = retired channel of the old app id: never write it).
- Logs key never in git (public repo): read at build time from `~/.config/wsconnect-logs/env`.
- Unit tests: `./gradlew testDebugUnitTest --tests 'com.rutger.workstationconnect.*'`.
- Unwanted upstream code (mouse/keyboard receivers): delete, never exclude; KSP's plugin index ignores source-set excludes.

## Pitfalls

- Shizuku tethering: run as UserService (shell uid has TETHER_PRIVILEGED); `TetheringManager.startTethering/stopTethering` are @SystemApi → reflection; rebase context onto `com.android.shell` (TetheringService checks package vs uid); try `setExemptFromEntitlementCheck(true)` first. Reference github.com/carlelieser/shizzi: no license, read, don't copy.
- Shizuku UserService process also runs `App.onCreate`: main-process-only init guarded by process name (`WsConnect.isHelperProcess`).
- Shizuku needs a wireless-debugging restart after every phone reboot; wireless debugging only works on Wi-Fi. Reboot on the road = no auto hotspot until some Wi-Fi. `LinkService` warns (notification 7702, 15 s grace) while `Tether.ready` is false.
- Android kills background links: foreground service + battery optimization exemption.
- Background clipboard reads (Android 10+): READ_CLIPBOARD appop doesn't help; upstream's READ_LOGS trick (granted via Shizuku shell, plus SYSTEM_ALERT_WINDOW). Android 13+ asks per logcat start: accepted by Rutger, no workaround. Password filter = `ClipDescription.EXTRA_IS_SENSITIVE`.
- Silent update: never use `setAppNotInteractingRequired` (KDE Connect always has traffic) or `setAppNotForegroundRequired` (counts the FGS). Shizuku rebind after MY_PACKAGE_REPLACED untested on a Pixel.
- Play Protect blocks browser-installed APKs with notification-listener/SMS perms (Rutger turned scanning off); `adb install` is the clean route.
- Upstream internals read by reflection (`Device.links` in `Links.kt`) and mirrored logic (`NotificationsPlugin.sendNotification` in `Diag.kt`): recheck on every upstream merge.
- Trackpad: volume keys = mouse buttons only while it's in the foreground. Sideways: 3-finger tap then swipe = "up" (pref `wsconnect_trackpad`/`up_edge`, activity portrait-locked). adb can't do 3 fingers: test by writing the pref.
- Phone side verified on Pixel 9 (Android 17, sdk 37): hotspot on ~2.7 s, off <0.5 s.
