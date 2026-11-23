# workstation-connect (Android)

Fork of [KDE Connect Android](https://invent.kde.org/network/kdeconnect-android) (GPL) with
workstation-connect built in: own home screen, auto Wi-Fi hotspot (Bluetooth RFCOMM control link + Shizuku; hotspot is the only tethering, no Bluetooth tethering), trackpad
with volume keys as mouse buttons. KDE Connect stays the engine (pairing, encryption,
notifications, clipboard, files). Laptop side: [Rutger505/workstation-connect](https://github.com/Rutger505/workstation-connect).

## Layout

- `wsconnect/` all our code: `java/com/rutger/wsconnect` (Kotlin), `aidl/`, `res/`, `wsconnect.gradle`
- Upstream files touched (keep it to these, marked `workstation-connect`):
  - `build.gradle.kts`: last line applies `wsconnect/wsconnect.gradle`
  - `src/main/AndroidManifest.xml`: launcher moved to `HomeActivity`, one block of our components
  - `src/main/java/org/kde/kdeconnect/KdeConnect.kt`: helper-process guard + `WsConnect.init`
  - `src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardListener.kt`: `setText` → `Clipboard.fromLaptop` (history)
  - `src/main/java/org/kde/kdeconnect/ui/MainActivity.kt`: `onCreate` → `HomeRedirect.toHome` (notifications/shortcuts land in our UI)
  - `src/main/java/org/kde/kdeconnect/BackgroundService.kt`: `createForegroundNotification` public, returns via `StatusNotification.decorate`
    (one ongoing notification, ID 1, shared with `LinkService`: title KDE Connect status, text laptop link, upstream's actions)
- No hook, but upstream internals read by reflection: `Device.links` (private) in `wsconnect/.../Links.kt` (link type on Home,
  dropping the laptop's LAN link on `CMD_NET_CHANGED`). Check the field still exists after an upstream merge
- Upstream files deleted: laptop→phone mouse/keyboard receivers (`plugins/{mousereceiver,remotekeyboard,remotekeyboardime,inputdevicesreceiver}`,
  `KdeConnectAccessibilityService.kt`, the IME test); `wsconnect.gradle` strips their manifest services. Unwanted (scrcpy types on the phone);
  deleted, not excluded, because KSP's plugin index ignores source-set excludes
- Rebrand "KDE Connect" → "Workstation Connect" happens at build time in `wsconnect.gradle` (generated res
  overlay of every upstream string containing it, all locales + merged-manifest label rewrite); never edit upstream strings
- App id `com.rutger.wsconnect`, versionCode = commit count, we ship the debug build
  (signed with upstream's `debug.keystore`, so updates install over each other).

## Link protocol (v5, classic Bluetooth RFCOMM)

- `wsconnect/.../Proto.kt`, keep in sync with the laptop's `proto.rs`. `LinkService` = RFCOMM server
  (`listenUsingRfcommWithServiceRecord`, secure: bonded + encrypted only), UUID `6f2b0010-3c1d-4e8a-9f41-7a5d2c9e0b10`;
  laptop finds the channel via SDP and pages the bonded phone directly (no advertising; v4 and older were BLE GATT)
- Multiple clients (laptop + desktop): one reader thread + one writer per socket, an I/O error closes only that client.
  Accept failure while Bluetooth is on → server reopened with backoff (2 s doubling to 60 s)
- Frames both ways: `[len u8][payload]`, len 0 = keepalive (ignored)
- Laptop → phone, 1 byte: `CMD_OFF` (0) stops the hotspot, `CMD_ON` (1) starts it, `CMD_NET_CHANGED` (3) drops the
  laptop's LAN links, `CMD_PING` (5) → state frame. 2 and 4 (old BT tethering commands) retired: logged + ignored, never reuse
- Phone → laptop, 4 bytes `[flags, battery %, signal 0-4/255, version 5]`: after accept, on every change, on ping
- Flags: 1 Shizuku, 2 unused (was BT tethered, always 0, never reuse), 4 hotspot, 8 wanted, 16 error
- Last client gone 3 min while wanted → hotspot off

## Upstream updates

`main` = last merged upstream release tag + our commits. `wsconnect/upstream-check` prints the
merged and latest upstream tags (exit 1 when behind). Update:

```sh
git fetch upstream --tags
git switch -c upstream-vX.Y.Z
git merge vX.Y.Z            # conflicts only in the 5 files above; modify/delete on the dropped receivers: git rm them again
./gradlew assembleDebug
```

## Build

```sh
export ANDROID_HOME=~/.local/share/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug    # build/outputs/apk/debug/kdeconnect-android-debug.apk
```

## Updates

- `wsconnect/java/.../update/Updater.kt`: checks `version.json` at app start, on Home resume (max every 30 min),
  every 10 min from `LinkService` (Handler tick) and every 15 min via WorkManager (backup, also with the link off;
  any network incl. mobile data); downloads, verifies sha256 and installs right away via a PackageInstaller
  session: no waiting for tethering, the app being on screen or install constraints (Rutger's wish). Base URL:
  `BuildConfig.UPDATE_URL` = `https://cdn.rutgerpronk.com/artifacts/workstation-connect/`,
  `WSCONNECT_UPDATE_URL=<url>` at build time overrides it (adds `-local` to versionName). Uncommitted changes add `-dirty`.
- Silent = self-update (`UPDATE_PACKAGES_WITHOUT_USER_ACTION` + `USER_ACTION_NOT_REQUIRED`), also right after an
  adb/browser install, as long as "install unknown apps" is granted. Otherwise: Home card + notification, tap.
  A failed silent install of a version is retried after 1 h. Checks every 10 min log only new errors (every AppLog
  line makes the periodic log upload run).
- **Hotspot across the update** (the install kills the process): before the commit `Tether.saveForUpdate` stores
  "wanted" with a timestamp (prefs `wsconnect-tether`), then the log is uploaded (reason `update`). The new process
  (`WsConnect.init`, also `BootReceiver` on MY_PACKAGE_REPLACED, idempotent) calls `Tether.restoreAfterUpdate`:
  wanted again right away (laptop sees FLAG_WANTED), hotspot on once the Shizuku helper binds; ignored when older
  than 5 min, gives up after 5 min without helper. No laptop reconnecting within 3 min → off (`armGoneTimeout`).
  Install failed/needs a tap → flag cleared.
- After an update `BootReceiver` (MY_PACKAGE_REPLACED) restarts LinkService: the start from `Application.onCreate`
  is refused there (background FGS start), the broadcast is exempt. Shizuku helper rebinds via `Tether.init`.
- Publish (Rutger): `wsconnect/publish [apk]` uploads `workstation-connect-<versionCode>.apk`, server-side copies it
  to `workstation-connect.apk` (no second upload), then `version.json`; verifies each, keeps 3 versioned APKs.
  Refuses `-dirty`/`-local` and non-newer versionCodes. `DRY_RUN=1`: checks only, prints the mcli commands.

Emulator test (AVD `WsConnect_API35`, port 5590): build A and B (B = one commit later, e.g. a temporary empty
commit, dropped afterwards) with `WSCONNECT_UPDATE_URL=http://10.0.2.2:8765/`; serve B as
`workstation-connect-<code>.apk` + `version.json` with `python3 -m http.server 8765 --bind 127.0.0.1`; install A,
`appops set com.rutger.wsconnect REQUEST_INSTALL_PACKAGES allow`, enable the link → B installs right away,
`dumpsys activity services com.rutger.wsconnect` shows LinkService. Fake tethering (as `adb root`):
`am broadcast -a android.net.conn.TETHER_STATE_CHANGED --esal tetherArray wlan1` (`rndis0` = idle again).

## Log upload

- `wsconnect/java/.../logs/`: `LogUpload` (Android), `LogUploadLogic` + `SigV4` (pure, JVM tests in `wsconnect/test/`,
  `./gradlew testDebugUnitTest --tests 'com.rutger.wsconnect.*'`). Mirrors the laptop's `logs.rs`/`sigv4.rs`.
- PUT `https://cdn.rutgerpronk.com/wsconnect-logs/phone/<Build.MODEL>/<YYYY-MM-DD>/<HHMMSS>-<reason>.log` (UTC,
  path-style, SigV4 header auth, signed payload, us-east-1, SeaweedFS). Body: header (device, app version, sdk,
  reasons, time) + AppLog lines after the marker (prefs `wsconnect-logs`: last line of the last successful upload;
  gone after rotation/clear → whole file), newest ~1 MB.
- Reasons: `start` (process start, so also after an update), `link-connect`/`link-lost` (RFCOMM), `hotspot-on`/
  `hotspot-fail`, `update` (blocking right before the install commit; install failed), `crash` (uncaught exception
  handler writes the trace to AppLog synchronously, uploaded on the next start), `periodic` (10 min tick / worker,
  only with new lines; `log upload: ` lines don't count), `manual` (LogActivity "Logs uploaden", status as extra).
  Triggers within 5 s = one upload; failure keeps the marker, reasons retried with the next trigger.
- **Key never in git** (public repo): write-only key read at build time by `wsconnect.gradle` from
  `~/.config/wsconnect-logs/env` (`WSCONNECT_LOGS_ACCESS_KEY=…`, `WSCONNECT_LOGS_SECRET_KEY=…`; `WSCONNECT_LOGS_ENV`
  overrides the path) into `BuildConfig.LOGS_*`. Missing → empty → upload off (one AppLog line, button hidden).
  The build prints only found/MISSING. It does end up in the published APK: write-only, so worst case is junk uploads.
- Debug lines for upstream parts (`Diag.kt`, `Clipboard.kt`, `TrackpadActivity`): per paired device reachable/link
  types/plugin state on change, notification filter decisions mirrored from `NotificationsPlugin.sendNotification`
  (keep in sync on upstream merges), clipboard sent/received/skipped-sensitive + READ_LOGS state, trackpad
  open/close/dropped input. Package names and lengths only, never clipboard or notification text.
