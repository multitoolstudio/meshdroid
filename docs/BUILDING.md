# Building Meshdroid

The APK is built from three parts: the cross-compiled `meshtasticd`, a patched
USB library inside it, and the Kotlin app. All of it is scripted. The Fedora
instructions are the ones the project was developed and released on; the
Debian and Arch sections follow the same steps with the matching package names
and have not been fully tested yet.

## Contents

- [Host setup](#host-setup)
  - [Fedora](#fedora)
  - [Debian and Ubuntu (not fully tested)](#debian-and-ubuntu-not-fully-tested)
  - [Arch (not fully tested)](#arch-not-fully-tested)
  - [Common to all distributions](#common-to-all-distributions)
- [Phone setup](#phone-setup)
- [Optional: test the dongle on the host first](#optional-test-the-dongle-on-the-host-first)
- [Build the dependencies](#build-the-dependencies)
- [Build meshtasticd](#build-meshtasticd)
- [Smoke-test the binary](#smoke-test-the-binary)
- [Build the app](#build-the-app)
- [Updating the firmware](#updating-the-firmware)
- [Things that took time to learn](#things-that-took-time-to-learn)

## Host setup

You need: a C/C++ toolchain for the host, CMake 3.17 or newer, Ninja, git,
Python 3 with venv, a JDK 17, `adb`, and Android Studio with the NDK. About
5 GB of disk for the SDK, NDK and build caches.

### Fedora

```bash
sudo dnf install -y git cmake ninja-build gcc gcc-c++ make patch file \
  python3 python3-pip python3-virtualenv pkgconf-pkg-config \
  java-17-openjdk-devel android-tools unzip
```

udev rules for `adb` and the dongle. Fedora uses the `uaccess` tag, which
grants the logged-in user access without any group changes:

```bash
sudo tee /etc/udev/rules.d/51-android-ch341.rules >/dev/null <<'EOF'
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0660", TAG+="uaccess"
SUBSYSTEM=="usb", ATTR{idVendor}=="1a86", ATTR{idProduct}=="5512", MODE="0660", TAG+="uaccess"
EOF
sudo udevadm control --reload && sudo udevadm trigger
```

### Debian and Ubuntu (not fully tested)

```bash
sudo apt update
sudo apt install -y git cmake ninja-build build-essential patch file \
  python3 python3-pip python3-venv pkg-config \
  openjdk-17-jdk adb unzip
```

Debian's CMake may be older than 3.17 on stable releases; `cmake --version`
should print 3.17 or newer, otherwise install it from Kitware's apt repository
or use the CMake bundled with the Android SDK
(`$ANDROID_HOME/cmake/<version>/bin`, added to `PATH`).

udev on Debian uses the `plugdev` group:

```bash
sudo tee /etc/udev/rules.d/51-android-ch341.rules >/dev/null <<'EOF'
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0660", GROUP="plugdev"
SUBSYSTEM=="usb", ATTR{idVendor}=="1a86", ATTR{idProduct}=="5512", MODE="0660", GROUP="plugdev"
EOF
sudo usermod -aG plugdev "$USER"
sudo udevadm control --reload && sudo udevadm trigger
# log out and back in for the group change
```

### Arch (not fully tested)

```bash
sudo pacman -S --needed git cmake ninja base-devel patch file \
  python python-pip python-virtualenv pkgconf \
  jdk17-openjdk android-tools unzip
```

Android Studio is available from the AUR as `android-studio`; the tarball
method below works as well. Arch uses systemd's `uaccess` tag like Fedora, so
the Fedora udev rules apply unchanged.

### Common to all distributions

**PlatformIO** in a virtual environment. Distribution packages exist but tend
to lag; the firmware's own tooling expects a recent CLI:

```bash
python3 -m venv ~/pio
~/pio/bin/pip install -U platformio
echo 'export PATH=$HOME/pio/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
pio --version
```

**Android Studio and the NDK.** Use the tarball, not a Flatpak or Snap: those
sandbox the NDK where the build scripts cannot call it from a terminal.

1. Download the Linux tarball from https://developer.android.com/studio and
   extract it, for example to `/opt`: `sudo tar -xzf android-studio-*.tar.gz -C /opt`.
2. Run `/opt/android-studio/bin/studio.sh`, choose the Standard install and
   accept the licences.
3. In Studio: More Actions > SDK Manager > SDK Tools tab. Tick *NDK (Side by
   side)* and *CMake*. Apply.
4. Export the paths. The NDK version directory changes with updates; the
   `sort -V | tail -1` picks the newest one installed:

```bash
cat >> ~/.bashrc <<'EOF'
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$(ls -d $HOME/Android/Sdk/ndk/* | sort -V | tail -1)
export PATH=$ANDROID_HOME/platform-tools:$PATH
EOF
source ~/.bashrc
echo "$ANDROID_NDK_HOME"     # must print a real directory
```

Meshdroid was built with NDK r30 (clang 21). NDK r27 or newer should work;
older NDKs ship a libc++ that behaves differently and have not been tried.

## Phone setup

Settings > About > tap *Build number* seven times, then Developer options >
enable **USB debugging** and **Wireless debugging**. Wireless debugging matters
here: once the dongle occupies the USB-C port, `adb` over Wi-Fi is the only way
to see logs.

```bash
adb devices                 # accept the prompt on the phone
adb tcpip 5555              # while still on the cable
adb connect <phone-ip>:5555 # phone IP: Settings > About > IP address
```

## Optional: test the dongle on the host first

Proving the radio, antenna and YAML with the stock Linux daemon separates
hardware problems from Android problems, and gives you a second node to
message once the phone is up.

On Fedora, meshtasticd is packaged in the Meshtastic COPR:

```bash
sudo dnf copr enable @meshtastic/beta
sudo dnf install -y meshtasticd
lsusb | grep 1a86:5512
sudo cp /etc/meshtasticd/available.d/lora-usb-meshtoad-e22.yaml /etc/meshtasticd/config.d/
sudo systemctl enable --now meshtasticd
journalctl -fu meshtasticd        # look for "SX1262 init result 0"
```

For Debian and Arch, see the Meshtastic documentation for the Linux native
install; the steps after installation are the same.

Stop and disable the service before moving the dongle to the phone, otherwise
the host claims it on the next plug-in. Also note that if this node stays
running on your LAN it will answer on port 4403; make sure later Wi-Fi tests
target the phone's IP address and not this one.

## Build the dependencies

```bash
git clone <this repository> meshdroid
cd meshdroid/firmware
chmod +x *.sh
./build-deps-android.sh
```

This cross-compiles six static libraries into `firmware/android-prefix/`:
yaml-cpp, jsoncpp, libuv, libusb, argp-standalone and libi2c. It takes around
ten minutes and only needs to run once per NDK version. The script is safe to
re-run; it reuses the sources under `firmware/deps-src/`.

## Build meshtasticd

```bash
git clone https://github.com/meshtastic/firmware.git ~/meshtastic-firmware
./build-meshtasticd-android.sh ~/meshtastic-firmware
```

The firmware checkout is not pinned; the script builds whatever `master` is
at the time. Meshdroid 1.0.0 was built and tested against firmware 2.8.1
(commit `fdb6730`). To reproduce that build exactly, check out that commit
before running the script: `git -C ~/meshtastic-firmware checkout fdb6730`.

The script copies the Android env, variant and compat headers into the
firmware checkout, installs the firmware's PlatformIO packages, applies the
three patches (see [ARCHITECTURE.md](ARCHITECTURE.md)), exports the NDK
toolchain through the `TARGET_*` variables, runs `pio run -e android-arm64`,
and strips the result into `app/src/main/jniLibs/arm64-v8a/libmeshtasticd.so`
next to the NDK's `libc++_shared.so`. The first build takes a few minutes;
later builds only recompile what changed.

At the end it prints the binary's `NEEDED` list. Expected:

```
libc++_shared.so  libc.so  libm.so  libdl.so  liblog.so
```

If the build fails, the useful part of the output is the first `error:` block:

```bash
cd ~/meshtastic-firmware && pio run -e android-arm64 2>&1 | grep -m3 -B3 -A10 "error:"
```

## Smoke-test the binary

Runs the daemon on the phone with a simulated radio (`-s`). This exercises the
bionic runtime, the C++ library, YAML and argp parsing and the TCP API without
touching USB.

```bash
cd meshdroid
adb push app/src/main/jniLibs/arm64-v8a/libmeshtasticd.so /data/local/tmp/mtd
adb push app/src/main/jniLibs/arm64-v8a/libc++_shared.so /data/local/tmp/
adb shell chmod +x /data/local/tmp/mtd
adb shell LD_LIBRARY_PATH=/data/local/tmp /data/local/tmp/mtd -s -d /data/local/tmp/fs -p 4403 -v
```

Success looks like the firmware banner, `sim init success`, and
`API server listen on TCP port 4403`. Press Ctrl-C to stop it, then remove the
files; a test daemon left running keeps port 4403 and blocks the app:

```bash
adb shell pkill -f /data/local/tmp/mtd
adb shell rm -rf /data/local/tmp/mtd /data/local/tmp/fs /data/local/tmp/libc++_shared.so
```

## Build the app

Open the repository root (the directory containing `settings.gradle.kts`) in
Android Studio. File > Sync Project with Gradle Files; the first sync downloads
Gradle, the Android Gradle Plugin and the Compose libraries. Then Run with the
phone selected.

Studio may offer to upgrade the Android Gradle Plugin. Decline; the versions
in `build.gradle.kts` are a tested pair. The "deprecated Gradle features"
notice at the end of every build is normal for AGP 8.

For a signed release build, see [RELEASING.md](RELEASING.md).

## Updating the firmware

```bash
cd ~/meshtastic-firmware && git pull
cd meshdroid/firmware && ./build-meshtasticd-android.sh ~/meshtastic-firmware
```

Then rebuild the APK. If upstream changes something a patch touches, `patch`
reports a rejected hunk; the patches are small and readable, and
[ARCHITECTURE.md](ARCHITECTURE.md) describes what each one must accomplish.

## Things that took time to learn

Recorded here because each one cost a build cycle.

- **CMake 4** refuses projects that declare `cmake_minimum_required` below 3.5
  (yaml-cpp does). `build-deps-android.sh` passes
  `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` to every dependency.
- **bionic exports `fwrite_unlocked` without declaring it.** CMake's link probe
  reports it as present and clang then errors on the implicit declaration.
  The argp build is configured with `HAVE_DECL_*_UNLOCKED=0`.
- **argp-standalone's git tags** are `v1.0.1` to `v1.2.0` plus a bare `1.0.0`.
- **The native variant header assumes a Raspberry Pi hat** with an RV3028 RTC
  on I2C. `firmware/variant.h` replaces it for Android.
- **libc++ 19 removed `char_traits` for non-char types.** `MQTT.h` uses
  `basic_string<uint8_t>`. `firmware/android_compat.h` supplies the
  specialization and is force-included into every C++ translation unit.
- **The force-include reaches C files too.** The compat header is guarded by
  `__cplusplus`.
- **clang errors on `{}` narrowing where GCC warns.** `-Wno-c++11-narrowing`.
- **bionic has no `libpthread`.** `-lpthread` is removed from the link.
- **`-li2c` is in upstream's Linux-only flags.** The Android env adds it
  explicitly, and the link script appends it after the framework archive.
- **`build_unflags` removes every occurrence.** Putting `-li2c` in both
  `build_unflags` and `build_flags` removes it entirely.
- **SCons expands `$ORIGIN`.** The rpath flag is written `-Wl,-rpath=$$ORIGIN`;
  with `-rpath,$ORIGIN` the empty expansion swallowed `main.cpp.o`.
- **The NDK's `libc++_shared.so` is not on the phone.** It ships beside the
  binary, with `LD_LIBRARY_PATH` set by the service.
- **`printf` inside a `Print` subclass** resolves to `Print::printf`, which on
  the portduino WiFiServer routes into an unimplemented `write()` and kills the
  daemon with SIGINT. Patch 0002 uses `::fprintf(stderr, ...)`.
- **Firmware 2.8 defers key generation until a region is set.** A fresh node
  with no region never writes `config.proto`. Patch 0003 and the app's region
  picker exist for this.
