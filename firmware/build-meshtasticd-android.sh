#!/usr/bin/env bash
# Builds meshtasticd for Android arm64 and installs it into the app.
#
# Steps, in order:
#   1. Check out the pinned firmware commit, then copy the Android PlatformIO env,
#      variant header, compat header and link script into the firmware checkout.
#   2. Install the firmware's PlatformIO packages, then apply the four patches:
#        0001  libch341-spi-userspace: receive the USB fd from the app
#        0002  framework WiFi shim: MESHTASTICD_BIND_ADDR for the API server
#        0003  firmware: config.yaml Lora.Region applied on first boot
#        0004  firmware: SIGTERM saves the node data and exits (needs 0003 first)
#        0005  firmware: battery level read from a host-written file
#   3. Export the TARGET_* variables that platform-native's "buildroot" board
#      reads, pointing at the NDK toolchain, and run pio.
#   4. Verify the built binary carries the strings the app depends on, then strip
#      it into app/src/main/jniLibs/arm64-v8a/libmeshtasticd.so alongside the
#      NDK's libc++_shared.so.
#
# The executable is packaged as a lib*.so because nativeLibraryDir is the only
# location an unrooted app may exec() from.
#
# Usage:
#   ./build-meshtasticd-android.sh /path/to/meshtastic/firmware
#
# Environment:
#   ANDROID_NDK_HOME  required, see docs/BUILDING.md
#   ANDROID_PREFIX    where build-deps-android.sh installed the static libraries
#   FW_COMMIT         firmware commit to build, or "keep" to build the checkout
#                     as it stands. Defaults to the pinned commit below.
set -euo pipefail

# The commit each release is built from. Bump it deliberately, rebuild, test on a
# phone, and record the new hash in CHANGELOG.md and the release notes. Building
# whatever master happens to be produces binaries nobody can reproduce later.
FW_COMMIT_DEFAULT="fdb6730"          # meshtastic/firmware 2.8.1
FW_COMMIT="${FW_COMMIT:-$FW_COMMIT_DEFAULT}"

FW="${1:?Path to a meshtastic/firmware checkout}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/../app"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
export ANDROID_PREFIX="${ANDROID_PREFIX:-$ROOT/android-prefix}"
API=28
HOST_TAG=$(ls "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/")
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"

[ -f "$ANDROID_PREFIX/lib/libusb-1.0.a" ] || { echo "Run ./build-deps-android.sh first"; exit 1; }

# ---- 0. Pin the firmware commit ---------------------------------------------
if [ "$FW_COMMIT" = "keep" ]; then
  echo ">> building the checkout as it stands: $(git -C "$FW" rev-parse --short HEAD 2>/dev/null || echo unknown)"
else
  CURRENT=$(git -C "$FW" rev-parse --short HEAD 2>/dev/null || echo none)
  if [ "$CURRENT" != "$FW_COMMIT" ]; then
    echo ">> checking out firmware $FW_COMMIT (was $CURRENT)"
    git -C "$FW" fetch --tags origin
    git -C "$FW" checkout -q "$FW_COMMIT"
    git -C "$FW" submodule update --init --recursive
  fi
fi

# ---- 1. Copy the Android build files into the firmware tree -----------------
mkdir -p "$FW/variants/native/portduino-android"
cp "$ROOT/platformio-android.ini" "$FW/variants/native/portduino-android/platformio.ini"
cp "$ROOT/variant.h" "$ROOT/android_compat.h" "$FW/variants/native/portduino-android/"
cp "$ROOT/extra_scripts/android_link_flags.py" "$FW/extra_scripts/"
# The firmware's top-level platformio.ini pulls in variants/*/*/platformio.ini
# through extra_configs, so the new env is discovered without editing anything.
# The check below is advisory only; pio run reports a missing env clearly.
# grep reads to EOF here on purpose: with "set -o pipefail", a "grep -q" that
# exits on the first match kills the writer with SIGPIPE and fails the pipeline
# even though the string was found.
( cd "$FW" && pio project config 2>&1 | grep "env:android-arm64" >/dev/null ) || {
  echo "?? pio project config didn't list env:android-arm64. Continuing anyway;"
  echo "   pio run will fail clearly below if the env really is missing."; }

# ---- 2. Install PlatformIO packages, then apply the patches -----------------
( cd "$FW" && pio pkg install -e android-arm64 )
find_libch341() {
  find "$FW/.pio/libdeps/android-arm64" -maxdepth 1 \( -iname "*libch341*" -o -iname "*pine*" \) | sort | head -1
}
LIBCH341=$(find_libch341)
[ -n "$LIBCH341" ] || { echo "!! libch341-spi-userspace not found in .pio/libdeps/android-arm64"; exit 1; }

# PINEDIO_USB_FD_SOCKET says some version of 0001 is present; PINEDIO_FD_PATCH_R2
# says it is this one. A tree patched by an earlier release has the first and not
# the second, and a patch file cannot be applied on top of its own older output,
# so the package is thrown away and re-installed clean.
if grep -q PINEDIO_USB_FD_SOCKET "$LIBCH341/libpinedio-usb.c" && \
   ! grep -q PINEDIO_FD_PATCH_R2 "$LIBCH341/libpinedio-usb.c"; then
  echo ">> $LIBCH341 carries an older patch 0001; re-installing the package"
  rm -rf "$LIBCH341"
  ( cd "$FW" && pio pkg install -e android-arm64 )
  LIBCH341=$(find_libch341)
  [ -n "$LIBCH341" ] || { echo "!! libch341-spi-userspace disappeared after re-install"; exit 1; }
fi
if ! grep -q PINEDIO_FD_PATCH_R2 "$LIBCH341/libpinedio-usb.c"; then
  echo ">> patching $LIBCH341 (0001)"
  patch -p1 -d "$LIBCH341" < "$ROOT/0001-libch341-android-fd.patch"
fi
grep -q PINEDIO_FD_PATCH_R2 "$LIBCH341/libpinedio-usb.c" || {
  echo "!! patch 0001 is not in libpinedio-usb.c. The daemon cannot reach the radio."; exit 1; }

# Patch 0002 targets the framework's WiFi shim in the PlatformIO package cache,
# which is shared by every env built on this machine and is reverted by
# "pio pkg update". Without it the daemon ignores MESHTASTICD_BIND_ADDR and binds
# every interface whatever the app's LAN switch says, so a missing patch is fatal
# here rather than a warning.
WIFI_SHIM="$HOME/.platformio/packages/framework-portduino/libraries/WiFi"
if [ ! -f "$WIFI_SHIM/src/WiFiServer.cpp" ]; then
  echo "!! $WIFI_SHIM/src/WiFiServer.cpp not found - LAN/local bind switch will not work"; exit 1
fi
if ! grep -q "::fprintf(stderr, \"API server binding" "$WIFI_SHIM/src/WiFiServer.cpp"; then
  echo ">> patching $WIFI_SHIM (0002)"
  ( cd "$WIFI_SHIM" && git checkout -q -- src/WiFiServer.cpp 2>/dev/null || true )
  patch -p1 -d "$WIFI_SHIM" < "$ROOT/0002-wifiserver-bind-addr.patch"
  rm -rf "$FW/.pio/build/android-arm64/lib"*/libWiFi.a   # make PlatformIO recompile the shim
fi
grep -q "::fprintf(stderr, \"API server binding" "$WIFI_SHIM/src/WiFiServer.cpp" || {
  echo "!! patch 0002 did not apply to the framework WiFi shim."; exit 1; }

# Patches 0003 and 0004 modify the firmware tree itself (main.cpp, PortduinoGlue.*).
# 0004 appends to the function 0003 adds, so the order matters.
if ! grep -q "portduinoApplyInitialRegion" "$FW/src/platform/portduino/PortduinoGlue.cpp"; then
  echo ">> patching firmware for Lora.Region (0003)"
  patch -p1 -d "$FW" < "$ROOT/0003-yaml-initial-region.patch"
fi
if ! grep -q "portduinoCheckShutdown" "$FW/src/platform/portduino/PortduinoGlue.cpp"; then
  echo ">> patching firmware for clean shutdown on SIGTERM (0004)"
  patch -p1 -d "$FW" < "$ROOT/0004-portduino-clean-shutdown.patch"
fi
if ! grep -q "portduinoFileBatteryInit" "$FW/src/Power.cpp"; then
  echo ">> patching firmware for host battery level (0005)"
  patch -p1 -d "$FW" < "$ROOT/0005-portduino-battery-file.patch"
fi
grep -q "portduinoFileBatteryInit" "$FW/src/Power.cpp" || {
  echo "!! patch 0005 did not apply to src/Power.cpp."; exit 1; }
for marker in portduinoApplyInitialRegion portduinoCheckShutdown; do
  grep -q "$marker" "$FW/src/platform/portduino/PortduinoGlue.cpp" || {
    echo "!! $marker missing from PortduinoGlue.cpp; patch 0003 or 0004 failed."; exit 1; }
done
# The call sites matter as much as the definitions. portduinoCheckShutdown() is
# only reachable from loop(), and the link runs with --gc-sections: if that hunk
# is rejected the function and its strings are dropped, the build succeeds, and
# the daemon dies on SIGTERM without saving. Rejected hunks leave .rej files, so
# check for those too.
for marker in portduinoApplyInitialRegion portduinoCheckShutdown; do
  grep -q "$marker" "$FW/src/main.cpp" || {
    echo "!! $marker is never called from src/main.cpp; a patch hunk was rejected."
    echo "   Look for .rej files under $FW/src, then: git -C $FW checkout -- src/ and re-run."; exit 1; }
done
REJECTS=$(find "$FW/src" -name '*.rej' 2>/dev/null | head -5)
[ -z "$REJECTS" ] || { echo "!! rejected patch hunks left behind:"; echo "$REJECTS"; exit 1; }

# ---- 3. Cross toolchain through platform-native's "buildroot" board ---------
export TARGET_CC="$BIN/aarch64-linux-android$API-clang"
export TARGET_CXX="$BIN/aarch64-linux-android$API-clang++"
export TARGET_AR="$BIN/llvm-ar"
export TARGET_AS="$TARGET_CC"
export TARGET_LD="$TARGET_CXX"
export TARGET_OBJCOPY="$BIN/llvm-objcopy"
export TARGET_RANLIB="$BIN/llvm-ranlib"
export TARGET_CFLAGS="-fPIC -I$ANDROID_PREFIX/include"
export TARGET_CXXFLAGS="-fPIC -I$ANDROID_PREFIX/include"
export TARGET_LDFLAGS="-L$ANDROID_PREFIX/lib"
# The base env runs pkg-config for jsoncpp; point it at the prefix so host
# include paths never leak into the cross build.
export PKG_CONFIG_LIBDIR="$ANDROID_PREFIX/lib/pkgconfig"

( cd "$FW" && pio run -e android-arm64 )

# ---- 4. Verify, then install into the app -----------------------------------
OUT="$FW/.pio/build/android-arm64/meshtasticd"
file "$OUT" | grep "ARM aarch64" >/dev/null || { echo "!! $OUT is not an aarch64 binary"; exit 1; }

# The app parses the first two of these out of the daemon's output, and the LAN
# switch is only real if the first one is present. The third comes from patch
# 0004 and only survives the link if portduinoCheckShutdown() is actually
# reachable, which makes it the check that a call site was really patched in. A
# stale object file from a build made before a patch was applied would otherwise
# ship silently.
# grep searches the binary directly. Piping "strings" into "grep -q" looks
# tidier but fails under "set -o pipefail" whenever the match is found early:
# grep exits, strings dies of SIGPIPE, and the pipeline reports failure.
for s in "API server binding" "Using pre-opened CH341 fd" "saving node data and exiting" "Using host battery level from"; do
  LC_ALL=C grep -qaF -- "$s" "$OUT" || { echo "!! built binary has no \"$s\" string; a patch did not make it into this build."; exit 1; }
done

DEST="$APP/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"
"$BIN/llvm-strip" -o "$DEST/libmeshtasticd.so" "$OUT"
# The binary links against the NDK's libc++_shared.so, which Android does not
# provide system-wide. Ship it beside the executable. The service sets
# LD_LIBRARY_PATH to nativeLibraryDir and the binary carries an $ORIGIN rpath.
cp "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$DEST/"
ls -la "$DEST"
echo ">> NEEDED libraries (should be libc++_shared, libc, libm, libdl, liblog only):"
"$BIN/llvm-readelf" -d "$DEST/libmeshtasticd.so" | grep NEEDED
echo ">> Built from firmware $(git -C "$FW" rev-parse --short HEAD). Record that hash in the release notes."
echo ">> Done. Open the project in Android Studio and run, or ./gradlew assembleRelease."
