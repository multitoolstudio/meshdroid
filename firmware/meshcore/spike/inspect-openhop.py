#!/usr/bin/env python3
"""
Prints the parts of the installed openhop_core API that the Meshtoad spike uses.

openHop's published examples cover a Waveshare SPI HAT rather than a CH341
bridge, and its documentation says the companion API changes quickly. Two things
are therefore worth reading out of the installed package before running
meshtoad-companion.py: the class that drives an SX1262 behind a CH341, and the
constructors for CompanionRadio and CompanionFrameServer.

Nothing here touches hardware. It is safe to run with no dongle attached.

Usage:
    python inspect-openhop.py
"""

import importlib
import inspect
import pkgutil
import sys


def heading(text):
    print()
    print(text)
    print("-" * len(text))


def signature_of(obj):
    try:
        return str(inspect.signature(obj))
    except (TypeError, ValueError):
        return "(signature unavailable)"


def describe(name, obj):
    print(f"  {name}{signature_of(obj)}")
    doc = inspect.getdoc(obj)
    if doc:
        first = doc.strip().splitlines()[0]
        print(f"      {first}")


def public_classes(module):
    for name, obj in vars(module).items():
        if name.startswith("_"):
            continue
        if inspect.isclass(obj) and obj.__module__.startswith("openhop_core"):
            yield name, obj


def main():
    try:
        import openhop_core
    except ImportError as e:
        print(f"openhop_core is not importable: {e}")
        print("Install it with: pip install 'openhop-core[all]'")
        return 1

    heading("Package")
    print(f"  version: {getattr(openhop_core, '__version__', 'unknown')}")
    print(f"  path:    {list(getattr(openhop_core, '__path__', []))}")

    heading("Top level names")
    for name in sorted(n for n in vars(openhop_core) if not n.startswith("_")):
        print(f"  {name}")

    # The radio backends live under openhop_core.hardware. The repeater selects
    # one with radio_type: sx1262_ch341, so the class behind that string is what
    # the spike needs. Every submodule is listed, then anything whose name
    # mentions ch341 is expanded.
    heading("openhop_core.hardware submodules")
    ch341_candidates = []
    try:
        hardware = importlib.import_module("openhop_core.hardware")
        for info in pkgutil.iter_modules(hardware.__path__):
            print(f"  {info.name}")
            if "ch341" in info.name.lower():
                ch341_candidates.append(f"openhop_core.hardware.{info.name}")
    except ImportError as e:
        print(f"  could not import openhop_core.hardware: {e}")

    heading("CH341 candidates")
    if not ch341_candidates:
        print("  No submodule mentions ch341. Look through the list above for a")
        print("  wrapper that takes a backend or transport argument instead.")
    for modname in ch341_candidates:
        print(f"  {modname}")
        try:
            module = importlib.import_module(modname)
        except ImportError as e:
            print(f"    import failed: {e}")
            continue
        for name, obj in public_classes(module):
            describe(name, obj)
            init = getattr(obj, "__init__", None)
            if init is not None:
                print(f"      __init__{signature_of(init)}")

    # The companion layer. CompanionRadio owns a radio, CompanionFrameServer
    # exposes a companion instance over the MeshCore TCP frame protocol.
    heading("openhop_core.companion")
    try:
        companion = importlib.import_module("openhop_core.companion")
    except ImportError as e:
        print(f"  could not import openhop_core.companion: {e}")
        return 1

    for name in sorted(n for n in vars(companion) if not n.startswith("_")):
        print(f"  {name}")

    for wanted in ("CompanionRadio", "CompanionBridge", "CompanionFrameServer",
                   "CompanionBase"):
        obj = getattr(companion, wanted, None)
        if obj is None:
            continue
        heading(wanted)
        print(f"  __init__{signature_of(obj.__init__)}")
        doc = inspect.getdoc(obj)
        if doc:
            for line in doc.strip().splitlines()[:12]:
                print(f"  {line}")
        print("  public methods:")
        for name, member in inspect.getmembers(obj, callable):
            if name.startswith("_"):
                continue
            print(f"    {name}{signature_of(member)}")

    # Identity persistence is application-owned in openHop, so the spike needs
    # to know how a LocalIdentity is serialised and restored.
    heading("LocalIdentity")
    identity_cls = getattr(openhop_core, "LocalIdentity", None)
    if identity_cls is None:
        print("  not exported from openhop_core")
    else:
        print(f"  __init__{signature_of(identity_cls.__init__)}")
        print("  public methods and attributes:")
        for name, member in inspect.getmembers(identity_cls):
            if name.startswith("_"):
                continue
            if callable(member):
                print(f"    {name}{signature_of(member)}")
            else:
                print(f"    {name}")

    print()
    print("Copy this output into the session. It settles the class names and")
    print("arguments the spike and the later C++ HAL both depend on.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
