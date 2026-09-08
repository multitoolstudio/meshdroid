#!/usr/bin/env python3
# trunk-ignore-all(ruff/F821)
# Link-stage adjustments for the Android env. PlatformIO's build_flags reach the
# compiler; flags meant only for the linker, and edits to the library list, go
# here. Same pattern as upstream's windows_link_flags.py and wasm_link_flags.py.
Import("env")

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
    # libFrameworkArduino.a, which the framework builder prepends to LIBS,
    # references i2c_smbus_*. The static libi2c must come after it on the link line.
    env.Append(LIBS=["i2c"])
    # Remove libraries the base env names that do not exist on Android.
    for lib in ("pthread", "stdc++fs", "bluetooth", "gpiod"):
        if lib in env.get("LIBS", []):
            env["LIBS"].remove(lib)
