# Changelog

## 1.2.5

Hardening and housekeeping for the BBS.

- Every command from the mesh is cleaned before anything stores or displays it: control
  characters, terminal escapes, bidirectional overrides and zero-width characters are
  removed, and length is capped. Nothing interprets these commands, and every database call
  was already parameterised, so this closes the quieter problems rather than a hole.
- Storage is bounded. The board keeps its most recent 200 bulletins, a sender may post five
  in an hour, and mail has a per-sender and a total limit. A flooding node can no longer
  fill the phone.
- Mail understands the mention the Meshtastic app inserts. `mail @!433d8ac0 hello`,
  `mail !433d8ac0 hello` and `mail alice hello` all work, and a name that matches a known
  node is stored as that node's number so the mail survives a rename.
- `ping` and `test` report the path. Heard directly gives snr and rssi; relayed gives the
  hop count, since signal figures describe the last hop rather than the whole path.
- New `admin` command: `admin status`, `admin clear board`, `admin clear mail`,
  `admin clear delivered` and `admin delete <n>`, refused on channels and from anyone not
  in the admin table.
- Admin commands require an encrypted direct message by default. A sender's node number is
  whatever they put in the packet, so the admin list alone authenticates nobody; the
  firmware reports pki_encrypted only after decrypting with this node's private key, which
  a forger cannot produce. The requirement can be turned off for a mesh without keys, and
  the switch says what that costs.
- The BBS tab manages admin nodes and clears the board, all mail, or just collected mail,
  with counts.

## 1.2.4

The BBS. A bulletin board and mail drop that answers commands from the mesh.

- New **BBS** tab: a switch, the activity log of what was asked and what was answered, and
  a console that runs a command locally without transmitting.
- Built-in commands: `help`, `about`, `ping`, `board` (list, read, post) and `mail` (send
  and collect). Bulletins and mail live in SQLite in the app's private storage.
- Direct messages are always treated as commands. Channel messages count only when they
  start with the prefix, `!` by default, which keeps the BBS out of ordinary conversation.
- Replies are queued and spaced four seconds apart, split on word boundaries when longer
  than a packet, capped at three parts, and limited to one reply per sender per cooldown.
  A BBS that answers instantly and at length is a BBS that saturates the channel.
- The BBS holds its own connection to the node rather than sharing the Mesh tab's, so it
  runs whether or not a screen is open. With sharing on that connection is one client among
  several; with it off the BBS takes the node's only slot, which the tab says.
- Off by default.

Modules are written against the transport interface rather than against protobuf, so the
same command set will work over MeshCore later. User-supplied modules are next.

## 1.2.3

The multiplexing proxy. Several clients can share the node at once.

- meshtasticd now listens on loopback port 4404 and Meshdroid owns 4403, holding the
  daemon's one connection and fanning it out. The Meshtastic app, the Mesh tab and the
  Python CLI can all be attached together.
- Config dumps are routed to the client that asked for them, in the order they asked,
  rather than broadcast. Live traffic goes to everyone.
- A disconnect from one client is handled locally and never forwarded, since telling the
  daemon to close the upstream connection would drop every other client with it.
- On by default. The Node tab has a switch that turns it off and hands 4403 straight back
  to the daemon, restoring the previous one-client behaviour.
- With the proxy on, LAN access is the proxy's listener and the daemon stays on loopback
  whatever the LAN switch says. Firmware patch 0002 still carries that switch when the
  proxy is off.
- The Mesh tab reports how many other clients are attached.

## 1.2.2

- Fixed the message row on the Mesh tab being laid out below the navigation bar, which
  showed as a stray rounded rectangle under the event list. The weight now sits on a plain
  Box with the list filling it, rather than on the SelectionContainer with the list
  stretching inside. The Logs tab had the same structure and was quietly clipping its line
  count; both are fixed.
- The Mesh tab keeps the message row above the keyboard, and gives up the status and
  sensor cards while the keyboard is open so the event list and the input keep their
  space instead of everything competing for half a screen. The cards return on dismiss.

## 1.2.1

- Phone sensor telemetry. The Mesh tab lists the environment sensors this phone actually
  has, with a live reading and the vendor name for each, and publishes barometric pressure
  and ambient light to the mesh as environment telemetry. Off by default.
- The sensors are probed at runtime rather than assumed. A barometer is common, an ambient
  light sensor close to universal, and ambient temperature and humidity are absent from
  almost every phone made in the last decade.
- Publishing goes over the mesh link, not through a firmware patch, so it costs nothing on
  a firmware bump. It only happens while the link is connected, which for now means while
  the Meshtastic app is displaced. The multiplexing proxy removes that constraint.
- `BatteryReporter` serialises its writes. The broadcast receiver and the heartbeat thread
  shared one temporary file name, so whichever renamed first left the other with none.
- A packet that fails to decode no longer ends the mesh session, and a clean close by the
  daemon is logged. A null read is what being displaced by another client looks like, and
  it previously logged nothing at all.

## 1.2.0

The mesh link. Groundwork for the BBS, and useful on its own.

- New **Mesh** tab: connect to the node, watch text messages, node sightings and other
  traffic as they arrive, and broadcast a message.
- `MeshLink` is a transport interface expressed in domain types, with
  `MeshtasticTcpLink` as the first implementation. The BBS, phone sensor telemetry and
  an eventual MeshCore mode all sit on the interface rather than on protobuf types.
- Meshtastic protobuf definitions are vendored at a pinned commit and compiled with the
  javalite runtime by the Gradle protobuf plugin.
- Framing, the want_config handshake, heartbeats and reconnect with backoff are handled
  by the link. It disconnects itself when the node stops.

meshtasticd still accepts one client, so connecting displaces the Meshtastic app. The
multiplexing proxy that removes that limit is a later 1.2.x release, and it is a second
implementation of `MeshLink` rather than a change to anything above it.

## 1.1.1

- The node keeps reporting its battery level while the phone charges. Firmware
  patch 0006 removes the upstream rule that replaces the level with the 101 that
  clients display as PWR whenever a node is charging. That rule suits a board whose
  battery backs up its USB supply; on a phone it discards the only reading anyone
  wants, and a node on a powered hub showed PWR with 1.1.0.

## 1.1.0

Phone battery telemetry. Built against meshtastic/firmware 2.8.1, commit `fdb6730`.

- The node reports the phone's battery percentage and charging state to the mesh
  instead of appearing permanently USB powered. Other nodes see the charge level
  the way they would for any battery-powered node.
- New firmware patch 0005 adds a portduino battery source that reads a file the app
  writes. It sits at the head of `Power::setup()`'s probe chain and is inert unless
  `MESHTASTICD_BATTERY_FILE` is set.
- A reading older than three minutes counts as no battery, so a stopped or crashed
  app falls back to the USB powered state rather than leaving a stale percentage on
  the mesh.
- The Node tab carries the switch, on by default, and shows the level currently
  being published.
- The build script applies patch 0005 and checks the binary for its log string.

Charging is reported alongside the real percentage rather than as the "plugged in,
ignore the battery" convention, because a phone always has a battery and the level
stays meaningful while it charges.

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
