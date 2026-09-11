#!/usr/bin/env bash
# Builds meshcored for Android arm64 and installs it into the app.
#
# Steps, in order:
#   1. Check out the pinned MeshCore tag, then copy the Android PlatformIO env,
#      the variant (target.h, target.cpp, USBHal.h) and the link script into the
#      MeshCore checkout.
#   2. Install the PlatformIO packages, then apply any patches in ./patches.
#   3. Export the TARGET_* variables that platform-native's "buildroot" board
#      reads, pointing at the NDK toolchain, and run pio.
#   4. Verify the built binary, then strip it into
#      app/src/main/jniLibs/arm64-v8a/libmeshcored.so alongside the NDK's
#      libc++_shared.so.
#
# The executable is packaged as a lib*.so because nativeLibraryDir is the only
# location an unrooted app may exec() from. Same trick as meshtasticd.
#
# Usage:
#   ./build-meshcored-android.sh /home/john/meshcore-firmware
#
# Environment:
#   ANDROID_NDK_HOME  required
#   MESHCORE_PREFIX   where build-deps-android.sh installed the static libraries
#   MC_TAG            MeshCore tag to build, or "keep" to build the checkout as
#                     it stands. Defaults to the pinned tag below.
set -euo pipefail

# The tag each release is built from. Bump it deliberately, rebuild, test on a
# phone, and record it in CHANGELOG.md. Building whatever main happens to be
# produces binaries nobody can reproduce later.
MC_TAG_DEFAULT="companion-v1.17.1"       # meshcore-dev/MeshCore, commit d9296435
MC_TAG="${MC_TAG:-$MC_TAG_DEFAULT}"

MC="${1:?Path to a meshcore-dev/MeshCore checkout}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/../../app"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
export MESHCORE_PREFIX="${MESHCORE_PREFIX:-$ROOT/android-prefix}"
API=28
HOST_TAG=$(ls "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/")
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
VARIANT="variants/meshdroid_ch341"

[ -f "$MESHCORE_PREFIX/lib/libusb-1.0.a" ] || { echo "Run ./build-deps-android.sh first"; exit 1; }
[ -f "$ROOT/variant/USBHal.h" ] || {
  echo "!! variant/USBHal.h is missing."
  echo "   cp /path/to/meshtastic-firmware/src/platform/portduino/USBHal.h $ROOT/variant/"
  exit 1
}

# ---- 0. Pin the MeshCore tag -------------------------------------------------
if [ "$MC_TAG" = "keep" ]; then
  echo ">> building the checkout as it stands (MC_TAG=keep)"
else
  echo ">> checking out $MC_TAG"
  ( cd "$MC" && git checkout -q "$MC_TAG" )
fi
echo ">> MeshCore at $( cd "$MC" && git describe --tags --always )"

# ---- 1. Copy the Android build files into the MeshCore tree ------------------
# MeshCore's top-level platformio.ini globs variants/*/platformio.ini, so the
# env is picked up by living in the variant directory. target.h has to be there
# too: MyMesh.h includes it as <target.h> and the env adds -I on this directory.
mkdir -p "$MC/$VARIANT" "$MC/extra_scripts"
cp "$ROOT/platformio-android.ini"            "$MC/$VARIANT/platformio.ini"
cp "$ROOT/variant/target.h"                  "$MC/$VARIANT/target.h"
cp "$ROOT/variant/target.cpp"                "$MC/$VARIANT/target.cpp"
cp "$ROOT/variant/SocketSerialInterface.h"   "$MC/$VARIANT/SocketSerialInterface.h"
cp "$ROOT/variant/USBHal.h"                  "$MC/$VARIANT/USBHal.h"
cp "$ROOT/extra_scripts/android_link_flags.py" "$MC/extra_scripts/"
echo ">> variant installed at $MC/$VARIANT"

# ---- 2. Install PlatformIO packages, then apply the patches -----------------
# Packages first: the patches touch files inside downloaded libraries, which do
# not exist until pio has fetched them.
( cd "$MC" && pio pkg install -e android-arm64 )

shopt -s nullglob
PATCHES=("$ROOT"/patches/*.patch)
shopt -u nullglob

if [ ${#PATCHES[@]} -eq 0 ]; then
  echo ">> no patches in $ROOT/patches (expected while the port is being brought up)"
else
  for p in "${PATCHES[@]}"; do
    name=$(basename "$p")
    case "$name" in
      # Patches against libch341-spi-userspace apply inside the PlatformIO
      # package directory, not the MeshCore tree. The directory is named from
      # the library's own library.json, which calls it "Pine libch341-spi
      # Userspace library" (with spaces), so match on a glob rather than
      # assuming the repository name.
      *libch341*)
        target=$(find "$MC/.pio/libdeps/android-arm64" -maxdepth 1 -iname '*ch341*' | head -1)
        if [ -z "$target" ]; then
          echo "!! no ch341 library directory under $MC/.pio/libdeps/android-arm64"
          ls "$MC/.pio/libdeps/android-arm64"
          exit 1
        fi
        ;;
      *)
        target="$MC"
        ;;
    esac
    if [ ! -d "$target" ]; then
      echo "!! patch target $target does not exist for $name"; exit 1
    fi
    # "patch" rather than "git apply" on purpose. .pio/libdeps/ lives inside the
    # MeshCore work tree, so git apply resolves a/ and b/ paths against the
    # repository root instead of the directory it was pointed at. That made its
    # --reverse --check report "already applied" for a patch that had never been
    # applied, and the build then shipped an unpatched binary. patch has no
    # notion of an enclosing repository and handles the spaces in PlatformIO's
    # library directory names.
    if patch -p1 -d "$target" -R --dry-run --force --quiet < "$p" >/dev/null 2>&1; then
      echo ">> $name already applied"
    elif patch -p1 -d "$target" --force --no-backup-if-mismatch < "$p"; then
      echo ">> applied $name to $target"
    else
      echo "!! $name failed to apply to $target"
      exit 1
    fi
  done
fi

# Rejects anywhere a patch could have landed, including the library directories.
REJECTS=$(find "$MC/src" "$MC/examples" "$MC/.pio/libdeps" -name '*.rej' 2>/dev/null | head -5)
[ -z "$REJECTS" ] || { echo "!! patch rejects present:"; echo "$REJECTS"; exit 1; }

# ---- 3. Cross toolchain through platform-native's "buildroot" board ---------
export TARGET_CC="$BIN/aarch64-linux-android$API-clang"
export TARGET_CXX="$BIN/aarch64-linux-android$API-clang++"
export TARGET_AR="$BIN/llvm-ar"
export TARGET_AS="$TARGET_CC"
export TARGET_LD="$TARGET_CXX"
export TARGET_OBJCOPY="$BIN/llvm-objcopy"
export TARGET_RANLIB="$BIN/llvm-ranlib"
export TARGET_CFLAGS="-fPIC -I$MESHCORE_PREFIX/include"
export TARGET_CXXFLAGS="-fPIC -I$MESHCORE_PREFIX/include"
export TARGET_LDFLAGS="-L$MESHCORE_PREFIX/lib"
export PKG_CONFIG_LIBDIR="$MESHCORE_PREFIX/lib/pkgconfig"

( cd "$MC" && pio run -e android-arm64 )

# ---- 4. Verify, then install into the app -----------------------------------
# PlatformIO names the artefact from the project, and MeshCore does not set a
# program name, so look for whatever executable landed in the build directory
# rather than assuming.
BUILD_DIR="$MC/.pio/build/android-arm64"
OUT=""
for candidate in meshcored program firmware firmware.elf; do
  [ -f "$BUILD_DIR/$candidate" ] && { OUT="$BUILD_DIR/$candidate"; break; }
done
if [ -z "$OUT" ]; then
  echo "!! no executable found in $BUILD_DIR. Contents:"
  ls -la "$BUILD_DIR"
  exit 1
fi
echo ">> built $OUT"

file "$OUT" | grep -q "ARM aarch64" || { echo "!! $OUT is not an aarch64 binary"; exit 1; }

# Strings the app depends on, checked against the binary so a patch that
# silently failed to apply cannot ship. Extend this list as the patches land.
# grep searches the binary directly: piping "strings" into "grep -q" looks
# tidier but fails under "set -o pipefail" whenever the match is found early.
for s in "Using pre-opened CH341 fd" "Meshdroid MeshToad V3"; do
  LC_ALL=C grep -qaF -- "$s" "$OUT" || {
    echo "!! built binary has no \"$s\" string; a patch did not make it into this build."
    exit 1
  }
done

DEST="$APP/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"
"$BIN/llvm-strip" -o "$DEST/libmeshcored.so" "$OUT"

# The binary links against the NDK's libc++_shared.so, which Android does not
# ship. Package it beside the daemon; -Wl,-rpath=$ORIGIN finds it there.
SYSROOT_LIB="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib/aarch64-linux-android"
cp "$SYSROOT_LIB/libc++_shared.so" "$DEST/libc++_shared.so"

echo
echo ">> Installed:"
ls -la "$DEST"
