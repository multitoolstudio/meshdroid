#!/usr/bin/env bash
# Cross-builds the libraries meshcored links against that Android's C library
# (bionic) does not provide:
#   libusb, libuv, argp-standalone, libi2c (from i2c-tools)
#
# Each is built as a static library for arm64-v8a, API level 28, and installed
# under $MESHCORE_PREFIX (default: ./android-prefix). The final meshcored then
# depends only on bionic, liblog and the NDK's libc++_shared.so.
#
# Deliberately separate from firmware/android-prefix. The two daemons share only
# the toolchain, and one prefix per daemon means a MeshCore dependency bump can
# never disturb a working meshtasticd build. The cost is a second copy of libusb
# built with the same NDK, which is a few hundred kilobytes on disk and nothing
# in the APK, since both link it statically.
#
# Far shorter than firmware/build-deps-android.sh, because MeshCore needs none
# of what meshtasticd needs:
#   yaml-cpp   MeshCore has no config.yaml; its settings live in a NodePrefs
#              struct persisted as /new_prefs
#   jsoncpp    no MQTT in this build
# argp and libi2c are both here despite MeshCore needing neither: they are
# framework-portduino's dependencies, not MeshCore's. Its Arduino.h includes
# <argp.h> unconditionally and its LinuxHardwareI2C.cpp includes <i2c/smbus.h>,
# so every build needs them even with nothing on the I2C bus.
#
# argp is here despite MeshCore not parsing options with it: framework-portduino's
# own Arduino.h includes <argp.h> unconditionally, so every translation unit needs
# it. Leaving it out fails the build at the first file compiled.
#
# Usage:
#   ./build-deps-android.sh
#
# Requires ANDROID_NDK_HOME. Safe to re-run: sources already cloned under
# ./deps-src are reused, and CMake reconfigures in place.
set -euo pipefail

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your NDK directory (Android Studio > SDK Manager > NDK)}"
ABI=arm64-v8a
API=28
ROOT="$(cd "$(dirname "$0")" && pwd)"
PREFIX="${MESHCORE_PREFIX:-$ROOT/android-prefix}"
SRC="$ROOT/deps-src"
TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
HOST_TAG=$(ls "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/")
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
NPROC=$(nproc 2>/dev/null || sysctl -n hw.ncpu)

mkdir -p "$PREFIX" "$SRC"
echo ">> prefix: $PREFIX"

cmake_build() {   # cmake_build <name> <git url> <tag> [extra cmake args...]
  local name=$1 url=$2 tag=$3; shift 3
  if [ ! -d "$SRC/$name" ]; then
    git clone --depth 1 --branch "$tag" --recurse-submodules "$url" "$SRC/$name"
  fi
  cmake -S "$SRC/$name" -B "$SRC/$name/build-android" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    "$@"
  cmake --build "$SRC/$name/build-android" -j"$NPROC"
  cmake --install "$SRC/$name/build-android"
}

# --- libusb: CH341 access through the fd handed over by the app -------------
# The same versions meshtasticd builds against, so a problem traced to libusb in
# one daemon means the same thing in the other. Our RadioLib HAL reaches the
# dongle through libusb_wrap_sys_device() on a descriptor the app opens, exactly
# as patch 0001 does for meshtasticd; an unrooted app cannot enumerate USB
# itself.
cmake_build libusb https://github.com/libusb/libusb-cmake.git v1.0.29 \
  -DLIBUSB_BUILD_SHARED_LIBS=OFF -DLIBUSB_BUILD_EXAMPLES=OFF -DLIBUSB_BUILD_TESTING=OFF \
  -DLIBUSB_INSTALL_TARGETS=ON

# --- libuv: linked by the portduino framework -------------------------------
# Not a MeshCore dependency. portduino_base names -luv, and we extend it.
cmake_build libuv https://github.com/libuv/libuv.git v1.51.0 \
  -DLIBUV_BUILD_TESTS=OFF -DLIBUV_BUILD_BENCH=OFF -DLIBUV_BUILD_SHARED=OFF
# Older libuv releases install libuv_a.a; the link line says -luv, so make sure
# libuv.a exists under that name.
[ -f "$PREFIX/lib/libuv.a" ] || cp "$PREFIX/lib/libuv_a.a" "$PREFIX/lib/libuv.a"

# --- argp-standalone: bionic has no <argp.h> --------------------------------
# framework-portduino's cores/portduino/Arduino.h includes <argp.h>, so this is
# needed even though nothing in MeshCore calls argp_parse().
#
# bionic exports fwrite_unlocked as a symbol but does not declare it in <stdio.h>.
# CMake's link probe therefore reports it as found, and clang then rejects the
# implicit declaration. Pre-seeding the probe results makes argp use the locked
# stdio calls instead. Same workaround as firmware/build-deps-android.sh.
cmake_build argp-standalone https://github.com/tom42/argp-standalone.git v1.2.0 \
  -DHAVE_DECL_FWRITE_UNLOCKED=0 -DHAVE_DECL_FPUTS_UNLOCKED=0 -DHAVE_DECL_PUTC_UNLOCKED=0
# The project has no install() rules; copy the header and archive by hand.
cp "$SRC/argp-standalone/include/argp-standalone/argp.h" "$PREFIX/include/argp.h"
cp "$SRC/argp-standalone/build-android/src/libargp-standalone.a" "$PREFIX/lib/libargp.a"

# --- libi2c from i2c-tools: framework-portduino includes <i2c/smbus.h> ------
# Only smbus.c is needed, compiled directly with the NDK clang rather than going
# through the i2c-tools Makefile. Nothing is on the I2C bus here; this exists
# because LinuxHardwareI2C.cpp is part of the framework and always compiles.
if [ ! -d "$SRC/i2c-tools" ]; then
  git clone --depth 1 https://git.kernel.org/pub/scm/utils/i2c-tools/i2c-tools.git "$SRC/i2c-tools"
fi
mkdir -p "$PREFIX/include/i2c"
cp "$SRC/i2c-tools/include/i2c/smbus.h" "$PREFIX/include/i2c/"
"$BIN/aarch64-linux-android$API-clang" -Os -fPIC -I"$SRC/i2c-tools/include" \
  -c "$SRC/i2c-tools/lib/smbus.c" -o "$SRC/i2c-tools/smbus.o"
"$BIN/llvm-ar" rcs "$PREFIX/lib/libi2c.a" "$SRC/i2c-tools/smbus.o"

echo
echo ">> Done. Libraries in $PREFIX/lib:"
ls "$PREFIX/lib"
echo ">> Now run: MESHCORE_PREFIX=$PREFIX ./build-meshcored-android.sh /home/john/meshcore-firmware"
