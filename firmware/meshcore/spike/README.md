# MeshCore companion spike

A desktop proof of concept for Meshdroid 2.0.0, run on Fedora rather than on the
phone. It uses openHop, a Python reimplementation of MeshCore, to drive the
Meshtoad's CH341 bridge and expose a MeshCore companion over TCP.

Nothing in here ships in the app. It exists to settle three questions before any
C++ is written for `firmware/meshcore/`:

1. The Meshtoad's SX1262 answers under MeshCore, on the pin mapping Meshdroid
   already ships for meshtasticd.
2. A companion built on that radio joins a mesh, hears adverts and exchanges
   messages with the Companion and Repeater on the bench.
3. A stock MeshCore client attaches over the TCP frame protocol.

If those hold, the C++ work is a reimplementation of a known-good path. If the
first one fails, we find out in an afternoon instead of after a week of
cross-compilation.

## Why this hardware is worth trying

Meshdroid's `lora-usb-meshtoad-e22.yaml` preset and openHop's own CH341 preset
for an E22 module specify the same five pins:

| Function | Meshdroid preset | openHop CH341 preset |
| -------- | ---------------- | -------------------- |
| CS       | 0                | 0                    |
| RXen     | 1                | 1                    |
| Reset    | 2                | 2                    |
| Busy     | 4                | 4                    |
| IRQ      | 6                | 6                    |

Both also set the E22's TCXO on DIO3 and its RF switch on DIO2. openHop is
already driving this wiring in production, on the same CH341 (`1a86:5512`).

## Setup

Fedora, with the dongle unplugged for now:

```
sudo dnf install -y python3 python3-pip libusb1

cd firmware/meshcore/spike
python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install 'openhop-core[all]'
```

`libusb1` is the Fedora package name for the runtime openHop reaches the CH341
through. Without it, pyusb raises `NoBackendError`.

Plug the Meshtoad in and confirm the kernel sees it:

```
lsusb -d 1a86:5512
```

openHop opens the device through libusb rather than a kernel driver, so a
non-root user needs a udev rule:

```
sudo tee /etc/udev/rules.d/99-ch341.rules >/dev/null <<'EOF'
SUBSYSTEM=="usb", ATTRS{idVendor}=="1a86", ATTRS{idProduct}=="5512", MODE="0660", GROUP="plugdev", TAG+="uaccess"
EOF
sudo udevadm control --reload-rules
sudo udevadm trigger
```

Unplug and replug the dongle after that.

## Running it

**Read the API first.** openHop's documentation says the companion API changes
quickly, and its published examples cover a Waveshare SPI HAT rather than a
CH341. This prints what the installed version actually offers, and touches no
hardware:

```
python inspect-openhop.py
```

Paste that output into the session. It settles the class names and constructor
arguments that both the spike and the later C++ HAL depend on.

**Bring the radio up without transmitting.** This builds the radio object and
calls `begin()`, then exits:

```
python meshtoad-companion.py --dry-run
```

An antenna should be attached before anything past this point.

**Run the companion.**

```
python meshtoad-companion.py --name meshtoad-poc
```

Before that run, open `meshtoad-companion.py` and set `FREQUENCY_HZ`,
`BANDWIDTH_KHZ`, `SPREADING_FACTOR` and `CODING_RATE` to whatever the Companion
and Repeater on the bench are using. The defaults are the common US preset. A
companion on the wrong parameters behaves exactly like a companion whose radio
never came up, which would waste the whole test.

**Connect a client.** In the MeshCore Android app, add a node over Wi-Fi by IP
address, using the desktop's LAN address and port 5000.

## What success looks like

- `--dry-run` reports a `begin()` result and no exception.
- Adverts from the Repeater appear as `[advert]` lines within a minute or two.
- The MeshCore app connects on 5000, shows the node, and picks up its public key.
- A message sent from the app reaches the other Companion, and a reply comes
  back as a `[message]` line.

## What to bring back

Beyond pass or fail, these are what the C++ port needs:

- The full `inspect-openhop.py` output.
- The console output from a successful start, from the first line to the first
  advert. Those lines are the model for what `meshcored` should print, and
  `DaemonState.parse` will eventually match patterns like them.
- The SNR and RSSI values on received packets, as a baseline to compare the C++
  build against. A HAL with a subtly wrong SPI mode often receives, just badly.
- Whether the identity file was written, and by which method. openHop leaves
  persistence to the application, so this is the behaviour Meshdroid's
  `files/meshcore/` layout has to reproduce.
- Anything that had to be changed in `meshtoad-companion.py` to make it run.

## Limitations

This has never been executed. It is written against openHop's documentation and
its published repeater configuration, not against source I have read, so the
class names and constructor arguments are resolved at run time and reported
rather than assumed. Expect to adjust it after the first `inspect-openhop.py`
run.

The script implements none of openHop's persistence hooks. Contacts, channels
and queues live in memory and are lost on exit. Only the identity key is saved.

openHop is MIT licensed, as is MeshCore itself, so reading it and porting its
behaviour into Meshdroid's GPL-3 tree is fine in that direction. Attribution
belongs in `firmware/meshcore/` when the C++ HAL lands.
