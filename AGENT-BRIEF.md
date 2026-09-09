# Meshdroid: briefing for a new session

State at the time of writing: version 1.2.2, built against meshtastic/firmware 2.8.1
commit `fdb6730`, protobufs commit `ca2cb1a8`. Working and installed on a Pixel 9 Pro.

## What it is

An Android app that runs the real meshtasticd (the Meshtastic Linux daemon, built from
meshtastic/firmware's native/portduino target) on an unrooted phone, driving a CH341-based
USB LoRa dongle over USB-C OTG. The official Meshtastic Android app connects to it at
127.0.0.1:4403. Package `studio.multitool.meshdroid`, by Multitool Studio. GPL-3.0.

Repository lives at `/home/john/Dev/meshdroid/meshdroid-src`, git branch `main`, tags
`v1.0.0` through `v1.2.0`. A local git server is planned; nothing is pushed yet.

## Repository map

```
app/                       Kotlin, Jetpack Compose, Material 3. One activity, one service.
  DaemonService.kt         owns the meshtasticd process, USB device, wake lock, fd hand-off,
                           the control thread, the startup watchdog and the notification
  DaemonState.kt           StateFlows shared with the UI; log parsing; Settings
  BatteryReporter.kt       writes the phone's battery state to files/battery
  PhoneSensors.kt          runtime probe of the phone's environment sensors
  EnvironmentReporter.kt   publishes pressure and light over the mesh link
  NodeFiles.kt             config.yaml, presets, Lora.Region rewrite, backup zip, reset
  UsbFdServer.kt           sends the usbfs fd over an abstract Unix socket (SCM_RIGHTS)
  MainActivity.kt          USB discovery, permission, attach intent, Compose host
  mesh/MeshLink.kt         transport interface and its domain types
  mesh/MeshtasticTcpLink.kt   MeshLink over meshtasticd's TCP API
  mesh/StreamFrames.kt     0x94 0xc3 length-prefixed framing with resync
  mesh/MeshLinkHolder.kt   process-wide link owner and capped event history
  ui/                      App.kt (theme, nav), NodeScreen, ConfigScreen, MeshScreen, LogsScreen
  src/main/assets/         config.yaml (default = MeshToad) and presets/*.yaml
  src/main/proto/          vendored meshtastic/*.proto, compiled by the protobuf plugin
  src/main/protoinclude/   nanopb.proto and google/protobuf/descriptor.proto, include only
  src/main/jniLibs/arm64-v8a/   libmeshtasticd.so + libc++_shared.so (built, gitignored)
firmware/
  build-deps-android.sh    NDK cross-build of yaml-cpp, jsoncpp, libuv, libusb, argp, libi2c
  build-meshtasticd-android.sh   pins the firmware commit, applies patches, builds, verifies
  platformio-android.ini   [env:android-arm64], board = buildroot (TARGET_* toolchain hook)
  variant.h                Android variant: no RV3028 RTC, HAS_GPS 0, HAS_SCREEN 0
  android_compat.h         char_traits<unsigned char> shim, force-included, C++ only
  extra_scripts/android_link_flags.py   PIE, rpath $$ORIGIN, 16 KB pages, libi2c order
  0001-libch341-android-fd.patch        libpinedio-usb.c: fd from env or SCM_RIGHTS socket
  0002-wifiserver-bind-addr.patch       framework WiFi shim: MESHTASTICD_BIND_ADDR
  0003-yaml-initial-region.patch        firmware: config.yaml Lora.Region on first boot
  0004-portduino-clean-shutdown.patch   firmware: SIGTERM saves the node database and exits
  0005-portduino-battery-file.patch     firmware: battery level from a host-written file
  0006-portduino-battery-while-charging.patch  firmware: keep the level while charging
docs/                      public: BUILDING, ARCHITECTURE, RELEASING, AUDIT-FIXES-1.0.1
```

## The mechanisms that make it work

1. USB without root. The app opens the device with UsbManager, gets the usbfs fd, and the
   daemon wraps it with libusb_wrap_sys_device after setting
   LIBUSB_OPTION_NO_DEVICE_DISCOVERY. Patch 0001.
2. fd delivery. Android's ProcessBuilder closes inherited fds in the child, so the fd goes
   over an abstract Unix socket named in PINEDIO_USB_FD_SOCKET (unique per start).
   UsbFdServer.kt on the app side, patch 0001 on the C side. The accept loop stays open so
   the firmware's CH341 error recovery can reconnect and get the fd again, and it checks
   SO_PEERCRED so only this app's UID is served.
3. Executing a binary. The daemon is packaged as jniLibs/arm64-v8a/libmeshtasticd.so with
   useLegacyPackaging = true; nativeLibraryDir is the only exec-able location for an
   unrooted app. libc++_shared.so sits beside it; LD_LIBRARY_PATH and an $ORIGIN rpath both
   point there.
4. Battery telemetry. The app writes `files/battery`, the daemon reads it through patch
   0005 as a HasBatteryLevel implementation, and patch 0006 stops the telemetry module
   replacing the level with 101 while charging.
5. The mesh link. MeshLink is a transport interface in domain terms. MeshtasticTcpLink
   implements it over the daemon's TCP API with vendored protobufs. Connecting takes the
   daemon's single client slot.

## Runtime contract between app and daemon

Command line: `libmeshtasticd.so -c <files>/config.yaml -d <files>/meshtasticd -p 4403 [-v]`.
Environment: PINEDIO_USB_FD_SOCKET, MESHTASTICD_BIND_ADDR (127.0.0.1 or 0.0.0.0),
MESHTASTICD_BATTERY_FILE (only when battery reporting is on), LD_LIBRARY_PATH, HOME.
stdout+stderr are read line by line into DaemonState.log.

Log lines the UI depends on (DaemonState.parse):
- `Use nodenum 0x...`            node ID
- `S:B:<n>,<version>,...`        firmware version
- `Using pre-opened CH341 fd N (serial X)`   radio serial
- `init result 0`                status becomes RUNNING
- `API server listen on TCP port N`
- `API server binding A:P`       from patch 0002 (fprintf to stderr)
- `using UNSET` / `Generate new PKI keys` / `Set radio: region=`   regionUnset flag
- `Portduino notneg` / `Portduino checkzero`   fatal framework errors
- `Couldn't open LoRa USB device` / `Could not receive USB fd`

Strings the build script greps out of the finished binary, which is how a patch that failed
to reach the build is caught: `API server binding`, `Using pre-opened CH341 fd`,
`saving node data and exiting`, `Using host battery level from`.

Changing any of those strings upstream silently breaks the parsing. Check on each firmware
bump.

## Invariants and gotchas

Firmware and protocol

- Firmware 2.8+ does not mint identity keys, transmit, or write config.proto until
  config.lora.region is set. Patch 0003 seeds it from config.yaml only while the stored
  region is UNSET. The sim radio (-s) is exempt upstream.
- meshtasticd's TCP API accepts one client. The Meshtastic app, the Python CLI and
  MeshtasticTcpLink displace each other. The multiplexing proxy is the planned fix.
- `readPowerStatus()` deep sleeps the node after ten voltage readings below the OCV floor,
  and zero is below it. Patch 0005 substitutes 3800 mV when the host supplies no voltage. A
  percentage-only battery source without that substitution kills the daemon about a minute
  after the phone comes off the charger.
- Upstream `DeviceTelemetry.cpp` replaces the battery level with 101 (shown as PWR) while
  charging. Patch 0006 removes that on portduino only.
- Inside classes deriving from Arduino's Print, an unqualified printf() is Print::printf and
  ends up in WiFiServer::write(), which is a stub that raises SIGINT (exit 130). Use
  ::fprintf(stderr, ...). Patch 0004 deliberately leaves SIGINT unhandled so that stays a
  visible crash.
- OTG current: `SX126X_MAX_POWER` in config.yaml caps transmit power. The `Final Tx power`
  line in the log is the number that matters. USB transfer errors a few seconds after a
  clean start are a brownout, and the CH341 needs a physical unplug to recover.

Patches

- Patch anchors must be taken from the pinned firmware commit, not from upstream master.
  2.8.1's `loop()` calls `Time::serviceMonotonic()` right after `runASAP = false` and master
  does not, which is how patch 0004 first landed with two rejected hunks.
- The link runs with `--gc-sections`. A patch that defines a function but whose call site
  hunk was rejected produces a clean build with the feature silently missing. The build
  script now greps `src/main.cpp` for the call sites and fails on any leftover `.rej`.
- Patch 0002 lives in the shared PlatformIO package cache. `pio pkg update` reverts it, and
  a build without it binds every interface whatever the LAN switch says.
- A libdep already carrying an older revision of patch 0001 cannot have the newer one
  applied on top. The script detects that through the `PINEDIO_FD_PATCH_R2` marker,
  re-installs the package and patches it again.
- SCons expands `$ORIGIN`; write `$$ORIGIN` in Python LINKFLAGS.
- build_unflags removes every occurrence of a flag, including ones added in build_flags of
  the same env.

Android and Kotlin

- `ParcelFileDescriptor` closes its descriptor from its finalizer. A PFD left in a local
  goes out of scope when the method returns and the collector then closes the usbfs fd
  underneath a running daemon. It is held in a DaemonService field for the session.
- The abstract socket namespace is not scoped to an app. UsbFdServer checks SO_PEERCRED.
- `java.lang.Process.pid()` is not in the Android SDK and the platform's pid field is
  private. DaemonService finds the daemon by matching cmdline and parent pid under /proc.
- `Process.destroy()` on Android is SIGKILL, which is where exit code 137 comes from. Exit
  0 means patch 0004 handled SIGTERM. 143 means SIGTERM reached a daemon without the patch.
- Kotlin block comments nest. A path like `prefs/*` inside a KDoc comment opens a second
  comment.
- Compose: `weight` is a scope member, never imported.
- `Modifier.weight()` belongs on a plain Box with the child filling it. Weight on a
  SelectionContainer with `fillMaxHeight()` on the list inside measured whatever followed
  off the bottom of the screen, which showed as a stray rectangle under the event list.
- Android's crash log buffer (`adb logcat -b crash -d`) is a ring; clear it with `-c`.

Build environment

- Gradle needs JDK 17 or 21. A newer default JDK fails with a bare version number as the
  "What went wrong" text. `/usr/lib/jvm/java-17-temurin-jdk` is the one in use, set through
  `org.gradle.java.home` in `~/.gradle/gradle.properties`. Some `java-NN-openjdk` paths are
  JRE only; check for `bin/javac`.
- With `set -o pipefail`, `something | grep -q` fails whenever the match is found early:
  grep exits, the writer dies of SIGPIPE, and the pipeline takes its status. Search binaries
  with `grep -qaF pattern file` and never through a pipe.
- The protobuf plugin needs `create("java")`, not `named("java")`: on Android the java
  builtin is not pre-registered.
- `nanopb.proto` and `descriptor.proto` are include-only. Compiling nanopb.proto would
  generate extension code needing DescriptorProtos, which javalite does not ship.

## Build loop

Firmware, only when something under `firmware/` changed:

```
cd /home/john/Dev/meshdroid/meshdroid-src/firmware
./build-meshtasticd-android.sh ~/meshtastic-firmware        # ~1 min incremental
cd ..
```

App:

```
cd /home/john/Dev/meshdroid/meshdroid-src
./gradlew assembleDebug          # or installDebug
adb logcat -c
adb logcat -s meshtasticd MeshtasticdService UsbFdServer MeshLink
```

Errors in the firmware build: paste the first `error:` block, and any `.rej` file. Errors in
the app: paste every line starting with `e:`. Both loops worked well with an agent.

If the firmware tree gets into a half-patched state:

```
cd ~/meshtastic-firmware
rm -f src/*.rej src/*.orig src/platform/portduino/*.rej src/platform/portduino/*.orig
git checkout -- src/
```

The build script reapplies every patch from clean after that.

## Applying a new codebase from an agent

This is where the one real breakage happened. `rsync --delete` without an ignore filter
deleted `app/src/main/jniLibs` and `firmware/android-prefix`, which are gitignored and so
absent from any zip, and cost a full dependency rebuild. Always use the filter:

```
cd /home/john/Dev/meshdroid/meshdroid-src
git status --short                      # must be empty before starting

rm -rf /tmp/mdnew
unzip -q ~/Downloads/meshdroid-vX.Y.Z.zip -d /tmp/mdnew
rsync -ac --delete -f':- .gitignore' \
      --exclude '.git/' --exclude '.gitignore' --exclude '.gitattributes' \
      /tmp/mdnew/meshdroid-X.Y.Z/ ./
chmod +x gradlew firmware/*.sh
git status --short
```

`-f':- .gitignore'` makes every ignored path invisible to the transfer, so build outputs,
the cross-build prefix and the daemon binary survive. `-c` compares checksums rather than
size and timestamp, which matters because a zip's timestamps can make rsync skip a file
that really did change. rsync will report `cannot delete non-empty directory` for protected
directories; that message means the filter worked.

Verify afterwards, before building:

```
ls app/src/main/jniLibs/arm64-v8a/      # libmeshtasticd.so, libc++_shared.so
ls firmware/android-prefix/lib          # six .a files
```

If the working tree had uncommitted edits of your own, `git diff` after the rsync shows
what the overlay replaced, and `git checkout -- <file>` brings your version back.

## Git

```
git add -A
git ls-files | grep -Ei '\.so$|\.jks$|\.keystore$|keystore\.properties|android-prefix/|deps-src/|/build/|\.orig$|\.rej$|local\.properties|\.idea/'
git commit -m "Meshdroid X.Y.Z: summary"
git tag -a vX.Y.Z -m "Meshdroid X.Y.Z, built against firmware fdb6730"
```

The grep must come back empty. `gradle/wrapper/gradle-wrapper.jar` is the only binary that
is committed on purpose, and it does not match that pattern.

Preferred workflow for a new drop, so an overlay cannot silently stomp something:

```
git switch -c import/X.Y.Z
# rsync, build, test
git add -A && git commit -m "Meshdroid X.Y.Z"
git switch main && git merge --no-ff import/X.Y.Z
```

## Version pinning

Three pins that move together and must be recorded in `CHANGELOG.md` on each bump:

- `FW_COMMIT_DEFAULT` in `firmware/build-meshtasticd-android.sh`, currently `fdb6730`
- `DAEMON_FIRMWARE_COMMIT` in `app/build.gradle.kts`, shown at the bottom of the Node tab
- the protobufs commit in `app/src/main/proto/README.md`, currently `ca2cb1a8`

The daemon's wire format comes from the firmware's own copy of those protos, so a
mismatched pair shows up as a field that quietly stops decoding rather than as an error.

## Where the work is going

Done: 1.0.1 audit fixes, 1.1.0 battery telemetry, 1.1.1 battery level while charging,
1.2.0 the mesh link and the Mesh tab, 1.2.1 phone sensor telemetry, 1.2.2 layout fixes.

Open, roughly in order:

1. **Multiplexing proxy**, 1.2.x. Meshdroid takes 4403, the daemon moves to a loopback port,
   and Meshdroid fans FromRadio out to every local client while serialising ToRadio into the
   single uplink. This is a second implementation of MeshLink; nothing above the interface
   changes. It fixes the one-client limit, and the observed symptom that motivates it is the
   link and the Meshtastic app trading the slot back and forth every few seconds. Two things
   to fold in then: log the clean close (a null read, which is what displacement looks like
   and currently logs nothing), and do not reset the reconnect backoff for a session that
   never saw a packet, since resetting makes a slot fight tighter.
2. **BBS**, 1.2.x. Written against MeshLink, never against protobuf types. Built-in modules
   plus user-imported apps through the file picker, with a per-app toggle. LuaJ is the
   candidate for user apps: pure Java, sandboxable by trimming the globals table, with an
   instruction-count hook and a wall-clock timeout. Reply throttling matters from day one;
   a BBS that answers every packet will saturate a duty-cycled channel. Supply Drop is a
   design reference only, since Apache 2.0 with the Commons Clause cannot be bundled into a
   GPL-3 app.
3. **MeshCore**, 2.0.0. A hard mode switch, one protocol at a time. `l5yth/meshcore-linux`
   is a portduino-targeted fork, MIT, which is compatible in the direction needed. Two
   things decide feasibility: whether its Linux target drives the radio through libusb and
   CH341 rather than spidev and gpiod, and whether the companion radio exposes a TCP
   transport for the MeshCore app to connect to. Structurally the service is already close,
   since it reduces to "launch this binary with these arguments and this fd socket"; it
   becomes a profile with its own binary, config, log patterns, port and prefs directory.

Carried notes: battery temperature is universal on Android but reads the battery, several
degrees above ambient and more under load, so it should not be published as `temperature`.
Phone current draw through `BATTERY_PROPERTY_CURRENT_NOW` would fit `PowerMetrics`
`ch1_current` and would show the node's real consumption with the dongle attached, which is
of specific interest to this project.

## Style rules for docs and comments (from the owner)

No em dashes. No "not X, so Y" constructions or stock agent phrasing. Say Fedora, not
Nobara, in anything public. Comments must describe what the code does; do not change code
when asked to fix comments. Be thorough without over-explaining.

The owner reviews and merges every commit, and the README carries an AI disclosure. Give
whole files or targeted diffs, and say plainly what has not been compiled or tested.
