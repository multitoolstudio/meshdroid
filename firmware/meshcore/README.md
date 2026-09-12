# meshcored

The MeshCore companion daemon Meshdroid runs alongside meshtasticd, built from
meshcore-dev/MeshCore at tag `companion-v1.17.1` (commit `d9296435`).

Read `docs/meshcore-reference.md` for what MeshCore does and what its protocol
looks like. This file is about building it.

## State

Working and confirmed on hardware: advert transmit, direct messages in both
directions, ACKs and path discovery, with MeshCore Open attached over TCP to the
desktop build driving a NullHop MeshToad V3.

Not yet wired into the app. `DaemonService` does not launch it and nothing
passes `--fsdir`, so the daemon only runs by hand at present.

## Building

Android, which is what ships:

```
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/30.0.16138531
./build-deps-android.sh
./build-meshcored-android.sh /path/to/meshcore-firmware
```

That installs `app/src/main/jniLibs/arm64-v8a/libmeshcored.so` and the NDK's
`libc++_shared.so` beside it. The executable is packaged as a `lib*.so` because
`nativeLibraryDir` is the only place an unrooted app may `exec()` from, the same
trick meshtasticd uses.

Desktop, for testing against real hardware without an APK in the way:

```
sudo dnf install libusb1-devel libi2c-devel libuv-devel gcc-c++
./build-meshcored-linux.sh /path/to/meshcore-firmware
~/meshcore-firmware/.pio/build/linux-x86_64/program --fsdir ~/.meshcored-test
```

Both environments live in `platformio-android.ini` and share one
`[meshdroid_ch341]` section of hardware defines. That sharing is deliberate: a
desktop build that quietly differed from the shipping one would prove nothing.

The desktop build needs a udev rule for the CH341, since it opens the dongle
through libusb rather than being handed a descriptor:

```
SUBSYSTEM=="usb", ATTRS{idVendor}=="1a86", ATTRS{idProduct}=="5512", \
  MODE="0660", GROUP="plugdev", TAG+="uaccess"
```

## Layout

```
build-deps-android.sh          libusb, libuv, argp-standalone, libi2c for arm64
build-meshcored-android.sh     the shipping build
build-meshcored-linux.sh       the desktop test build
make-patch-0001.py             regenerates patch 0001 against a new MeshCore pin
platformio-android.ini         both environments and the shared defines
extra_scripts/                 link-stage flags for the Android env
patches/                       what MeshCore needs that upstream does not have
variant/                       everything that is ours, copied into the checkout
docs/                          MeshCore protocol and radio reference
```

`variant/` is copied to `variants/meshdroid_ch341/` in the MeshCore checkout by
both build scripts. Nothing in it is patched into upstream, so it survives a
version bump untouched:

| File | What it is |
| ---- | ---------- |
| `target.h`, `target.cpp` | the board, clock and radio MeshCore expects a variant to declare |
| `USBHal.h` | the CH341 RadioLib HAL, vendored from meshtastic/firmware (both GPL-3) |
| `MeshdroidFS.h` | adapts portduino's `fs::FS` to MeshCore's `FILESYSTEM` |
| `SocketSerialInterface.h` | the companion protocol over a POSIX socket |
| `AndroidCompat.h` | declares `ltoa` and friends, which portduino defines but nothing declares |

## Patches

`patches/0001-portduino-filesystem.patch` has seven hunks and is generated, not
hand-written. Run `./make-patch-0001.py /path/to/meshcore-firmware` after a
MeshCore version bump rather than fixing rejects by hand; it anchors on exact
strings and refuses rather than corrupting a file if upstream has moved.

It adds a portduino branch to the filesystem selection in `IdentityStore.h`, the
`DataStore` declaration and `setup()` bring-up in `main.cpp`,
`formatFileSystem()` in `DataStore.cpp`, the `/identity` directory creation, and
the declaration and registration of the TCP interface.

`patches/0002-libch341-android-fd.patch` is Meshdroid's existing meshtasticd
patch: the daemon receives a usbfs descriptor over an abstract-namespace socket
and hands it to `libusb_wrap_sys_device()`, because an unrooted app cannot
enumerate USB itself. Regenerate it by diffing a pristine extraction of the
pinned libch341 commit against a patched one, so the context is exact.

Both build scripts apply patches with `patch -F0`. Fuzz is an error on purpose:
a patch that lands at a shifted offset produces a binary that builds, links and
does not work, and that has already happened once here.

## The one setting that matters

`SX126X_DIO2_AS_RF_SWITCH=true`, in the shared section.

DIO2 drives the E22P-915M30S front-end module's T/RCTRL pin, which selects the
transmit path. `CustomSX1262::std_init` calls `setDio2AsRfSwitch()` under that
`#ifdef`, and `SX126xReset.h` re-applies it after `Calibrate(0x7F)` under the
same one, because calibration clears it.

Without it the daemon sends its first advert, hits the first AGC reset, then
transmits nothing forever while still receiving normally and reporting correct
airtime. Established on hardware before any of this was written; see
`docs/openhop-radio-stack.md`.

## Verification

Both build scripts grep the finished binary for strings that only exist if the
patches applied, and refuse to install if any is missing. Extend that list as
patches land. It has caught two real problems already, both of them cases where
the build succeeded and the binary was wrong.

## Remaining work for 2.0.0

App integration, the last piece. `DaemonService` becomes profile-driven and
passes `--fsdir`, `DaemonState.parse` learns MeshCore's log lines,
`NodeFiles` takes a profile parameter so backups stay separate, the Config tab
edits `NodePrefs` fields rather than YAML, and switching profiles stops one
daemon before starting the other.

Worth doing at the same time: drop `helpers/sensors` from the source filter and
stub `SensorManager`. The simulated I2C bus answers every probe, so each boot
logs fifteen lines of sensors that do not exist, which makes the log harder to
parse than it needs to be.
