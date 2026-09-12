# Architecture

## The problem in one paragraph

A CH341-based dongle such as the MeshToad is not a serial mesh node. It is a
USB-to-SPI bridge in front of an SX1262 transceiver; the mesh firmware logic
runs on whatever is driving it. On Linux that is `meshtasticd`, which talks to
the CH341 from user space through libusb. Meshdroid makes the phone that host.
Three things stand in the way on an unrooted Android device, and the design is
the answer to each.

Since 2.0.0 there are two such daemons, `meshtasticd` and `meshcored`, and all
three answers are shared between them. What differs is gathered in one file; see
[Profiles](#profiles).

## Three obstacles, three answers

**1. No `/dev/bus/usb`.** Apps cannot open USB device nodes directly. They can
ask the USB Host API for the device, and `UsbDeviceConnection` exposes the
underlying usbfs file descriptor. libusb supports being handed such a
descriptor: `libusb_set_option(LIBUSB_OPTION_NO_DEVICE_DISCOVERY)` followed by
`libusb_wrap_sys_device(fd)`. Patch 0001 adds that path to
`libch341-spi-userspace`, the library meshtasticd uses for CH341 access.

**2. Child processes inherit nothing.** Android's `ProcessBuilder` closes every
descriptor in the child except stdin, stdout and stderr, so the fd cannot
simply be inherited. `UsbFdServer` listens on an abstract-namespace Unix
socket; the daemon connects to it on startup and receives the descriptor as
`SCM_RIGHTS` ancillary data. The socket name travels in the
`PINEDIO_USB_FD_SOCKET` environment variable.

**3. Where can a binary run from?** Unrooted apps may only `exec()` files under
their `nativeLibraryDir`, which the package installer populates from
`jniLibs/`. Each daemon is therefore packaged as a `lib*.so`, `libmeshtasticd.so`
and `libmeshcored.so`, with `useLegacyPackaging` so they are extracted to disk,
and the NDK's `libc++_shared.so` sits beside them.

Everything else is ordinary: `meshtasticd` exposes the standard Meshtastic TCP
API on port 4403 and the official app connects to `127.0.0.1`; `meshcored`
exposes MeshCore's companion protocol on port 5000 and a MeshCore client
connects the same way.

## Profiles

One radio and one USB descriptor, so exactly one daemon runs at a time.
`Profile` is an enum with one entry per daemon, and it holds everything that
differs between them: the binary, the data directory, the client port, the
argument list, the environment, and which features apply.

| | `MESHTASTIC` | `MESHCORE` |
|---|---|---|
| binary | `libmeshtasticd.so` | `libmeshcored.so` |
| data directory | `files/meshtasticd` | `files/meshcore` |
| client port | 4403 | 5000 |
| argv | `-c config.yaml -d <dir> -p <port> [-v]` | `--fsdir <dir>` |
| `supportsProxy` | yes | no, the port is compiled in |
| `supportsMeshLink` | yes | no, different wire format |
| `supportsBbs` | yes | not yet |
| `hasConfigFile` | yes | no, settings live in a struct the client edits |
| `canRestrictBind` | yes, `MESHTASTICD_BIND_ADDR` | no, `TCP_BIND_ADDR` is compiled in |
| `supportsVerbose` | yes, `-v` | no, verbosity is a build flag |

Adding a third daemon should mean an entry there and log patterns in
`DaemonState`, not edits to `DaemonService`.

Two consequences are easy to miss. `DaemonService` captures the profile once at
the start of a session rather than reading it live, so a switch made while a
daemon is up cannot make the shutdown path look for the wrong binary; and
`killStaleDaemons` matches any profile's binary, because a leftover from the
other profile holds the same radio.

The UI hides the tabs a profile does not support, but hiding a tab only keeps a
user off a screen. `BbsHolder` and `MeshLinkHolder` are process-wide and run
whether or not a screen is open, so they check the profile themselves. Without
that, an operator who had the BBS on under Meshtastic and switched to MeshCore
would leave a reconnect loop running against a port nothing is listening on.

## Firmware build: meshtasticd

`meshtasticd` is the `native` (portduino) target of `meshtastic/firmware`.
Building it for Android means cross-compiling with the NDK against bionic
instead of glibc.

- `firmware/platformio-android.ini` defines `[env:android-arm64]`. It extends
  the upstream `portduino_base` env and uses platform-native's `buildroot`
  board, whose builder reads the compiler, linker and archiver from `TARGET_*`
  environment variables. `build-meshtasticd-android.sh` points those at the
  NDK. The env is headless: no screen, input broker, GPS, I2C scanning, web
  server or Bluetooth, and `PORTDUINO_LINUX_HARDWARE` is not defined, which
  keeps libgpiod and Linux I2C device code out of the build.
- `firmware/variant.h` replaces the native variant header, which assumes a
  Raspberry Pi hat with an I2C RTC.
- `firmware/android_compat.h` is force-included into every C++ file and
  supplies `std::char_traits<unsigned char>`, which libc++ 19 no longer
  provides and `MQTT.h` needs.
- `firmware/extra_scripts/android_link_flags.py` runs at link time: PIE, an
  `$ORIGIN` rpath, 16 KB page alignment, `libi2c` after the framework archive,
  and removal of libraries that do not exist on Android.
- `firmware/build-deps-android.sh` cross-builds the libraries bionic lacks
  (yaml-cpp, jsoncpp, libuv, libusb, argp, libi2c) as static archives.

## The patches

All six are applied by `build-meshtasticd-android.sh` and are idempotent; the
script checks for a marker string before applying each one, and fails if a
marker is still missing afterwards. 0004 appends to the function 0003 adds, so
they are applied in order.

### 0001: libch341-spi-userspace, receive the USB fd from the app

Target: `.pio/libdeps/android-arm64/<libch341>/libpinedio-usb.c` in the
firmware checkout.

In `pinedio_init()`, before `libusb_init()`: if `PINEDIO_USB_FD` is set, use
that descriptor; otherwise if `PINEDIO_USB_FD_SOCKET` is set, connect to the
named abstract socket and receive one descriptor over `SCM_RIGHTS`. With a
descriptor in hand it sets `LIBUSB_OPTION_NO_DEVICE_DISCOVERY`, wraps the
descriptor with `libusb_wrap_sys_device()`, claims interface 0, reads the
serial and product strings, and skips the device enumeration loop. Without
either variable the library behaves exactly as upstream.

The firmware derives the node's MAC address, and from it the node number,
from the CH341 serial string, so no Bluetooth adapter is needed.

### 0002: framework WiFi shim, API bind address

Target: `~/.platformio/packages/framework-portduino/libraries/WiFi/src/WiFiServer.cpp`.
This is the PlatformIO package cache, shared by every env built on the
machine, so the change is visible to other native builds on the same host. It
is inert unless the environment variable is set.

Living in the package cache has two consequences worth knowing about. A
`pio pkg update` reverts it, and a clean machine that skips the patch produces a
daemon that ignores `MESHTASTICD_BIND_ADDR` and binds every interface however the
app's LAN switch is set. Three things guard against shipping that binary: the
build script treats a missing patch as fatal rather than a warning, it greps the
finished binary for the `API server binding` string before installing it into the
app, and `DaemonService` warns in the log if a running daemon never reports a bind
address.

`WiFiServer::begin()` binds to `INADDR_ANY`. The patch reads
`MESHTASTICD_BIND_ADDR`; if it holds a valid IPv4 address the server binds to
that instead. It then logs `API server binding <addr>:<port>` to stderr.
`DaemonService` sets the variable to `127.0.0.1` or `0.0.0.0` from the
*Allow LAN access* switch.

### 0003: firmware, config.yaml `Lora.Region` on first boot

Target: `src/main.cpp`, `src/platform/portduino/PortduinoGlue.{cpp,h}` in the
firmware checkout.

Firmware 2.8 will not mint the node's identity keys until a LoRa region is
set; a fresh node also never writes `config.proto` or `nodes.proto` until
then. The patch adds a `Lora: Region:` key to `config.yaml`. At the end of
`setup()`, if the stored config's region is `UNSET` and the YAML names a valid
region, `portduinoApplyInitialRegion()` sets the region, enables transmit,
runs the same key generation path the admin module uses, re-initialises the
radio and persists the result. If the node already has a region the key is
ignored, so later changes made from the Meshtastic app are never overridden.

Applying a region also sets `tx_enabled`. That only ever runs on a node whose
region is `UNSET`, which is a node that has never transmitted, so no setting a
user chose is overridden by it.

### 0004: firmware, clean shutdown on SIGTERM

Target: `src/main.cpp`, `src/platform/portduino/PortduinoGlue.cpp` in the
firmware checkout.

Portduino installs no signal handlers, so the default action for SIGTERM ends
the process wherever it happens to be, including part way through writing
`config.proto` or `nodes.proto`. The patch adds a handler that records the
signal, and a `portduinoCheckShutdown()` call at the top of `loop()` that saves
the node database and exits when it sees one. The handler is installed on the
first call, so nothing in `setup()` has to change.

SIGINT is deliberately left alone. `WiFiServer::write()` raises it when the
unimplemented `Print` path is reached, and that has to stay a visible crash.

`DaemonService` sends SIGTERM and waits before falling back to SIGKILL, which is
what `Process.destroy()` does on Android. Without this patch that wait accomplishes
nothing: an unhandled SIGTERM is as abrupt as a kill.

### 0005: firmware, battery level from a host file

Target: `src/Power.cpp` in the firmware checkout.

`Power.cpp` already abstracts the battery behind `HasBatteryLevel`, and
`Power::setup()` picks an implementation from a chain of probes. The patch adds a
portduino-only implementation that reads a file named in `MESHTASTICD_BATTERY_FILE`,
and puts it at the head of that chain. With no such variable the chain is unchanged.

The file holds one line of key=value pairs, written by `BatteryReporter` in the app:

```
pct=87 mv=3980 charging=1 powered=1 ts=1757390000
```

Two details carry weight. The `ts` field is a freshness check: anything older than
three minutes reads as no battery, so a stopped or crashed app degrades to the USB
powered state this build reported before rather than leaving a frozen percentage on
the mesh. And a zero or out-of-range voltage is replaced with a mid-charge value,
because `readPowerStatus()` compares the voltage against the OCV floor and triggers
a low battery deep sleep after ten readings below it. A percentage-only source that
passed zero through would shut the daemon down about a minute after the phone came
off the charger.

### 0006: firmware, keep the battery level while charging

Target: `src/modules/Telemetry/DeviceTelemetry.cpp` in the firmware checkout.

Upstream replaces the battery level with `MAGIC_USB_BATTERY_LEVEL`, the 101 that
clients display as PWR, whenever the node reports itself as charging. That is right
for a board whose battery is a backup for its USB supply, and wrong for a phone,
where the charge level is the only interesting number and stays interesting while
it charges. The patch keeps the magic value for nodes with no battery at all and
reports the level otherwise, on portduino only.

The trade is that other operators no longer see a plug icon for this node. They see
a percentage, which happens to be climbing.

## Firmware build: meshcored

`meshcored` is MeshCore's `companion_radio` example built for portduino, from a
variant that lives in this repository rather than upstream. Everything is under
`firmware/meshcore/`; its `README.md` covers the build commands and the patch
workflow.

- `firmware/meshcore/platformio-android.ini` defines `[env:android-arm64]` on the
  same platform-native archive `meshtasticd` uses, deliberately: one toolchain for
  both daemons means a portduino problem found in one is the same problem in the
  other. Its `[meshdroid_ch341]` section holds the pins, the radio class and the
  radio parameters.
- `firmware/meshcore/variant/` is copied into the MeshCore checkout at build time
  and is never patched into upstream, so it survives a version bump. It holds
  `target.h` and `target.cpp` (board, clock, radio), `USBHal.h` (the CH341
  RadioLib HAL, vendored from meshtastic/firmware), `MeshdroidFS.h` (adapts
  portduino's `fs::FS` to MeshCore's `FILESYSTEM`), `SocketSerialInterface.h`
  (the companion protocol over a socket) and `AndroidCompat.h`.
- `firmware/meshcore/patches/0001-portduino-filesystem.patch` is generated by
  `make-patch-0001.py`, not hand-written. After a MeshCore bump, re-run the
  generator rather than fixing rejects.
- `firmware/meshcore/patches/0002-libch341-android-fd.patch` is the same patch as
  meshtasticd's 0001, applied to the same library.

Three settings in that env are worth knowing, because each of them was expensive
to establish and none is visible from the code.

`SX126X_DIO2_AS_RF_SWITCH=true` is the one the release rests on. DIO2 drives the
E22P front-end module's T/RCTRL pin, which selects the transmit path, and
`SX126xReset.h` re-applies it after calibration only inside that `#ifdef`.
Without it the daemon sends its first advert, hits the first AGC reset, then
transmits nothing forever while still receiving normally and reporting correct
airtime.

`LORA_TX_POWER=10` is a PA drive level, not a power reduction. The E22P's power
amplifier needs roughly 10 to 13 dBm of drive to reach its rated 30 dBm, and more
overdrives it. The board radiates about a watt either way, and Meshtastic's
preset caps it at 10 for the same reason.

The radio parameters are MeshCore's United States and Canada preset: 910.525 MHz,
62.5 kHz, spreading factor 7, coding rate 4/5. Frequency, bandwidth and spreading
factor must match the rest of the mesh exactly, with no degraded mode. Coding rate
is read from each packet's header and need not match.

### What patch 0001 does

Upstream MeshCore selects its filesystem with `#if` chains covering ESP32, RP2040,
NRF52 and STM32, each ending in an `#error` rather than a default, so a portduino
build does not compile until a branch is added. The patch adds one in
`IdentityStore.h`, declares the `DataStore` and brings it up in `setup()` in
`main.cpp`, implements `formatFileSystem()` by deleting the data directory's
contents, and registers the TCP companion interface.

It also fixes an upstream gap: `identity_store.begin()` is called under
`#if defined(RP2040_PLATFORM)` only, so on any platform with a real directory tree
`/identity` is never created, every identity write fails, and the node runs with
no private key. Such a node receives perfectly and cannot sign an advert or
complete an ACK, which reads as a transmit fault rather than a storage one.

The last hunk adds a marker string, `MESHDROID_PATCH_0001_R1`, which the build
script greps out of the finished binary before installing it. Both of the patch's
effects fail silently, so a binary that built cleanly without it is the failure
mode worth guarding against.

### The companion socket

`SocketSerialInterface` implements MeshCore's `BaseSerialInterface` over a TCP
socket, byte for byte the same receive state machine as the Arduino serial
version, with `>` and `<` framing and a little-endian length. Port 5000 is what
stock MeshCore clients expect of a WiFi companion, so an unmodified app finds the
node with no special casing, the way 4403 works for `meshtasticd`.

One client at a time, and a new connection evicts the old one, which matches both
the firmware and openHop's server. The socket is non-blocking, so a client that
stops reading fills the send buffer; writes wait on `poll` for it to become
writable and drop a client that never does, rather than spinning the daemon's only
thread.

## The app

Kotlin, Jetpack Compose, Material 3. One module, no dependency injection.

| File | Role |
|---|---|
| `MainActivity.kt` | Finds the CH341 device, requests USB permission, handles the `USB_DEVICE_ATTACHED` launch intent, hosts the Compose UI. Start, Stop, Restart and Close are requests to the service. |
| `Profile.kt` | The two daemons and everything that differs between them: binary, data directory, ports, argv, environment, and which features apply. |
| `DaemonService.kt` | Foreground service that owns the daemon process: opens USB, holds a wake lock, starts the fd socket, launches the profile's binary, streams its stdout, tears down cleanly. Signals orphaned daemons of either profile from a crashed earlier run and waits for the port before starting. Every transition runs on its own control thread. |
| `UsbFdServer.kt` | The abstract Unix socket that hands the USB descriptor to the daemon, after checking that the peer is this app. |
| `DaemonState.kt` | Shared state: node status, a coalesced log buffer, values parsed from the log (node ID, firmware, serial, bind address, region unset). The parser splits three ways: the CH341 lines both daemons share, then Meshtastic's patterns or MeshCore's. `Settings` holds the persisted switches. |
| `mesh/MeshLink.kt` | The transport interface and its domain types. Nothing above it sees a protobuf. |
| `mesh/MeshtasticTcpLink.kt` | MeshLink over meshtasticd's TCP API: framing, the want_config handshake, heartbeats, reconnect. |
| `mesh/StreamFrames.kt` | The 0x94 0xc3 length-prefixed framing, with resync. |
| `bbs/BbsEngine.kt` | Dispatches commands to modules, rate limits and spaces the replies. |
| `bbs/BbsModule.kt` | The module interface and the parsed request handed to one. |
| `bbs/BuiltinModules.kt` | help, about, ping, board, mail. |
| `bbs/lua/LuaSandbox.kt` | A Lua interpreter with the dangerous parts left out, and the instruction and time limits. |
| `bbs/lua/LuaModule.kt` | Wraps one script as a BbsModule, and the `mesh` host table. |
| `bbs/lua/LuaModuleLoader.kt` | Import, enable, delete and load the scripts in files/bbs/apps. |
| `bbs/Sanitize.kt` | Cleans text arriving from the mesh, and normalises mail addresses. |
| `bbs/BbsStore.kt` | Bulletins and mail in SQLite. |
| `bbs/BbsHolder.kt` | Process-wide owner; follows the node status and the setting. |
| `mesh/MeshProxy.kt` | Shares the daemon's single API connection between several clients. |
| `mesh/MeshLinkHolder.kt` | Process-wide owner of the link and a capped event history for the Mesh tab. |
| `PhoneSensors.kt` | Runtime probe of the phone's environment sensors, and one-shot reads. |
| `EnvironmentReporter.kt` | Publishes pressure and light over the mesh link on an interval. |
| `BatteryReporter.kt` | Publishes the phone's battery percentage, voltage and charging state to the file the daemon reads. |
| `NodeFiles.kt` | Paths, `config.yaml` read and write, the `Lora.Region` key, presets, per-profile backup and restore zips, log export, identity reset. All of it blocking, all of it called from a background dispatcher. |
| `ui/App.kt` | Theme and the tab scaffold, filtered by what the active profile supports. |
| `ui/NodeScreen.kt`, `ui/ConfigScreen.kt`, `ui/MeshScreen.kt`, `ui/BbsScreen.kt`, `ui/LogsScreen.kt` | The tabs. |

Process model: the daemon is a separate OS process, so a crash inside it
cannot take the app down, and the app can restart it. The service runs in the
app's process. The daemon's stdout is read on a dedicated thread and appended
to `DaemonState.log`; the Logs tab observes that flow.

## The mesh link

`MeshLink` is an interface over a connection to the node, expressed in text messages and
node sightings rather than in protobuf types. `MeshtasticTcpLink` implements it against
meshtasticd's TCP API, which is the same interface the Meshtastic app uses: connect to
127.0.0.1:4403, send a `want_config_id` nonce, read the config dump until
`config_complete_id` returns that nonce, then stay on the stream with a heartbeat every
five minutes.

The interface exists because more than one implementation was expected. The multiplexing
proxy arrived first: it holds the daemon's single client slot and fans the stream out to
several local clients, so the phone app and this link can both be connected, which is what
lets the BBS run without displacing the Meshtastic app. A MeshCore link is the other one,
and it does not exist yet. 2.0.0 runs MeshCore as a second daemon that clients attach to
directly, which is a different thing: the app does not speak MeshCore's companion protocol
at all, and the Mesh and BBS tabs are hidden under that profile for exactly that reason.
When a `MeshCoreLink` is written, nothing above `MeshLink` should have to change, which is
why the BBS and the phone sensor telemetry are written against the interface only.

`MeshProxy` is that piece, and it is on by default. The daemon listens on loopback 4404,
Meshdroid owns 4403 and holds the daemon's one connection, and clients attach to Meshdroid.
MeshLink needs no special case for it: it connects to 4403 like any other client.

Routing is the only part with judgement in it. A client opens with a `want_config_id` nonce
and expects the config dump back, so those frames go to the client that asked, in the order
clients asked, ending on the matching `config_complete_id`. Mesh packets, queue status and
notifications are broadcast, since they belong to no handshake. A `disconnect` from a client
is handled locally and never forwarded, because the daemon would close the upstream
connection and take every other client with it.

Turning the proxy off hands 4403 back to the daemon and restores the one-client behaviour,
which is the fallback if the proxy ever misbehaves in the path of the Meshtastic app.

Protobuf definitions are vendored under `app/src/main/proto` at a pinned commit and
compiled by the Gradle protobuf plugin with the javalite runtime. The pin matters for the
same reason the firmware pin does: the wire format the daemon speaks comes from the
firmware's copy of those files, so the two are bumped together.

### Two routes for telemetry

Battery goes through firmware patch 0005 and a file, because the app had no way to talk to
the node when it was written. Environment telemetry goes over the mesh link instead: the app
builds the same Telemetry packet a sensor-equipped node would send. The second route costs
nothing on a firmware bump and lets the published field set be a runtime decision, which
suits hardware that varies as much as phone sensors do. The trade is that it only works
while the link holds the daemon's client slot.

The file route carries to MeshCore unchanged, because a file needs no protocol.
`BatteryReporter` writes one line of key=value pairs and both daemons read it, with the same
three-minute staleness rule, so an app that stops writing degrades to reporting no battery
rather than leaving a frozen figure on the mesh.

What does not carry is the shape of the reading. Meshtastic publishes a percentage and uses
the voltage only for its low-battery check, where patch 0005 substitutes a mid-charge value
when the host supplies none. MeshCore has only `getBattMilliVolts`, so a phone that does not
report a voltage the app recognises produces no reading at all rather than a degraded one.
That is the 2.0.0 known issue: the plumbing is in place and the voltage often is not. The
fix is on the app side, in what `BatteryReporter` accepts as a voltage.

## The BBS

`BbsEngine` consumes `MeshEvent.Text` from its own `MeshLink` and replies through the same
interface, so no module sees a protobuf and the command set carries to another protocol
unchanged. Modules declare a keyword and return text; the engine handles selection, the
rate limit and the sending.

Three deliberate limits. One reply per sender per cooldown, so a looping node cannot hold
the channel. Four seconds between transmissions, because airtime is shared. And replies
split on word boundaries to at most three packets, which is why listings show a count and
the first few entries rather than everything.

The engine holds its own connection rather than sharing the Mesh tab's, so a BBS does not
depend on a screen being open. That is one more proxy client, which costs nothing while
sharing is on and takes the node's only slot when it is off.

### Imported modules

`bbs/lua` holds the sandbox, the module wrapper and the loader. The environment is
constructed from BaseLib, TableLib, StringLib and MathLib, with DebugLib loaded only long
enough to install an instruction hook and then removed from the globals so a script cannot
lift its own limiter. `dofile`, `loadfile`, `load`, `print` and `require` are cleared after
the libraries are in place.

The design decision worth keeping: a module returns text and never sends anything. Every
limit that protects the channel lives in the engine, so an imported script cannot transmit
around them however it is written.

### Knowing a reply arrived

Direct messages already went out with `want_ack` set, and the mesh answers them with a
Routing packet whose `request_id` is the id of the message being acknowledged. The link
decodes those into `MeshEvent.Ack`, and `sendText` returns the id it used so a caller can
match them up.

The BBS uses this for mail: a message is marked collected only once the reply carrying it
has been confirmed, so a reply lost in the air leaves the mail waiting rather than losing
it. Acks land in a map filled by the event loop rather than being awaited on the flow,
because a reply can be acknowledged before a collector started at send time would have
subscribed.

### Trusting what arrives

Commands come from strangers. Nothing interprets them, and every query is parameterised, so
the risks are narrower than they first look: text that renders as something other than what
it is, and storage filled by a flooding node. `Sanitize` strips control characters, terminal
escapes, bidirectional overrides and zero-width characters on the way in, and the store caps
bulletins per sender per hour, bulletins in total and mail per sender.

Admin commands need something stronger, because a packet's `from` field is whatever the
sender put there. Membership of the admins table is checked, and so is `pki_encrypted`,
which the firmware sets only after decrypting a message with this node's private key. That
is a signature in everything but name, and it is why the check is on by default. The switch
that relaxes it exists for meshes running without keys, and the UI states the consequence.

## Lifecycle

Start, stop and restart are intents to `DaemonService`, which posts each one to a
single "daemon-control" thread. Serialising them there is what makes a restart
safe: the teardown finishes before the next start begins, instead of racing it on
a timer. `onStartCommand` itself only promotes the service to the foreground and
returns, so none of the blocking work (scanning `/proc`, waiting for the port,
opening USB, `ProcessBuilder.start`) happens on the main thread.

The promotion is conditional, and that is not a defensive nicety. A
`connectedDevice` foreground service needs the USB permission the app holds only
while the device is attached, so promoting on a stop request after the dongle has
gone throws `SecurityException` and takes the app with it. A stop arrives through
`startService` rather than `startForegroundService`, so there is no deadline to
meet on that path and none is attempted; a real start promotes, once per session,
and reports a refusal rather than crashing.

Two ownership details matter:

- The `ParcelFileDescriptor` returned by `ParcelFileDescriptor.fromFd` owns the
  duplicate of the usbfs descriptor that the daemon receives. It is held in a
  field for the life of the session and closed after the fd server stops. If it
  became unreachable its finalizer would close the descriptor underneath a
  running daemon, and the next CH341 recovery attempt would get a dead fd.
- `UsbFdServer` checks `SO_PEERCRED` on every connection and only sends the
  descriptor to this app's own UID. The abstract socket namespace is not scoped
  to an app, so the name alone is not a secret.

Stopping sends SIGTERM, waits, then falls back to `Process.destroy()`, which is a
SIGKILL. Anything that replaces the node's files, meaning Restore, Reset identity
and a protocol switch, waits for the status to reach `Stopped` first. A switch
waits because the daemon holds the radio and the USB descriptor, so asking it to
stop is not the same as it having stopped.

The node is not restarted automatically after a crash or a reboot. The phone is
the radio's host and a start needs a USB device the user has plugged in, so
`onStartCommand` returns `START_NOT_STICKY`.

## File layout on the phone

```
/data/data/studio.multitool.meshdroid/
  files/config.yaml              meshtasticd -c        (equivalent of /etc/meshtasticd/config.yaml)
  files/meshtasticd/             meshtasticd -d        (equivalent of /var/lib/meshtasticd)
    prefs/config.proto           radio and security config, including the keypair
    prefs/nodes.proto            node database
    prefs/channels.proto, device.proto, module.proto, ...
  files/meshcore/                meshcored --fsdir
    identity/_main.id            the node's keypair
    new_prefs, contacts3, channels2, bl/
  files/battery                  phone battery state, read by both daemons
  shared_prefs/meshdroid.xml     app switches: profile, lan, verbose, autostart, battery
```

`meshtasticd` is launched as
`libmeshtasticd.so -c files/config.yaml -d files/meshtasticd -p 4403 [-v]`
with `PINEDIO_USB_FD_SOCKET`, `MESHTASTICD_BIND_ADDR`, `MESHTASTICD_BATTERY_FILE`,
`LD_LIBRARY_PATH` and `HOME` in its environment.

`meshcored` is launched as `libmeshcored.so --fsdir files/meshcore` with
`PINEDIO_USB_FD_SOCKET`, `MESHCORE_BATTERY_FILE`, `LD_LIBRARY_PATH` and `HOME`.
It takes no port: portduino parses `--fsdir` with argp before `setup()` runs, so
the data directory is an argument rather than an environment variable, and the
listening port is compiled in.
