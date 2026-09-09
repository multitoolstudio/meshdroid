# Changelog

## 1.0.1

Maintenance release from a full audit of 1.0.0. No new features. Built against
meshtastic/firmware 2.8.1, commit `fdb6730`, which is now pinned in
`firmware/build-meshtasticd-android.sh`.

Fixed, in order of how much they could cost you:

- The USB descriptor could be closed while the node was running. The
  `ParcelFileDescriptor` was a local, so the garbage collector closed the
  duplicate handed to the daemon and the next CH341 recovery attempt got a dead
  fd. It is now owned by the service for the life of the session.
- Any app on the phone could ask the fd hand-off socket for the radio. The
  socket now checks `SO_PEERCRED` and only answers this app's own UID.
- Restore ran while the daemon was still alive and merged into the existing
  `prefs/` instead of replacing it. It now confirms, stops the node, waits for
  it to exit, reads the whole zip before writing anything, and replaces the
  tree. Oversized and out-of-tree entries are rejected.
- Stop, Restart and Close now send SIGTERM and wait before falling back to a
  kill, and firmware patch 0004 gives the daemon a handler that saves the node
  database and exits. The daemon's pid comes from /proc, since
  `java.lang.Process.pid()` is not in the Android SDK.
- Restart is sequenced inside the service instead of a 1200 ms timer in the
  activity, so a slow teardown can no longer leave the node stopped.
- Close Meshdroid waits for the service to finish instead of killing the app
  process on a 600 ms timer, which is what used to orphan daemons.
- One failed fd hand-off no longer ends the accept loop, so CH341 error
  recovery keeps working for the rest of the session.
- `config.yaml` is written through a temporary file and renamed, so an
  interrupted write cannot truncate it. The default config no longer leaks a
  file descriptor.

Performance and responsiveness:

- The log buffer is a deque with coalesced updates instead of a full list copy
  per line. The Logs tab filters and scrolls per update rather than per line.
- Starting, stopping, scanning `/proc`, waiting for the port, backup, restore,
  log export and every `config.yaml` read and write moved off the main thread.

Behaviour and UI:

- The Node tab shows the app version and the firmware commit the bundled daemon
  was built from, at the bottom of the page.
- The notification follows the node's real state instead of claiming the node
  is running from the moment the process launches.
- A start that never reaches `init result 0` says so after 30 seconds.
- Starting no longer clears the log, so the reason a previous start failed
  survives long enough to read.
- The region key is found and rewritten inside the `Lora:` block only, and its
  indentation is preserved.
- The Config tab reloads when `config.yaml` changes underneath it, and says so
  rather than silently overwriting a region set from the Node tab.
- Dark mode gets its own status bar icon colour.
- Notification permission is requested once, and the Node tab explains what is
  missing if it was refused.
- Every user-visible string moved into `res/values/strings.xml`.

Build and release:

- The Gradle wrapper is in the repository, so `./gradlew assembleRelease` works
  from a fresh clone.
- The firmware commit is pinned, with `FW_COMMIT=keep` to override.
- A missing patch 0002 is now a build failure, and the built binary is checked
  for the strings the app parses before it is installed into the app. That check
  greps the binary directly: piping `strings` into `grep -q` reports a false
  failure under `set -o pipefail`, because grep exits on the match and the
  writer dies of SIGPIPE.
- The script detects a libch341 package still carrying patch 0001 from an
  earlier release, re-installs it and applies the current patch.
- The script checks that the patched functions are called from `src/main.cpp`,
  not only defined, and stops if any `.rej` file is left behind. A rejected call
  site plus `--gc-sections` produced a clean build with the feature silently
  missing.
- Patch 0001 bounds the socket name and closes the received descriptor on its
  error paths.

## 1.0.0

First release.

- meshtasticd (meshtastic/firmware, native target) cross-compiled for Android
  arm64 against bionic, packaged inside the app.
- CH341 USB LoRa dongles opened through the Android USB Host API and handed to
  the daemon over a Unix socket (patch 0001 to libch341-spi-userspace).
- Foreground service with wake lock; start on USB attach; clean stop,
  restart and close; orphaned daemon cleanup; port-in-use detection.
- API bound to localhost by default with an "Allow LAN access" switch
  (patch 0002 to the portduino WiFi shim).
- LoRa region picker; the daemon applies `Lora: Region:` from config.yaml on
  a node that has none yet, which triggers identity key generation
  (patch 0003 to the firmware).
- config.yaml editor with device presets (MeshToad, MeshStick, Pinedio USB,
  uMesh, RAK19714), import and export.
- Backup and restore of config.yaml plus the node database and keys as a zip,
  interchangeable with a Linux meshtasticd install.
- Live log view with filter, follow, verbose mode and export.
- Reset identity.
