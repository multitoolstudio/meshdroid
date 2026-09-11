#!/usr/bin/env bash
# Builds meshcored for the desktop, to test the CH341 HAL against a real
# MeshToad without an APK in the way.
#
# Same sources, same variant, same patches and the same Ch341Hal as the Android
# build. What differs is the toolchain (host gcc rather than the NDK) and the
# libraries (the distribution's, rather than the static ones under
# ./android-prefix). See the linux-x86_64 comment in platformio-android.ini.
#
# The result is not packaged and never enters the APK. It exists to answer one
# question: does the radio come up and join the mesh?
#
# Usage:
#   ./build-meshcored-linux.sh /home/john/meshcore-firmware
#   ./build-meshcored-linux.sh /home/john/meshcore-firmware --run
#
# Fedora prerequisites:
#   sudo dnf install libusb1-devel i2c-tools-devel libuv-devel gcc-c++
#
# The CH341 is opened through libusb, so a non-root user needs the udev rule
# from the spike:
#   SUBSYSTEM=="usb", ATTRS{idVendor}=="1a86", ATTRS{idProduct}=="5512", \
#     MODE="0660", GROUP="plugdev", TAG+="uaccess"
set -euo pipefail

MC_TAG_DEFAULT="companion-v1.17.1"       # meshcore-dev/MeshCore, commit d9296435
MC_TAG="${MC_TAG:-$MC_TAG_DEFAULT}"

MC="${1:?Path to a meshcore-dev/MeshCore checkout}"
RUN="${2:-}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
VARIANT="variants/meshdroid_ch341"
DATA_DIR="${MESHCORE_DATA_DIR:-$HOME/.meshcored-test}"

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

# ---- 1. Copy the variant in --------------------------------------------------
mkdir -p "$MC/$VARIANT" "$MC/extra_scripts"
cp "$ROOT/platformio-android.ini"              "$MC/$VARIANT/platformio.ini"
cp "$ROOT/variant/target.h"                    "$MC/$VARIANT/target.h"
cp "$ROOT/variant/target.cpp"                  "$MC/$VARIANT/target.cpp"
cp "$ROOT/variant/AndroidCompat.h"             "$MC/$VARIANT/AndroidCompat.h"
cp "$ROOT/variant/MeshdroidFS.h"               "$MC/$VARIANT/MeshdroidFS.h"
cp "$ROOT/variant/SocketSerialInterface.h"   "$MC/$VARIANT/SocketSerialInterface.h"
cp "$ROOT/variant/USBHal.h"                    "$MC/$VARIANT/USBHal.h"
cp "$ROOT/extra_scripts/android_link_flags.py" "$MC/extra_scripts/"
echo ">> variant installed at $MC/$VARIANT"

# ---- 2. Packages, then patches ----------------------------------------------
( cd "$MC" && pio pkg install -e linux-x86_64 )

shopt -s nullglob
PATCHES=("$ROOT"/patches/*.patch)
shopt -u nullglob

for p in "${PATCHES[@]}"; do
  name=$(basename "$p")
  case "$name" in
    *libch341*)
      target=$(find "$MC/.pio/libdeps/linux-x86_64" -maxdepth 1 -iname '*ch341*' | head -1)
      if [ -z "$target" ]; then
        echo "!! no ch341 library directory under $MC/.pio/libdeps/linux-x86_64"
        ls "$MC/.pio/libdeps/linux-x86_64"; exit 1
      fi
      ;;
    *)
      target="$MC"
      ;;
  esac
  if patch -p1 -d "$target" -R --dry-run --force --quiet < "$p" >/dev/null 2>&1; then
    echo ">> $name already applied"
  elif patch -p1 -F0 -d "$target" --force --no-backup-if-mismatch < "$p"; then
    echo ">> applied $name to $target"
  else
    echo "!! $name failed to apply to $target"; exit 1
  fi
done

REJECTS=$(find "$MC/src" "$MC/examples" "$MC/.pio/libdeps" -name '*.rej' 2>/dev/null | head -5)
[ -z "$REJECTS" ] || { echo "!! patch rejects present:"; echo "$REJECTS"; exit 1; }

# ---- 3. Host toolchain through the same "buildroot" board -------------------
# The board reads these, so pointing them at the host compiler is all it takes
# to turn the cross build into a native one.
export TARGET_CC="${CC:-gcc}"
export TARGET_CXX="${CXX:-g++}"
export TARGET_AR="${AR:-ar}"
export TARGET_AS="$TARGET_CC"
export TARGET_LD="$TARGET_CXX"
export TARGET_OBJCOPY="${OBJCOPY:-objcopy}"
export TARGET_RANLIB="${RANLIB:-ranlib}"
export TARGET_CFLAGS="-fPIC"
export TARGET_CXXFLAGS="-fPIC"
export TARGET_LDFLAGS=""
unset PKG_CONFIG_LIBDIR    # use the system pkg-config, not the Android prefix

( cd "$MC" && pio run -e linux-x86_64 )

BUILD_DIR="$MC/.pio/build/linux-x86_64"
OUT=""
for candidate in meshcored program firmware firmware.elf; do
  [ -f "$BUILD_DIR/$candidate" ] && { OUT="$BUILD_DIR/$candidate"; break; }
done
[ -n "$OUT" ] || { echo "!! no executable in $BUILD_DIR"; ls -la "$BUILD_DIR"; exit 1; }

# The same check the Android script makes. A patch that half-applies produces a
# binary that builds, links and does not work.
for s in "Using pre-opened CH341 fd" "Meshdroid MeshToad V3"; do
  LC_ALL=C grep -qaF -- "$s" "$OUT" || {
    echo "!! built binary has no \"$s\" string; a patch did not make it into this build."
    exit 1
  }
done

echo
echo ">> built $OUT"
file "$OUT"
echo
echo ">> data directory: $DATA_DIR"
mkdir -p "$DATA_DIR"
echo ">> run it with:"
echo "     $OUT --fsdir $DATA_DIR"

if [ "$RUN" = "--run" ]; then
  echo
  echo ">> starting. Ctrl-C to stop."
  exec "$OUT" --fsdir "$DATA_DIR"
fi
