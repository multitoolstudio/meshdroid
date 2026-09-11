#!/usr/bin/env python3
"""
Applies the portduino filesystem edits to a MeshCore checkout and writes them
out as patches/0001-portduino-filesystem.patch.

MeshCore selects its filesystem with #if chains covering ESP32, RP2040, NRF52
and STM32. Each chain ends in an #error rather than a default, so a portduino
build cannot compile until a branch is added. Three sites need one:

  src/helpers/IdentityStore.h          the FILESYSTEM selection
  examples/companion_radio/main.cpp    the DataStore declaration
  examples/companion_radio/main.cpp    the filesystem bring-up in setup()
  examples/companion_radio/DataStore.cpp   formatFileSystem()

This edits the checkout, runs git diff to produce the patch, then restores the
checkout so build-meshcored-android.sh can apply the patch itself the way it
applies every other one. Running it twice is harmless.

Every edit anchors on an exact string and asserts it appears exactly once, so a
MeshCore version bump makes this fail loudly instead of corrupting a file.

Usage:
    ./make-patch-0001.py /home/john/meshcore-firmware
"""

import subprocess
import sys
from pathlib import Path

PATCH_NAME = "0001-portduino-filesystem.patch"

# --- IdentityStore.h: select the filesystem ---------------------------------
# This branch is why there is no -include for MeshdroidFS.h. Force-including it
# put <FS.h>, and through it <Arduino.h>, into every translation unit in the
# build, including framework-portduino's own sources. That made
# simulated/SimHardwareI2C.cpp see linux/LinuxHardwareI2C.h, and the two both
# define arduino::Wire. Adding a branch here is what upstream does for every
# other platform, and it only reaches files that actually need a filesystem.
#
# MeshdroidFS.h defines FILESYSTEM itself, matching the pattern of the branches
# above it.
IDSTORE_ANCHOR = """  using namespace Adafruit_LittleFS_Namespace;
#endif
"""

IDSTORE_NEW = """  using namespace Adafruit_LittleFS_Namespace;
#elif defined(PORTDUINO_PLATFORM)
  // Meshdroid: MeshdroidFS wraps framework-portduino's fs::FS, adding the
  // three-argument open() and the format() that MeshCore expects.
  #include <MeshdroidFS.h>
#endif
"""

# --- main.cpp: declare the DataStore -----------------------------------------
# Appended after the ESP32 branch, before the #endif. PortduinoFS.h is
# force-included by the env, so InternalFS is already declared and no #include
# is needed here.
DECL_ANCHOR = """  #include <SPIFFS.h>
  DataStore store(SPIFFS, rtc_clock);
"""

DECL_NEW = """  #include <SPIFFS.h>
  DataStore store(SPIFFS, rtc_clock);
#elif defined(PORTDUINO_PLATFORM)
  // Meshdroid: AppFS wraps portduino's global PortduinoFS, adding the
  // three-argument open() and the format() that MeshCore expects. It is
  // declared in MeshdroidFS.h, which the build force-includes ahead of
  // everything so that FILESYSTEM is defined before IdentityStore.h's #if chain
  // is reached.
  DataStore store(AppFS, rtc_clock);
"""

# --- main.cpp: bring the filesystem up in setup() ----------------------------
SETUP_ANCHOR = """#else
  #error "need to define filesystem"
#endif
"""

SETUP_NEW = """#elif defined(PORTDUINO_PLATFORM)
  // Meshdroid: no filesystem bring-up. portduino's main() mounts the VFS root
  // and creates the directory before it calls setup().
  store.begin();
  the_mesh.begin(
    #ifdef DISPLAY_CLASS
        disp != NULL
    #else
        false
    #endif
  );
#else
  #error "need to define filesystem"
#endif
"""

# --- DataStore.cpp: formatFileSystem() ---------------------------------------
# There is no partition to reformat, so delete the files the firmware owns.
# Same outcome as a factory reset on an embedded board.
FORMAT_ANCHOR = """#else
  #error "need to implement format()"
#endif
"""

FORMAT_NEW = """#elif defined(PORTDUINO_PLATFORM)
  // Meshdroid: the data directory belongs to the app, so there is nothing to
  // reformat. Deleting every file at the root is the equivalent outcome:
  // identity, prefs, contacts and channels all go, which is what a factory
  // reset means on an embedded board. Peers holding this node as a contact will
  // see a stranger afterwards, and that is intended here.
  return _fs->removeAll();
#else
  #error "need to implement format()"
#endif
"""

EDITS = [
    ("src/helpers/IdentityStore.h", IDSTORE_ANCHOR, IDSTORE_NEW, "FILESYSTEM selection"),
    ("examples/companion_radio/main.cpp", DECL_ANCHOR, DECL_NEW, "DataStore declaration"),
    ("examples/companion_radio/main.cpp", SETUP_ANCHOR, SETUP_NEW, "setup() filesystem"),
    ("examples/companion_radio/DataStore.cpp", FORMAT_ANCHOR, FORMAT_NEW, "formatFileSystem()"),
]


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2

    mc = Path(sys.argv[1]).resolve()
    if not (mc / ".git").exists():
        print(f"{mc} is not a git checkout")
        return 1

    here = Path(__file__).resolve().parent
    patches = here / "patches"
    patches.mkdir(exist_ok=True)

    dirty = subprocess.run(["git", "-C", str(mc), "status", "--porcelain",
                            "examples/", "src/"], capture_output=True, text=True).stdout
    if dirty.strip():
        print("The MeshCore checkout has uncommitted changes under examples/ or src/:")
        print(dirty)
        print("Run: git -C %s checkout -- examples/ src/" % mc)
        return 1

    for rel, anchor, replacement, label in EDITS:
        path = mc / rel
        text = path.read_text()
        if replacement in text:
            print(f"   {label}: already applied")
            continue
        count = text.count(anchor)
        if count != 1:
            print(f"!! {label}: anchor found {count} times in {rel}, expected once.")
            print("   MeshCore has probably changed. Not editing anything.")
            subprocess.run(["git", "-C", str(mc), "checkout", "--", "examples/", "src/"])
            return 1
        path.write_text(text.replace(anchor, replacement))
        print(f">> {label}: edited {rel}")

    diff = subprocess.run(["git", "-C", str(mc), "diff", "--", "examples/", "src/"],
                          capture_output=True, text=True).stdout
    if not diff.strip():
        print("!! git diff is empty; nothing was changed")
        return 1

    out = patches / PATCH_NAME
    out.write_text(diff)
    print(f">> wrote {out} ({len(diff.splitlines())} lines)")

    # Restore the checkout. build-meshcored-android.sh applies the patch itself,
    # and leaving the tree dirty would make it look already-applied on a clean
    # build and then fail the reverse-check on the next one.
    subprocess.run(["git", "-C", str(mc), "checkout", "--", "examples/", "src/"],
                   check=True)
    print(">> checkout restored; the build script will apply the patch")
    return 0


if __name__ == "__main__":
    sys.exit(main())
