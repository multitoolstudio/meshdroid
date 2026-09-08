#!/usr/bin/env bash
# Builds meshtasticd for Android arm64 and installs it into the app.
#
# Steps, in order:
#   1. Copy the Android PlatformIO env, variant header, compat header and link
#      script into the firmware checkout.
#   2. Install the firmware's PlatformIO packages, then apply the three patches:
#        0001  libch341-spi-userspace: receive the USB fd from the app
#        0002  framework WiFi shim: MESHTASTICD_BIND_ADDR for the API server
#        0003  firmware: config.yaml Lora.Region applied on first boot
#   3. Export the TARGET_* variables that platform-native's "buildroot" board
#      reads, pointing at the NDK toolchain, and run pio.
#   4. Strip the binary into app/src/main/jniLibs/arm64-v8a/libmeshtasticd.so
#      alongside the NDK's libc++_shared.so.
#
# The executable is packaged as a lib*.so because nativeLibraryDir is the only
# location an unrooted app may exec() from.
#
# Usage:
#   ./build-meshtasticd-android.sh /path/to/meshtastic/firmware
#
# Requires ANDROID_NDK_HOME and a prior run of build-deps-android.sh.
set -euo pipefail

FW="${1:?Path to a meshtastic/firmware checkout}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/../app"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
export ANDROID_PREFIX="${ANDROID_PREFIX:-$ROOT/android-prefix}"
API=28
HOST_TAG=$(ls "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/")
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"

[ -f "$ANDROID_PREFIX/lib/libusb-1.0.a" ] || { echo "Run ./build-deps-android.sh first"; exit 1; }

# ---- 1. Copy the Android build files into the firmware tree -----------------
mkdir -p "$FW/variants/native/portduino-android"
cp "$ROOT/platformio-android.ini" "$FW/variants/native/portduino-android/platformio.ini"
cp "$ROOT/variant.h" "$ROOT/android_compat.h" "$FW/variants/native/portduino-android/"
cp "$ROOT/extra_scripts/android_link_flags.py" "$FW/extra_scripts/"
# The firmware's top-level platformio.ini pulls in variants/*/*/platformio.ini
# through extra_configs, so the new env is discovered without editing anything.
# The check below is advisory only; pio run reports a missing env clearly.
( cd "$FW" && pio project config 2>&1 | grep -q "env:android-arm64" ) || {
  echo "?? pio project config didn't list env:android-arm64. Continuing anyway;"
  echo "   pio run will fail clearly below if the env really is missing."; }

# ---- 2. Install PlatformIO packages, then apply the patches -----------------
( cd "$FW" && pio pkg install -e android-arm64 )
LIBCH341=$(find "$FW/.pio/libdeps/android-arm64" -maxdepth 1 \( -iname "*libch341*" -o -iname "*pine*" \) | head -1)
[ -n "$LIBCH341" ] || { echo "!! libch341-spi-userspace not found in .pio/libdeps/android-arm64"; exit 1; }
if ! grep -q PINEDIO_USB_FD_SOCKET "$LIBCH341/libpinedio-usb.c"; then
  echo ">> patching $LIBCH341"
  patch -p1 -d "$LIBCH341" < "$ROOT/0001-libch341-android-fd.patch"
fi
# Patch 0002 targets the framework's WiFi shim in the PlatformIO package cache,
# which is shared by every env built on this machine. The grep looks for the
# current form of the patch; an older form is reverted with git first.
WIFI_SHIM="$HOME/.platformio/packages/framework-portduino/libraries/WiFi"
if [ ! -f "$WIFI_SHIM/src/WiFiServer.cpp" ]; then
  echo "!! $WIFI_SHIM/src/WiFiServer.cpp not found - LAN/local bind switch will not work"; exit 1
fi
if ! grep -q "::fprintf(stderr, \"API server binding" "$WIFI_SHIM/src/WiFiServer.cpp"; then
  echo ">> patching $WIFI_SHIM"
  ( cd "$WIFI_SHIM" && git checkout -q -- src/WiFiServer.cpp 2>/dev/null || true )
  patch -p1 -d "$WIFI_SHIM" < "$ROOT/0002-wifiserver-bind-addr.patch"
  rm -rf "$FW/.pio/build/android-arm64/lib"*/libWiFi.a   # make PlatformIO recompile the shim
fi

# Patch 0003 modifies the firmware tree itself (main.cpp, PortduinoGlue.*).
if ! grep -q "portduinoApplyInitialRegion" "$FW/src/platform/portduino/PortduinoGlue.cpp"; then
  echo ">> patching firmware for Lora.Region (0003)"
  patch -p1 -d "$FW" < "$ROOT/0003-yaml-initial-region.patch"
fi

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

# ---- 4. Install into the app ------------------------------------------------
OUT="$FW/.pio/build/android-arm64/meshtasticd"
file "$OUT" | grep -q "ARM aarch64" || { echo "!! $OUT is not an aarch64 binary"; exit 1; }
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
echo ">> Done. Open the project in Android Studio and run, or Build > Generate Signed APK."
