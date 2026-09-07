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

All three are applied by `build-meshtasticd-android.sh` and are idempotent;
the script checks for a marker string before applying each one.

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

## The app

Kotlin, Jetpack Compose, Material 3. One module, no dependency injection.

| File | Role |
|---|---|
| `MainActivity.kt` | Finds the CH341 device, requests USB permission, handles the `USB_DEVICE_ATTACHED` launch intent, hosts the Compose UI. |
| `DaemonService.kt` | Foreground service that owns the daemon process: opens USB, holds a wake lock, starts the fd socket, launches the binary, streams its stdout, tears down cleanly. Kills orphaned daemons from a crashed earlier run and checks that port 4403 is free before starting. |
| `UsbFdServer.kt` | The abstract Unix socket that hands the USB descriptor to the daemon. |
| `DaemonState.kt` | Shared state: node status, a capped log buffer, values parsed from the log (node ID, firmware, serial, region unset). `Settings` holds the persisted switches. |
| `NodeFiles.kt` | Paths, `config.yaml` read and write, the `Lora.Region` key, presets, backup and restore zips, log export, identity reset. |
| `ui/App.kt` | Theme and the three-tab scaffold. |
| `ui/NodeScreen.kt`, `ui/ConfigScreen.kt`, `ui/LogsScreen.kt` | The tabs. |

Process model: the daemon is a separate OS process, so a crash inside it
cannot take the app down, and the app can restart it. The service runs in the
app's process. The daemon's stdout is read on a dedicated thread and appended
to `DaemonState.log`; the Logs tab observes that flow.

## File layout on the phone

```
/data/data/studio.multitool.meshdroid/
  files/config.yaml              meshtasticd -c        (equivalent of /etc/meshtasticd/config.yaml)
  files/meshtasticd/             meshtasticd -d        (equivalent of /var/lib/meshtasticd)
    prefs/config.proto           radio and security config, including the keypair
    prefs/nodes.proto            node database
    prefs/channels.proto, device.proto, module.proto, ...
  shared_prefs/meshdroid.xml     app switches: lan, verbose, autostart
```

The daemon is launched as
`libmeshtasticd.so -c files/config.yaml -d files/meshtasticd -p 4403 [-v]`
with `PINEDIO_USB_FD_SOCKET`, `MESHTASTICD_BIND_ADDR`, `LD_LIBRARY_PATH` and
`HOME` in its environment.
