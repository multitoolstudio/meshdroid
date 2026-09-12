# Meshdroid

*by [Multitool Studio](https://multitool.studio)*

Meshdroid runs a real mesh daemon on an Android phone and drives a USB LoRa
dongle through USB-C OTG. The phone is the node. No root, no external computer,
no separate battery. The phone app for whichever protocol you are running
connects to it over a local TCP connection exactly as it would to a Raspberry Pi
node.

Two protocols, one at a time:

- **Meshtastic**, through `meshtasticd`, the Meshtastic Linux daemon. The
  official Meshtastic app connects on port 4403.
- **MeshCore**, through `meshcored`, built from MeshCore's `companion_radio`
  firmware. MeshCore clients connect on port 5000.

One radio and one USB descriptor means exactly one daemon runs at a time.
Switching protocol stops one and starts the other. Each keeps its own identity,
contacts and settings, so switching back finds the node as you left it.

Works with CH341-based USB radios: NULLHOP MeshToad (all revisions), MeshStick,
Pinedio USB, uMesh USB, RAK19714, and anything else `meshtasticd` supports with
`spidev: ch341`. The MeshCore daemon is built for the MeshToad V3 pinmap
specifically; see [Protocols](#protocols).

Meshdroid is an independent project and is not affiliated with or endorsed by
Meshtastic LLC or the MeshCore project. "Meshtastic" is a registered trademark
of Meshtastic LLC.

```
+- Android phone ---------------------------------------------------------+
|  Meshtastic app --TCP 127.0.0.1:4403--> meshtasticd  (arm64, bionic)    |
|  MeshCore app   --TCP        :5000----> meshcored    (arm64, bionic)    |
|                                              | libusb (wrapped fd)      |
|  Meshdroid ---- USB Host API ---- fd -------> |                          |
|    (foreground service, wake lock, UI)       v                          |
+------------------------------------------ USB-C OTG --> CH341 -SPI-> SX1262
```

## Contents

- [Requirements](#requirements)
- [Install](#install)
- [First run](#first-run)
- [Protocols](#protocols)
- [The app](#the-app)
- [Where the files live](#where-the-files-live)
- [Power, battery and limits](#power-battery-and-limits)
- [Troubleshooting](#troubleshooting)
- [Building from source](#building-from-source)
- [License](#license)
- [AI Disclosure](#ai-disclosure)

## Requirements

- An arm64 Android phone running Android 9 or newer. Anything sold in the
  last several years qualifies.
- A CH341-based LoRa dongle with an antenna attached. Never power a LoRa radio
  without an antenna.
- A USB-C OTG adapter, or a USB-C to USB-C cable. Most C-to-C cables negotiate
  host mode on their own.
- The phone app for the protocol you are running: the official Meshtastic app,
  or a MeshCore client such as MeshCore Open.

## Install

1. Download `meshdroid-<version>.apk` from the Releases page and open it on
   the phone. Android asks once to allow installs from your browser or file
   manager.
2. Settings > Apps > Meshdroid > Battery > **Unrestricted**. Without this,
   Android 14 and 15 suspend the daemon after a while in the background.

## First run

1. Plug the dongle in. Android shows "Open Meshdroid when this USB device is
   connected?". Tick *Always* and accept. The node starts on its own. If the
   prompt does not appear, open Meshdroid and tap **Start node**.
2. Choose the protocol under **Protocol** in the Node tab if you do not want
   the default, which is Meshtastic.
3. Under Meshtastic, Meshdroid asks for your **LoRa region**. Pick the one you
   are in. Firmware 2.8 and later will not create the node's identity keys,
   transmit, or write its saved config until a region is set, so this step is
   required before the node does anything useful. MeshCore does not ask: its
   radio settings are compiled in and the rest are set from the MeshCore app.
4. Watch the Node tab. The status goes *Starting* then *Node running* and,
   under Meshtastic, fills in the node ID, firmware version and the dongle's
   serial number.
5. Connect the phone app:
   - Meshtastic: **+** > **Network** > address `127.0.0.1`, port `4403`.
   - MeshCore: add a WiFi or TCP companion at `127.0.0.1` port `5000`.

The default Meshtastic configuration is the MeshToad pinmap with transmit power
capped at 10 dBm. See [Power, battery and limits](#power-battery-and-limits)
before raising it.

## Protocols

The **Protocol** card in the Node tab switches between them. Switching stops the
running node first and warns that anything connected will drop.

What differs:

| | Meshtastic | MeshCore |
|---|---|---|
| Daemon | `meshtasticd` | `meshcored` |
| Client port | 4403 | 5000 |
| Radio settings | `config.yaml`, editable in the Config tab | compiled in, then edited from the MeshCore app |
| LoRa region | set in the app | not applicable, the preset is fixed at build time |
| Several apps at once | yes, through the sharing proxy | no, a new connection evicts the old one |
| LAN access | switchable | always reachable on the LAN |
| Mesh and BBS tabs | yes | no, both speak Meshtastic's protocol |

The tabs that do not apply to the running protocol are hidden rather than shown
and left to fail.

The MeshCore daemon ships configured for the United States and Canada preset:
910.525 MHz, 62.5 kHz bandwidth, spreading factor 7, coding rate 4/5. Frequency,
bandwidth and spreading factor have to match the rest of your mesh exactly or the
radios cannot hear each other at all. Changing them means rebuilding the daemon;
see [docs/BUILDING.md](docs/BUILDING.md).

## The app

### Node

- **Status card.** Node state, and under Meshtastic the node ID, firmware
  version and radio serial. Start, Stop and Restart act on the daemon process
  only; the app stays open.
- **Protocol.** Which daemon to run. Switching stops the running one first.
- **LoRa region** (Meshtastic only). Writes `Lora: Region:` into `config.yaml`
  and restarts the node. The daemon applies it only while the node has no region
  yet, so a region changed later in the Meshtastic app takes precedence.
- **Allow LAN access** (Meshtastic only). Off by default: the API listens on
  `127.0.0.1` and only this phone can reach it. On: it listens on all interfaces
  and any device on the same Wi-Fi can connect to, read from and reconfigure the
  node. Takes effect on the next start. `meshcored` takes its bind address at
  compile time and always listens on every interface, so the switch is not shown
  for it and the Connections card says so.
- **Report phone battery to the mesh.** On by default. The node publishes the
  phone's charge level, and whether it is charging, the way a battery-powered
  node does. Off means the node reports itself as USB powered, which is what a
  daemon on a Linux box does. Applies on the next start. See
  [Troubleshooting](#troubleshooting) for the state of this under MeshCore.
- **Start when radio is plugged in.** The USB auto-launch.
- **Back up / Restore.** Moves the node's files as a zip through the system file
  picker: Downloads, Drive, a USB stick, wherever. A Meshtastic backup is
  `config.yaml` plus the node database, channels and keys. A MeshCore backup is
  the whole data directory, which is identity, contacts, channels and settings.
  Either zip holds the node's private key in the clear, so put it somewhere you
  would put a key. The filename names the protocol, and a restore refuses a
  backup from the other one rather than replacing a working node with files its
  daemon cannot read. Restore asks first, then stops the node, waits for it to
  exit, and replaces the files with what the backup contains.
- **Reset identity.** Under Meshtastic, deletes the node database and keys and
  keeps `config.yaml`. Under MeshCore, deletes the whole data directory, so
  peers holding this node as a contact will see a stranger. The node gets a new
  keypair on its next start. Only available while the node is stopped.
- **Close Meshdroid.** Stops the node and quits the app.

The bottom of the tab shows the app version and the upstream the running
protocol's daemon was built from.

### Config

Meshtastic only, because MeshCore has no config file: its settings are a struct
the client edits over the companion protocol.

The `config.yaml` editor, same format as `/etc/meshtasticd/config.yaml` on
Linux. **Presets** loads a known pinmap: MeshToad, MeshStick, Pinedio USB,
uMesh (SX1262 and SX1268), RAK19714. **Import** and **Export** work with any
YAML from a Linux install. Changes apply after a restart; **Save & restart**
does both.

### Mesh

Meshtastic only. A direct connection to the node, the same one the Meshtastic app
makes. **Connect** shows what the node is hearing as it arrives: text messages,
node sightings and other traffic, with a box to broadcast a message.

**Phone sensors** lists the environment sensors this phone has, with a live
reading for each. Barometric pressure and ambient light can be published to the
mesh as environment telemetry, off by default, sent every 30 minutes while the
link is connected.

With the proxy on, connecting here leaves the Meshtastic app connected too. With
it off, `meshtasticd` accepts one client and connecting here disconnects that app.

### BBS

Meshtastic only. A bulletin board and mail drop that answers commands sent to
this node. Built in: `help`, `about`, `ping` and `test` (which report snr and
rssi when heard directly, or the hop count when relayed), `board list` for
numbered titles, `board list <n>` to read one in full, `board post @title #body`
to leave one, `mail` to collect yours and `mail <to> <text>` to leave one. Mail
takes a name, a node id, or the `@!433d8ac0` mention the Meshtastic app inserts.

Operators can add admin node ids in the tab, which unlocks `admin status`,
`admin clear board`, `admin clear mail` and `admin delete <n>` over the mesh.
Those are accepted only in an encrypted direct message, because a node id in a
packet is whatever the sender chose to put there. The tab also clears the board
and mail directly.

Direct messages to the node are treated as commands. Channel messages count only
when they start with `!`, so ordinary conversation is left alone. Replies are
spaced out and limited per sender, because the channel is shared.

Commands can be added by importing a Lua file from the settings screen. Imported
modules run sandboxed, with no file, network or radio access, under an
instruction and time limit, and each is off until you turn it on. See
[docs/examples](docs/examples/) for a working module and what the API looks like.

The tab carries a console that runs a command locally without transmitting, which
is the quickest way to see what a command does.

The BBS stays off under MeshCore rather than running against a daemon that does
not speak its protocol. MeshCore support is a later release.

### Logs

The live daemon log for whichever daemon is running. **Export** writes it to a
file through the file picker, **Clear** empties the view, **Follow** keeps the
newest line in view, and **Verbose** adds `meshtasticd`'s `-v` output starting
from the next start. The filter box matches case-insensitively. The Verbose chip
is not shown under MeshCore, whose verbosity is a build-time setting.

## Where the files live

Android keeps app data private, so these paths are reachable only through the
app (Back up, Export) or through `adb` on debug builds:

```
/data/data/studio.multitool.meshdroid/files/config.yaml          equivalent of /etc/meshtasticd/config.yaml
/data/data/studio.multitool.meshdroid/files/meshtasticd/prefs/   equivalent of /var/lib/meshtasticd/
/data/data/studio.multitool.meshdroid/files/meshcore/            meshcored's data directory: identity, contacts, channels, settings
/data/data/studio.multitool.meshdroid/files/battery              phone battery state, read by the daemon
```

A Meshtastic backup zip contains exactly `config.yaml` and `prefs/`, in the
layout `meshtasticd` uses on Linux. It can be unpacked onto a Linux install, and
a Linux install's files can be zipped up the same way and restored on the phone.

A MeshCore backup zip contains the data directory under a `meshcore/` prefix.

## Power, battery and limits

- **USB power.** Most phone ports supply about 500 mA. A 1 W dongle at full
  transmit power can exceed that. Symptoms: the node restarts by itself, or
  the log shows USB errors mid-session. Under Meshtastic, keep
  `SX126X_MAX_POWER` low or use a powered OTG hub, which also charges the phone.
- **Battery.** The USB-to-SPI bridge is polled continuously while the node
  runs. Expect a noticeable share of battery per hour. Stop releases the wake
  lock.
- **Sharing the node.** `meshtasticd`'s TCP API accepts a single connection.
  Meshdroid holds it and shares it, so the Meshtastic app, the Mesh tab and the
  Python CLI can be attached at once. Turning off *Share the node with several
  apps* in the Node tab restores the one-client behaviour. `meshcored` takes one
  client and a new connection evicts the old one, with no sharing available.
- **Two 1 W radios in one room saturate each other.** The SX1262 overloads at
  around -5 dBm and a front-end LNA makes it worse. If you are testing against a
  second node on the same desk, put them in different rooms or use an
  attenuator. Healthy readings are roughly -50 to -90 dBm.
- **No Bluetooth.** The phone apps connect over TCP, not BLE, by design.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "No CH341 radio found" | Check the OTG adapter or cable. Settings > Connected devices should list the dongle. |
| Node error: could not open the USB radio | Permission was denied, or another app holds the device. Unplug, replug, accept the prompt. |
| Node error: port in use | Another process on the phone is listening on 4403 or 5000. If you ran the sim-radio test from `docs/BUILDING.md`, stop it: `adb shell pkill -f /data/local/tmp/mtd`. |
| Meshtastic app connects, then hangs | Turn on *Share the node with several apps* in the Node tab, or close whatever else is attached. |
| Keys blank in the Meshtastic app, `/prefs/config.proto does not exist` in the log | The LoRa region is unset. Set it in the Node tab. |
| Node restarts on its own | USB power. Lower `SX126X_MAX_POWER` in the Config tab or use a powered hub. |
| Status stays *Starting* | After 30 seconds Meshdroid says so and points at the Logs tab. Usually the pinmap in the Config tab does not match the dongle, or the radio is not answering on SPI. |
| Laptop cannot reach the node over Wi-Fi | Under Meshtastic this is intended when *Allow LAN access* is off; turn it on and restart the node. Check you are using the phone's IP, not another node's. |
| Log warns that the daemon never reported its bind address | The bundled `meshtasticd` was built without firmware patch 0002, so it listens on every interface whatever the LAN switch says. Rebuild with `firmware/build-meshtasticd-android.sh`. |
| Other nodes show this node as powered, not on battery (Meshtastic) | Battery reporting is off in the Node tab, or the daemon predates firmware patches 0005 and 0006. Turn it on and restart, or rebuild the daemon. |
| No battery reading under MeshCore | Known issue in 2.0.0. MeshCore reports battery as a voltage only, and the app publishes a voltage only when the phone reports one in a range it recognises. Fix due in 2.0.1. |
| A MeshCore node is silent, and everything else looks right | A MeshCore companion node transmits only when a client asks it to. With no app attached it says nothing, by design, which looks exactly like a broken radio. Attach the MeshCore app and send an advert. |
| MeshCore hears nothing from the rest of the mesh | Frequency, bandwidth and spreading factor have to match exactly. There is no degraded mode: a mismatch means the radios cannot demodulate each other at all. |

## Building from source

See [docs/BUILDING.md](docs/BUILDING.md) for host setup on Fedora (tested),
Debian and Arch (not yet fully tested), both daemon cross-builds and the app
build. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how the pieces
fit and what each firmware patch does. [docs/RELEASING.md](docs/RELEASING.md)
covers signing, versioning and publishing.

A build with only one daemon present works: the Protocol picker lists the
daemons the APK actually contains, so skipping the MeshCore build gives a
Meshtastic-only app.

## License

Copyright 2026 Multitool Studio. Meshdroid is released under the GNU General
Public License, version 3 (see [LICENSE](LICENSE)).

`meshtasticd` in each release is built from
[meshtastic/firmware](https://github.com/meshtastic/firmware) (GPL-3.0) with
the patches in `firmware/`, at the commit pinned in
`firmware/build-meshtasticd-android.sh`.

`meshcored` is built from
[meshcore-dev/MeshCore](https://github.com/meshcore-dev/MeshCore) (MIT) with
the patches in `firmware/meshcore/patches/`, at the tag pinned in
`firmware/meshcore/build-meshcored-android.sh`. Its CH341 HAL
(`firmware/meshcore/variant/USBHal.h`) is vendored from meshtastic/firmware and
is GPL-3.0.

Those patches, variants and build scripts are the corresponding source for the
binaries.

The APK also contains the NDK's `libc++_shared.so`, redistributed unmodified
under the Apache 2.0 licence with LLVM exceptions.

## AI Disclosure

This codebase was developed with AI-assisted tooling. Prior to this project, I had no experience with the Android SDK or the meshtasticd source code. AI was used during development for research, boilerplate, and implementation support.

All architectural decisions, code review, and final implementation are my own. Every commit was audited and verified by me before merge. That said, I am a human.

Licensed under GPLv3. No warranty is provided; see LICENSE for full terms.
