#!/usr/bin/env python3
"""
Runs a MeshCore companion on a Nullhop Meshtoad, on a Linux desktop.

This is the proof of concept for Meshdroid 2.0.0. It answers three questions in
one run, before any C++ is written:

  1. Does the CH341 bridge on the Meshtoad drive its SX1262 under MeshCore, with
     the pin mapping Meshdroid already ships for meshtasticd?
  2. Does a MeshCore companion built on that radio join a mesh, hear adverts and
     exchange messages?
  3. Does a stock MeshCore client attach to it over the TCP frame protocol?

If all three hold, the remaining work for 2.0.0 is a C++ reimplementation of a
path that is known to work on this hardware, rather than an open question.

Everything here is a spike. It keeps no durable state beyond the identity file,
implements none of openHop's persistence hooks, and is not meant to run
unattended.

Usage:
    python meshtoad-companion.py
    python meshtoad-companion.py --bind 0.0.0.0 --port 5000 --name meshtoad-poc
    python meshtoad-companion.py --dry-run        # build the radio, do not transmit

Run inspect-openhop.py first. It prints the class names and constructor
arguments that this script resolves at run time.
"""

import argparse
import asyncio
import datetime
import importlib
import inspect
import pkgutil
import signal
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Meshtoad hardware
#
# Taken from app/src/main/assets/presets/lora-usb-meshtoad-e22.yaml, which came
# from meshtastic/firmware bin/config.d. These are CH341 GPIO numbers, not
# Raspberry Pi BCM numbers. openHop's own CH341 preset for an E22 module uses
# the same five values, which is the reason this spike is worth running at all.
# ---------------------------------------------------------------------------
MESHTOAD_PINS = {
    "cs_pin": 0,
    "rxen_pin": 1,
    "reset_pin": 2,
    "busy_pin": 4,
    "irq_pin": 6,
}

# The E22 carries a TCXO on DIO3 and its RF switch on DIO2.
MESHTOAD_FLAGS = {
    "use_dio2_rf": True,
    "use_dio3_tcxo": True,
}

MESHTOAD_USB = {
    "usb_vid": 0x1A86,
    "usb_pid": 0x5512,
}

# ---------------------------------------------------------------------------
# Radio settings
#
# These must match the mesh being joined. The values below are the common US
# MeshCore preset. Change them to whatever the Repeater and Companion already on
# the bench are using, because a companion on the wrong parameters is
# indistinguishable from a companion whose radio never came up.
# ---------------------------------------------------------------------------
FREQUENCY_HZ = 910_525_000
BANDWIDTH_KHZ = 62.5
SPREADING_FACTOR = 10
CODING_RATE = 5
TX_POWER_DBM = 22

IDENTITY_FILE = Path(__file__).with_name("meshtoad-identity.key")


def log(message):
    stamp = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
    print(f"{stamp}  {message}", flush=True)


# ---------------------------------------------------------------------------
# Resolving openHop's API
#
# openHop's documentation says the companion API evolves quickly, so this script
# looks up the classes it needs rather than importing fixed names, and passes
# only the keyword arguments a constructor actually accepts. A dropped argument
# is reported instead of being swallowed, since a silently ignored pin number
# would look exactly like a wiring fault.
# ---------------------------------------------------------------------------
def find_ch341_radio_class():
    """Returns the openhop_core class that drives an SX1262 behind a CH341."""
    hardware = importlib.import_module("openhop_core.hardware")
    for info in pkgutil.iter_modules(hardware.__path__):
        if "ch341" not in info.name.lower():
            continue
        module = importlib.import_module(f"openhop_core.hardware.{info.name}")
        for name, obj in vars(module).items():
            if name.startswith("_") or not inspect.isclass(obj):
                continue
            if "ch341" in name.lower() or "ch341" in info.name.lower():
                log(f"radio class: openhop_core.hardware.{info.name}.{name}")
                return obj
    raise RuntimeError(
        "No CH341 radio class found under openhop_core.hardware. "
        "Run inspect-openhop.py and check whether the SPI wrapper takes a "
        "backend argument instead of having its own module."
    )


def call_with_supported(factory, **kwargs):
    """Calls factory with the subset of kwargs its signature accepts."""
    try:
        params = inspect.signature(factory).parameters
    except (TypeError, ValueError):
        return factory(**kwargs)

    takes_everything = any(
        p.kind is inspect.Parameter.VAR_KEYWORD for p in params.values()
    )
    if takes_everything:
        return factory(**kwargs)

    accepted = {k: v for k, v in kwargs.items() if k in params}
    dropped = sorted(set(kwargs) - set(accepted))
    if dropped:
        log(f"note: {factory.__name__} does not accept {', '.join(dropped)}")
    missing = [
        name
        for name, p in params.items()
        if name != "self"
        and p.default is inspect.Parameter.empty
        and p.kind
        in (inspect.Parameter.POSITIONAL_OR_KEYWORD, inspect.Parameter.KEYWORD_ONLY)
        and name not in accepted
    ]
    if missing:
        log(f"note: {factory.__name__} still wants {', '.join(missing)}")
    return factory(**accepted)


# ---------------------------------------------------------------------------
# Identity
#
# openHop leaves persistence to the application. Without saving the key, every
# run appears on the mesh as a new node and the phone app collects a fresh
# contact each time. Best effort: whichever serialisation the installed
# LocalIdentity offers is used, and the run continues with an ephemeral identity
# when none is found.
# ---------------------------------------------------------------------------
def load_or_create_identity(identity_cls):
    if IDENTITY_FILE.exists():
        raw = IDENTITY_FILE.read_bytes()
        for loader in ("from_bytes", "from_private_key", "load"):
            fn = getattr(identity_cls, loader, None)
            if callable(fn):
                try:
                    identity = fn(raw)
                    log(f"identity loaded from {IDENTITY_FILE.name} via {loader}()")
                    return identity
                except Exception as e:
                    log(f"identity {loader}() failed: {e}")
        try:
            identity = identity_cls(raw)
            log(f"identity loaded from {IDENTITY_FILE.name} via the constructor")
            return identity
        except Exception as e:
            log(f"identity constructor with saved key failed: {e}")

    identity = identity_cls()
    for dumper in ("to_bytes", "private_key_bytes", "private_key"):
        value = getattr(identity, dumper, None)
        raw = value() if callable(value) else value
        if isinstance(raw, (bytes, bytearray)):
            IDENTITY_FILE.write_bytes(bytes(raw))
            IDENTITY_FILE.chmod(0o600)
            log(f"new identity saved to {IDENTITY_FILE.name} via {dumper}")
            return identity
    log("new identity created, and it could not be saved: this run is ephemeral")
    return identity


def public_key_of(identity):
    for attr in ("public_key", "pub_key", "public_key_hex"):
        value = getattr(identity, attr, None)
        raw = value() if callable(value) else value
        if isinstance(raw, (bytes, bytearray)):
            return bytes(raw).hex()
        if isinstance(raw, str):
            return raw
    return "unknown"


# ---------------------------------------------------------------------------
# Event logging
#
# Every callback the companion offers is attached and printed. This doubles as
# research for Meshdroid: the shape of these events is what MeshCoreLink will
# eventually have to turn into MeshEvent, and the log format is the closest
# thing to the meshtasticd output DaemonState already parses.
# ---------------------------------------------------------------------------
def attach_callbacks(companion):
    registrars = [
        ("message", "on_message_event"),
        ("advert", "on_advert_event"),
        ("channel", "on_channel_event"),
        ("ack", "on_ack_event"),
        ("response", "on_response_event"),
        ("contact", "on_contact_event"),
    ]
    attached = []
    for label, method_name in registrars:
        register = getattr(companion, method_name, None)
        if not callable(register):
            continue

        def handler(*args, _label=label, **kwargs):
            payload = args[0] if len(args) == 1 and not kwargs else (args, kwargs)
            log(f"[{_label}] {payload!r}")

        try:
            register(handler)
            attached.append(label)
        except Exception as e:
            log(f"could not attach {method_name}: {e}")
    log(f"callbacks attached: {', '.join(attached) if attached else 'none'}")


async def run(args):
    try:
        import openhop_core
        from openhop_core import LocalIdentity
        from openhop_core.companion import CompanionRadio
    except ImportError as e:
        log(f"openhop_core is not importable: {e}")
        log("Install it with: pip install 'openhop-core[all]'")
        return 1

    log(f"openhop_core {getattr(openhop_core, '__version__', 'unknown')}")

    radio_cls = find_ch341_radio_class()
    radio = call_with_supported(
        radio_cls,
        **MESHTOAD_PINS,
        **MESHTOAD_FLAGS,
        **MESHTOAD_USB,
        frequency=FREQUENCY_HZ,
        bandwidth=BANDWIDTH_KHZ,
        spreading_factor=SPREADING_FACTOR,
        coding_rate=CODING_RATE,
        tx_power=TX_POWER_DBM,
    )
    log(f"radio built: {radio!r}")

    begin = getattr(radio, "begin", None)
    if callable(begin):
        result = begin()
        if inspect.isawaitable(result):
            result = await result
        log(f"radio begin() returned {result!r}")
    log(
        f"radio configured for {FREQUENCY_HZ / 1e6:.3f} MHz, "
        f"BW {BANDWIDTH_KHZ} kHz, SF {SPREADING_FACTOR}, CR {CODING_RATE}, "
        f"{TX_POWER_DBM} dBm"
    )

    if args.dry_run:
        log("dry run: the radio came up and nothing was transmitted")
        return 0

    identity = load_or_create_identity(LocalIdentity)
    log(f"public key: {public_key_of(identity)}")

    companion = call_with_supported(
        CompanionRadio,
        radio=radio,
        identity=identity,
        node_name=args.name,
    )
    attach_callbacks(companion)

    await companion.start()
    log("companion started")

    server = None
    try:
        from openhop_core.companion import CompanionFrameServer

        server = call_with_supported(
            CompanionFrameServer,
            companion=companion,
            host=args.bind,
            port=args.port,
        )
        start = getattr(server, "start", None)
        if callable(start):
            result = start()
            if inspect.isawaitable(result):
                await result
        log(f"frame server listening on {args.bind}:{args.port}")
        log("point a MeshCore client at this host and port over Wi-Fi")
    except ImportError:
        log("CompanionFrameServer is not available; running without a TCP server")
    except Exception as e:
        log(f"frame server failed to start: {e}")

    try:
        await companion.advertise(flood=True)
        log("flood advert sent")
    except Exception as e:
        log(f"advertise failed: {e}")

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    log("running. Ctrl-C to stop.")
    await stop.wait()

    log("stopping")
    if server is not None:
        stopper = getattr(server, "stop", None)
        if callable(stopper):
            result = stopper()
            if inspect.isawaitable(result):
                await result
    await companion.stop()
    log("stopped")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="0.0.0.0",
                        help="address the frame server listens on")
    parser.add_argument("--port", type=int, default=5000,
                        help="TCP port for MeshCore clients")
    parser.add_argument("--name", default="meshtoad-poc",
                        help="node name advertised to the mesh")
    parser.add_argument("--dry-run", action="store_true",
                        help="bring the radio up and exit without transmitting")
    args = parser.parse_args()

    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 0
    except Exception as e:
        log(f"failed: {type(e).__name__}: {e}")
        raise


if __name__ == "__main__":
    sys.exit(main())
