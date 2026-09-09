# Architecture

## The problem in one paragraph

A CH341-based dongle such as the MeshToad is not a serial Meshtastic node. It
is a USB-to-SPI bridge in front of an SX1262 transceiver; the Meshtastic
firmware logic runs on whatever is driving it. On Linux that is `meshtasticd`,
which talks to the CH341 from user space through libusb. Meshdroid makes the
phone that host. Three things stand in the way on an unrooted Android device,
and the design is the answer to each.

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
`jniLibs/`. The daemon is therefore packaged as `libmeshtasticd.so` with
`useLegacyPackaging` so it is extracted to disk, and the NDK's
`libc++_shared.so` sits beside it.

Everything else is ordinary: the daemon exposes the standard Meshtastic TCP
API on port 4403, and the official app connects to `127.0.0.1`.

## Firmware build

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

## The app

Kotlin, Jetpack Compose, Material 3. One module, no dependency injection.

| File | Role |
|---|---|
| `MainActivity.kt` | Finds the CH341 device, requests USB permission, handles the `USB_DEVICE_ATTACHED` launch intent, hosts the Compose UI. Start, Stop, Restart and Close are requests to the service. |
| `DaemonService.kt` | Foreground service that owns the daemon process: opens USB, holds a wake lock, starts the fd socket, launches the binary, streams its stdout, tears down cleanly. Signals orphaned daemons from a crashed earlier run and waits for port 4403 before starting. Every transition runs on its own control thread. |
| `UsbFdServer.kt` | The abstract Unix socket that hands the USB descriptor to the daemon, after checking that the peer is this app. |
| `DaemonState.kt` | Shared state: node status, a coalesced log buffer, values parsed from the log (node ID, firmware, serial, bind address, region unset). `Settings` holds the persisted switches. |
| `mesh/MeshLink.kt` | The transport interface and its domain types. Nothing above it sees a protobuf. |
| `mesh/MeshtasticTcpLink.kt` | MeshLink over meshtasticd's TCP API: framing, the want_config handshake, heartbeats, reconnect. |
| `mesh/StreamFrames.kt` | The 0x94 0xc3 length-prefixed framing, with resync. |
| `mesh/MeshLinkHolder.kt` | Process-wide owner of the link and a capped event history for the Mesh tab. |
| `PhoneSensors.kt` | Runtime probe of the phone's environment sensors, and one-shot reads. |
| `EnvironmentReporter.kt` | Publishes pressure and light over the mesh link on an interval. |
| `BatteryReporter.kt` | Publishes the phone's battery percentage, voltage and charging state to the file the daemon reads. |
| `NodeFiles.kt` | Paths, `config.yaml` read and write, the `Lora.Region` key, presets, backup and restore zips, log export, identity reset. All of it blocking, all of it called from a background dispatcher. |
| `ui/App.kt` | Theme and the three-tab scaffold. |
| `ui/NodeScreen.kt`, `ui/ConfigScreen.kt`, `ui/LogsScreen.kt` | The tabs. |

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

The interface exists because at least two other implementations are planned. A MeshCore
link speaks a different wire format entirely. A multiplexing proxy would hold the daemon's
single client slot and fan the stream out to several local clients, so that the phone app
and this link could both be connected; that is the piece that would let the BBS run
without displacing the Meshtastic app. Neither should be visible above `MeshLink`, so the
BBS and the phone sensor telemetry are written against the interface only.

Until the proxy exists, connecting takes the daemon's only client slot and the Meshtastic
app is disconnected for the duration. The Mesh tab says so, and the link is off by default.

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

## Lifecycle

Start, stop and restart are intents to `DaemonService`, which posts each one to a
single "daemon-control" thread. Serialising them there is what makes a restart
safe: the teardown finishes before the next start begins, instead of racing it on
a timer. `onStartCommand` itself only calls `startForeground` and returns, so none
of the blocking work (scanning `/proc`, waiting for the port, opening USB,
`ProcessBuilder.start`) happens on the main thread.

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
SIGKILL. Anything that replaces files under `prefs/`, meaning Restore and Reset
identity, waits for the status to reach `Stopped` first.

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
  files/battery                  phone battery state for firmware patch 0005
  shared_prefs/meshdroid.xml     app switches: lan, verbose, autostart, battery
```

The daemon is launched as
`libmeshtasticd.so -c files/config.yaml -d files/meshtasticd -p 4403 [-v]`
with `PINEDIO_USB_FD_SOCKET`, `MESHTASTICD_BIND_ADDR`, `LD_LIBRARY_PATH` and
`HOME` in its environment.
