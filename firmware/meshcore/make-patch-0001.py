#!/usr/bin/env python3
"""
Applies the portduino filesystem edits to a MeshCore checkout and writes them
out as patches/0001-portduino-filesystem.patch.

MeshCore selects its filesystem with #if chains covering ESP32, RP2040, NRF52
and STM32. Each chain ends in an #error rather than a default, so a portduino
build cannot compile until a branch is added. Three sites need one:

  src/helpers/IdentityStore.h          the FILESYSTEM selection
  examples/companion_radio/DataStore.cpp   creating the identity directory
  examples/companion_radio/main.cpp    the DataStore declaration
  examples/companion_radio/main.cpp    the filesystem bring-up in setup()
  examples/companion_radio/DataStore.cpp   formatFileSystem()
  examples/companion_radio/main.cpp    declaring the TCP interface
  examples/companion_radio/main.cpp    registering the TCP interface

An eighth edit adds a marker string to DataStore.cpp. It changes no behaviour
and exists so the build script can prove from the finished binary that this
patch reached the build.

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

# --- DataStore.cpp: a marker the build can be checked against ---------------
# Both things this patch adds fail silently when the patch does not apply. A
# binary with no identity directory builds, links, runs, receives perfectly and
# cannot sign an advert; a binary with no TCP interface builds and listens on
# nothing. Neither failure looks like a build problem, and both have cost days
# here. This string is what the build script greps the finished binary for
# before it installs anything.
#
# __attribute__((used)) keeps it past --gc-sections. Bump the revision whenever
# the patch changes, so a stale patch is distinguishable from a missing one.
MARKER_ANCHOR = """#include <Arduino.h>
#include "DataStore.h"
"""

MARKER_NEW = """#include <Arduino.h>
#include "DataStore.h"

#if defined(PORTDUINO_PLATFORM)
// Meshdroid: proof that patch 0001 reached this build. See make-patch-0001.py.
__attribute__((used)) static const char MESHDROID_PATCH_0001[] = "MESHDROID_PATCH_0001_R1";
#endif
"""

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

# --- DataStore.cpp: create the identity directory ---------------------------
# identity_store.begin() is the only thing that creates /identity, and upstream
# calls it on RP2040 only: ESP32 uses SPIFFS, which is flat and needs no
# directory, and NRF52 and STM32 pass "" as the directory so there is nothing to
# make. On portduino it is a real directory on a real filesystem, and without
# this every identity write fails with
#   fopen(<root>/identity/_main.id) failed
#   IdentityStore::save() write - Err
# leaving the node with no private key, unable to sign an advert or complete an
# ACK. It receives perfectly and transmits nothing.
IDDIR_ANCHOR = """#if defined(RP2040_PLATFORM)
  identity_store.begin();
#endif
"""

IDDIR_NEW = """#if defined(RP2040_PLATFORM) || defined(PORTDUINO_PLATFORM)
  // Meshdroid: portduino's VFS is a real directory tree, so /identity has to be
  // created before the first identity write, exactly as on RP2040's LittleFS.
  identity_store.begin();
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

# --- main.cpp: the TCP companion interface ----------------------------------
# MeshCore's WiFi companion is ESP32-only, and main.cpp #errors for any other
# platform with WIFI_SSID set. SocketSerialInterface implements the same
# BaseSerialInterface over a POSIX socket, with the identical framing, so it
# registers the way BLE and USB do.
#
# InterfaceType::WiFi rather than a new enum value: MeshCore clients already
# treat a TCP companion as the WiFi one, and adding an enumerator would mean
# patching MultiSerialInterface.h too.
IFACE_DECL_ANCHOR = """MultiSerialInterface interface_manager;
"""

IFACE_DECL_NEW = """MultiSerialInterface interface_manager;

#if defined(MESHCORE_TCP_INTERFACE)
  // Meshdroid: the companion protocol over TCP. Header-only and living in the
  // variant directory, so it survives a MeshCore version bump untouched.
  #include <SocketSerialInterface.h>
  SocketSerialInterface tcp_interface;
#endif
"""

IFACE_REG_ANCHOR = """// add bluetooth interface
#if defined(BLE_PIN_CODE)
"""

IFACE_REG_NEW = """// Meshdroid: add the TCP companion interface
#if defined(MESHCORE_TCP_INTERFACE)
  #ifndef TCP_BIND_ADDR
    #define TCP_BIND_ADDR "0.0.0.0"
  #endif
  tcp_interface.begin(TCP_BIND_ADDR, TCP_PORT);
  interface_manager.addInterface(InterfaceType::WiFi, &tcp_interface);
#endif

// add bluetooth interface
#if defined(BLE_PIN_CODE)
"""

EDITS = [
    ("examples/companion_radio/DataStore.cpp", MARKER_ANCHOR, MARKER_NEW, "patch marker"),
    ("src/helpers/IdentityStore.h", IDSTORE_ANCHOR, IDSTORE_NEW, "FILESYSTEM selection"),
    ("examples/companion_radio/main.cpp", DECL_ANCHOR, DECL_NEW, "DataStore declaration"),
    ("examples/companion_radio/main.cpp", SETUP_ANCHOR, SETUP_NEW, "setup() filesystem"),
    ("examples/companion_radio/DataStore.cpp", IDDIR_ANCHOR, IDDIR_NEW, "identity directory"),
    ("examples/companion_radio/DataStore.cpp", FORMAT_ANCHOR, FORMAT_NEW, "formatFileSystem()"),
    ("examples/companion_radio/main.cpp", IFACE_DECL_ANCHOR, IFACE_DECL_NEW, "TCP interface declaration"),
    ("examples/companion_radio/main.cpp", IFACE_REG_ANCHOR, IFACE_REG_NEW, "TCP interface registration"),
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
