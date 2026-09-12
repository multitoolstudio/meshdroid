# meshcored

The MeshCore companion daemon Meshdroid runs alongside meshtasticd, built from
meshcore-dev/MeshCore at tag `companion-v1.17.1` (commit `d9296435`).

Read `docs/meshcore-reference.md` for what MeshCore does and what its protocol
looks like. This file is about building it.

## State

Shipping in 2.0.0. Confirmed on hardware with a NullHop MeshToad V3 on a
Pixel 9 Pro: advert transmit, direct messages in both directions, ACKs and path
discovery, with MeshCore Open attached over TCP on port 5000.

`DaemonService` launches it as a profile, passes `--fsdir`, and parses its log
lines. `Profile.kt` holds everything that differs between the two daemons.

## Building

```
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/30.0.16138531
./build-deps-android.sh
./build-meshcored-android.sh /path/to/meshcore-firmware
```

That installs `app/src/main/jniLibs/arm64-v8a/libmeshcored.so` and the NDK's
`libc++_shared.so` beside it. The executable is packaged as a `lib*.so` because
`nativeLibraryDir` is the only place an unrooted app may `exec()` from, the same
trick meshtasticd uses.

Use the same NDK for both daemons. Mixing versions produces linker errors weeks
later with no obvious cause.

A desktop environment used to live in `platformio-android.ini` and build the
same sources against the host toolchain, for testing the CH341 HAL without an
APK in the way. It did its job: it is how the missing `/identity` directory was
found in one run. It was removed for 2.0.0, along with
`build-meshcored-linux.sh`, because the daemon now runs inside the app and a
second build path is a second place for the two to diverge. The git history has
it if it is ever wanted again.

## Layout

```
build-deps-android.sh          libusb, libuv, argp-standalone, libi2c for arm64
build-meshcored-android.sh     the build
make-patch-0001.py             regenerates patch 0001 against a new MeshCore pin
platformio-android.ini         the env and the hardware defines
extra_scripts/                 link-stage flags
patches/                       what MeshCore needs that upstream does not have
variant/                       everything that is ours, copied into the checkout
docs/                          MeshCore protocol and radio reference
```

Everything in `variant/` is copied to `variants/meshdroid_ch341/` in the MeshCore
checkout by the build script, with a glob rather than a list of filenames. The
list had drifted: `AndroidCompat.h` and `MeshdroidFS.h` were missing from it, and
the Android build only worked because a desktop build had left copies behind.
Nothing in `variant/` is patched into upstream, so it survives a version bump
untouched:

| File | What it is |
| ---- | ---------- |
| `target.h`, `target.cpp` | the board, clock and radio MeshCore expects a variant to declare |
| `USBHal.h` | the CH341 RadioLib HAL, vendored from meshtastic/firmware (both GPL-3) |
| `MeshdroidFS.h` | adapts portduino's `fs::FS` to MeshCore's `FILESYSTEM` |
| `SocketSerialInterface.h` | the companion protocol over a POSIX socket |
| `AndroidCompat.h` | declares `ltoa` and friends, which portduino defines but nothing declares |

## Patches

`patches/0001-portduino-filesystem.patch` has eight hunks and is generated, not
hand-written. Run `./make-patch-0001.py /path/to/meshcore-firmware` after a
MeshCore version bump rather than fixing rejects by hand; it anchors on exact
strings and refuses rather than corrupting a file if upstream has moved.

It adds a portduino branch to the filesystem selection in `IdentityStore.h`, the
`DataStore` declaration and `setup()` bring-up in `main.cpp`,
`formatFileSystem()` in `DataStore.cpp`, the `/identity` directory creation, the
declaration and registration of the TCP interface, and a marker string in
`DataStore.cpp` that the build checks for.

`patches/0002-libch341-android-fd.patch` is Meshdroid's existing meshtasticd
patch: the daemon receives a usbfs descriptor over an abstract-namespace socket
and hands it to `libusb_wrap_sys_device()`, because an unrooted app cannot
enumerate USB itself. Regenerate it by diffing a pristine extraction of the
pinned libch341 commit against a patched one, so the context is exact.

The build script applies patches with `patch -F0`. Fuzz is an error on purpose:
a patch that lands at a shifted offset produces a binary that builds, links and
does not work, and that has already happened once here.

## Verification

The build script greps the finished binary for strings that only exist if the
patches applied, and refuses to install if any is missing. Extend that list as
patches land. It has caught two real problems already, both of them cases where
the build succeeded and the binary was wrong.

| String | Proves |
| ------ | ------ |
| `Using pre-opened CH341 fd` | patch 0002, the usbfs descriptor hand-off |
| `MESHDROID_PATCH_0001_R1` | patch 0001 reached the build |
| `companion: listening on` | patch 0001's `main.cpp` hunks, which are the only reason `SocketSerialInterface.h` is compiled |
| `Meshdroid MeshToad V3` | `variant/target.h`, so the variant was copied |

The marker exists because both of patch 0001's effects fail silently. A node
with no `/identity` directory receives perfectly and cannot sign an advert or
complete an ACK, which reads as a transmit fault. A node with no TCP interface
comes up and listens on nothing. Neither looks like a build problem. Bump the
`_R1` suffix in `make-patch-0001.py` whenever the patch changes, so a stale
patch is distinguishable from a missing one.

## The one setting that matters

`SX126X_DIO2_AS_RF_SWITCH=true`, in the `[meshdroid_ch341]` section.

DIO2 drives the E22P-915M30S front-end module's T/RCTRL pin, which selects the
transmit path. `CustomSX1262::std_init` calls `setDio2AsRfSwitch()` under that
`#ifdef`, and `SX126xReset.h` re-applies it after `Calibrate(0x7F)` under the
same one, because calibration clears it.

Without it the daemon sends its first advert, hits the first AGC reset, then
transmits nothing forever while still receiving normally and reporting correct
airtime. Established on hardware before any of this was written; see
`docs/openhop-radio-stack.md`.

## Known upstream gaps

Worth reporting to MeshCore.

`identity_store.begin()` is called under `#if defined(RP2040_PLATFORM)` only, so
on any platform with a real directory tree `/identity` is never created, every
identity write fails, and the node runs with no private key.

`ENABLE_ADVERT_ON_BOOT` is defined in `platformio.ini` and read nowhere. Grep
every `.cpp` and `.h` and there is no consumer. A `companion_radio` node
transmits only in response to `CMD_SEND_SELF_ADVERT` from a client, so a node
with nothing attached is silent by design, which looks exactly like a broken
radio.

## Remaining work

Sensor log noise. The simulated I2C bus answers every probe, so each boot logs
fifteen lines about sensors that do not exist. Dropping `helpers/sensors` from
`[meshdroid_ch341].build_src_filter` needs a `SensorManager` stub carrying
`querySensors()`, `node_lat` and `node_lon`, which `MyMesh.cpp` uses, and both
`target.cpp` and `target.h` refer to `EnvironmentSensorManager`.

A runtime bind address. `SocketSerialInterface::begin()` already takes one and
patch 0001 passes the compiled-in `TCP_BIND_ADDR`, so a `getenv` inside `begin()`
is all that stands between the app's LAN switch and MeshCore. The switch is
hidden under MeshCore in 2.0.0 rather than left to look as though it does
something.

A config editor. MeshCore's settings are a `NodePrefs` struct the client edits
over the companion protocol, so the Config tab has nothing to show and is hidden
under MeshCore in 2.0.0. Users set frequency and the rest from the MeshCore app
they are already running.
