# 1.0.1: what changed, by audit item

One entry per finding from the 1.0.0 audit, naming the files that changed. Two
items were closed without a change; they are at the bottom with the reason.

## A. Correctness and security

**A1. USB descriptor closed underneath the daemon.** `DaemonService` holds the
`ParcelFileDescriptor` in `usbPfd` for the life of the session and closes it in
`shutdown()`, after `fdServer.stop()` so nothing can send a descriptor that has
just been closed. Files: `DaemonService.kt`.

**A2. Any app could take the radio.** `UsbFdServer.serve()` reads
`LocalSocket.peerCredentials` and returns without sending unless the peer UID
matches `Process.myUid()`. A refused request is logged where the user can see
it. Files: `UsbFdServer.kt`.

**A4. Restore raced the daemon and merged.** Restore now asks for confirmation,
stops the node, waits for the status to reach `Stopped` or `Error` (15 second
bound), and only then replaces files. `importBackup` reads the entire zip into
memory before writing anything, rejects entries that resolve outside `prefs/`,
caps a single entry at 16 MB and the whole zip at 128 MB, and deletes the
existing `prefs/` tree before extracting so the result is the backup and nothing
else. Files: `NodeFiles.kt`, `ui/NodeScreen.kt`, `res/values/strings.xml`.

**A5. Every stop was a SIGKILL.** `stopDaemonProcess()` sends SIGTERM through
`android.os.Process.sendSignal`, waits `Settings.STOP_TIMEOUT_S` (4 s), then
falls back to `Process.destroy()`. The pid comes from `findDaemonPid()`, which
matches the binary name in `/proc/<pid>/cmdline` and the parent pid in
`/proc/<pid>/stat` against this process; `java.lang.Process.pid()` exists in
Java 9 but not in the Android SDK, and the platform's pid field is private. `killStaleDaemons()` does the same in two
passes. Upstream portduino installs no signal handlers, so **firmware patch
0004** adds one: the handler records the signal, and `portduinoCheckShutdown()`
at the top of `loop()` saves the node database and exits. Without 0004 the
SIGTERM is just as abrupt as a kill, so the two halves belong together. Files:
`DaemonService.kt`, `firmware/0004-portduino-clean-shutdown.patch`,
`firmware/build-meshtasticd-android.sh`.

**A6. Restart could leave the node stopped.** Restart is now
`DaemonService.ACTION_RESTART`, posted to the service's single control thread,
which runs the teardown and the start in sequence. The 1200 ms timer in the
activity is gone, `stopping` became `teardown` and is reset at the top of every
start, and the service returns `START_NOT_STICKY`. Files: `DaemonService.kt`,
`MainActivity.kt`.

**A7. Close Meshdroid orphaned daemons.** `DaemonState.serviceAlive` is set in
`onCreate` and cleared in `onDestroy`. Close stops the node, waits for that flag
to go false (10 second bound), then calls `finishAndRemoveTask()`. No
`killProcess`. `killStaleDaemons()` stays for real crashes. Files:
`MainActivity.kt`, `DaemonService.kt`, `DaemonState.kt`.

**A8. One bad connection killed the fd server.** The try block moved inside the
accept loop, with a separate catch for `accept()` itself and a `stopped` flag so
a deliberate stop is not logged as a failure. Files: `UsbFdServer.kt`.

**A9. Leaked stream in `ensureDefaultConfig`.** The asset is read into memory and
written through the same atomic path as everything else. Files: `NodeFiles.kt`.

**A10. `writeConfig` truncated before writing.** All writes go through
`writeAtomically()`: temp file in the same directory, `fd.sync()`, rename.
Files: `NodeFiles.kt`.

## B. Performance

**B1. Log buffer copied per line.** `DaemonState` keeps an `ArrayDeque` under a
lock and refreshes the `StateFlow` at most every 120 ms, with an immediate flush
when the node stops or errors so the tail of a failed session is not held back.
`LogsScreen` filters in a `derivedStateOf` and uses `scrollToItem` instead of
`animateScrollToItem`. Files: `DaemonState.kt`, `ui/LogsScreen.kt`.

**B2. Blocking work on the main thread.** The service does every transition on
its "daemon-control" thread; `onStartCommand` only calls `startForeground`. The
`/proc` scan, the port wait, `openDevice`, `ProcessBuilder.start` and the asset
copy all run there. In the UI, backup, restore, log export, preset loading and
every `config.yaml` read and write go through `Dispatchers.IO`.
`Settings` caches its `SharedPreferences` and is warmed off the main thread from
both the activity and the service. Files: `DaemonService.kt`, `DaemonState.kt`,
all three screens.

## C. Robustness and UX

**C1. Notification claimed the node was running.** The service collects
`DaemonState.status` and `detail` and rebuilds the notification on every change,
including the new `Stopping` state. Files: `DaemonService.kt`.

**C2. No startup watchdog.** A coroutine started with the daemon reports after
`Settings.START_TIMEOUT_S` (30 s) if the status is still `Starting`, with a hint
about the antenna, the cable and the pinmap. Files: `DaemonService.kt`.

**C3. Starting cleared the log.** `beginSession()` writes a blank line and a
`---- start HH:mm:ss ----` marker instead of clearing. Clear is still a button.
Files: `DaemonState.kt`, `DaemonService.kt`.

**C4. Region regex and list.** `loraBody()` finds the `Lora:` block and the
Region regex is applied only inside it. The existing indentation is preserved on
rewrite instead of forced to two spaces. The region list is unchanged but now
sits next to a comment about drift. Files: `NodeFiles.kt`.

**C5. Config editor held a stale copy.** `NodeFiles.configVersion` is bumped on
every write, import and restore. `ConfigScreen` reloads when it changes, and
when there are unsaved edits it keeps them, updates what Revert will restore,
and says the file changed. Files: `NodeFiles.kt`, `ui/ConfigScreen.kt`.

**C7. Dark mode status bar.** `res/values-night/themes.xml` sets
`windowLightStatusBar` to false.

**C8. Notification permission.** Requested once through
`ActivityResultContracts.RequestPermission`, only when it is not already
granted, and not re-asked after a refusal. The Node tab explains what is missing
when it was refused. Files: `MainActivity.kt`, `ui/NodeScreen.kt`.

**C9. Hardcoded strings.** Every user-visible string is in
`res/values/strings.xml`. The one that stayed in code is the log line prefix
`[meshdroid]`, which is diagnostic output rather than UI.

## D. Build and release

**D1. Gradle wrapper.** `gradlew`, `gradlew.bat` and
`gradle/wrapper/gradle-wrapper.jar` are in the tree, taken from the official
`gradle/gradle` repository at tag `v8.9.0`, matching the `distributionUrl`
already in `gradle-wrapper.properties`. Verify it before you trust it:

```bash
sha256sum gradle/wrapper/gradle-wrapper.jar
# 498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17
```

If you would rather generate it yourself, delete the jar and run
`gradle wrapper --gradle-version 8.9` with a system Gradle, or copy the one
Android Studio generates.

**D2. Patch 0002 in the shared package cache.** The build script now treats a
missing or unapplied shim as fatal instead of a warning, and after the build it
greps the binary for `API server binding` and `Using pre-opened CH341 fd`, which
catches a stale object file from a build made before a patch was applied. At
runtime, `DaemonService` warns in the log if a running daemon never reported a
bind address, which is what a build without 0002 looks like from the app's side.
Vendoring the shim into the firmware tree would be the stronger fix and is still
open. Files: `firmware/build-meshtasticd-android.sh`, `DaemonService.kt`,
`DaemonState.kt`, `docs/ARCHITECTURE.md`, `docs/BUILDING.md`.

**D3. Firmware commit pinned.** `FW_COMMIT_DEFAULT="fdb6730"` at the top of the
build script, checked out before building. `FW_COMMIT=<sha>` overrides it and
`FW_COMMIT=keep` builds the checkout as it stands. The script prints the commit
it built from at the end. Files: `firmware/build-meshtasticd-android.sh`,
`docs/BUILDING.md`, `docs/RELEASING.md`.

**D4. Smaller build items.**

- Patch 0001 rejects an empty or over-long `PINEDIO_USB_FD_SOCKET` name, uses
  `memcpy` with the checked length, and closes the received descriptor on the
  `libusb_wrap_sys_device` and `libusb_claim_interface` failure paths, since
  libusb does not take ownership of a wrapped fd.
- Patch 0003's `tx_enabled = true` is now documented in ARCHITECTURE.md.
- `libc++_shared.so` is attributed in README.md and RELEASING.md.
- A `.gitignore` is in the tree, with a note that the wrapper jar is committed
  on purpose.
- CI is still not set up. The build script's checks are what a CI job would run
  first, so the groundwork is there.

## Closed without a change

**A3. Auto Backup of `prefs/`.** Closed at the owner's call: Back up already
goes through the system file picker, which offers local storage as readily as
Drive, so the key leaving the phone is a choice the user makes.

**C6. Single radio, duplicated USB IDs.** Closed at the owner's call: one dongle
per phone is the design limit, because a second radio exceeds what an OTG port
supplies. The IDs did move into `MainActivity.CH341_VID` and `CH341_PID` with a
comment pointing at `res/xml/device_filter.xml`, which is the other copy.

## Version display

The bottom of the Node tab shows `Meshdroid <version> (build <code>)` from
`BuildConfig`, and under it the firmware commit the bundled daemon was built
from. That commit is a `buildConfigField` in `app/build.gradle.kts` and has to be
kept in step with `FW_COMMIT_DEFAULT` in `firmware/build-meshtasticd-android.sh`
when the daemon is rebuilt against newer firmware. `buildFeatures` now has
`buildConfig = true`, which AGP 8 leaves off by default. The version the running
daemon reports for itself still appears in the status card.

## Verify on first build

Two things to watch on the first pass:

1. **Patch 0001's revision marker.** A libdep patched by 1.0.0 has
   `PINEDIO_USB_FD_SOCKET` but not `PINEDIO_FD_PATCH_R2`, and a patch cannot be
   applied on top of its own older output. The build script now spots that,
   deletes the package, re-installs it with `pio pkg install` and applies the
   current patch. Expect one extra download on the first 1.0.1 build.
2. **The SIGTERM path.** With the node running, press Stop and confirm the log
   shows the daemon saving and exiting rather than the
   `still alive 4s after SIGTERM` line. Exit code 0 in
   `meshtasticd exited with code N` is the other tell. 137 means SIGKILL, so the
   pid lookup failed; 143 means SIGTERM reached a daemon with no handler, so the
   binary predates patch 0004 or the call site was not patched in.

Patch 0004's hunks were rebuilt against `fdb67309a` itself rather than upstream
master, which is where the first attempt went wrong: 2.8.1's `loop()` calls
`Time::serviceMonotonic()` immediately after `runASAP = false`, and master does
not. Both patches were applied to a clean copy of that commit here, with no
rejects. Anchoring on a different firmware commit may need the same treatment
again, which is what the new `.rej` check is for.
