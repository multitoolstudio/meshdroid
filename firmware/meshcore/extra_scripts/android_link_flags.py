#!/usr/bin/env python3
# Link-stage adjustments for the meshcored Android env. PlatformIO's build_flags
# reach the compiler; flags meant only for the linker, and edits to the library
# list, go here. Same pattern as firmware/extra_scripts/android_link_flags.py,
# minus the libi2c handling: MeshCore has nothing on the I2C bus, so there is no
# static libi2c to order after libFrameworkArduino.a.
Import("env")  # noqa: F821

if env["PIOENV"].startswith("android-"):
    env.Append(
        LINKFLAGS=[
            # Android 15 devices may run with 16 KB pages. Aligning the ELF segments
            # keeps the binary loadable on both 4 KB and 16 KB kernels.
            "-Wl,-z,max-page-size=16384",
            "-Wl,-z,common-page-size=16384",
            # Android requires position-independent executables.
            "-fPIE",
            "-pie",
            # libc++_shared.so is packaged next to the binary in nativeLibraryDir.
            # "$$" escapes SCons variable expansion; the linker receives a literal
            # $ORIGIN. The "=" form stops an empty value from consuming the next
            # argument on the link line.
            "-Wl,-rpath=$$ORIGIN",
            "-Wl,--gc-sections",
        ]
    )
    # Libraries the base env or the framework names that bionic does not have.
    # pthreads live inside libc, filesystem is in libc++, and there is no BlueZ
    # or libgpiod on Android.
    for lib in ("pthread", "stdc++fs", "bluetooth", "gpiod"):
        if lib in env.get("LIBS", []):
            env["LIBS"].remove(lib)
