# workstation-connect (Android)

Fork of [KDE Connect Android](https://invent.kde.org/network/kdeconnect-android) (GPL) with
workstation-connect built in: own home screen, auto hotspot (BLE + Shizuku tethering), trackpad
with volume keys as mouse buttons. KDE Connect stays the engine (pairing, encryption,
notifications, clipboard, files). Laptop side: [Rutger505/workstation-connect](https://github.com/Rutger505/workstation-connect).

## Layout

- `wsconnect/` all our code: `java/com/rutger/wsconnect` (Kotlin), `aidl/`, `res/`, `wsconnect.gradle`
- Upstream files touched (keep it to these, marked `workstation-connect`):
  - `build.gradle.kts`: last line applies `wsconnect/wsconnect.gradle`
  - `src/main/AndroidManifest.xml`: launcher moved to `HomeActivity`, one block of our components
  - `src/main/java/org/kde/kdeconnect/KdeConnect.kt`: helper-process guard + `WsConnect.init`
  - `src/main/java/org/kde/kdeconnect/plugins/clipboard/ClipboardListener.kt`: `setText` → `Clipboard.fromLaptop` (history)
- Rebrand "KDE Connect" → "Workstation Connect" happens at build time in `wsconnect.gradle` (generated res
  overlay of every upstream string containing it, all locales + merged-manifest label rewrite); never edit upstream strings
- App id `com.rutger.wsconnect`, versionCode = commit count, we ship the debug build
  (signed with upstream's `debug.keystore`, so updates install over each other).

## Upstream updates

`main` = last merged upstream release tag + our commits. `wsconnect/upstream-check` prints the
merged and latest upstream tags (exit 1 when behind). Update:

```sh
git fetch upstream --tags
git switch -c upstream-vX.Y.Z
git merge vX.Y.Z            # conflicts only in the 4 files above
./gradlew assembleDebug
```

## Build

```sh
export ANDROID_HOME=~/.local/share/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug    # build/outputs/apk/debug/kdeconnect-android-debug.apk
```
