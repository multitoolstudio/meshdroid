# Meshdroid

*by [Multitool Studio](https://multitool.studio)*

Meshdroid runs the Meshtastic Linux daemon, `meshtasticd`, on an Android phone
and drives a USB LoRa dongle through USB-C OTG. The phone is the node. No root,
no external computer, no separate battery. The official Meshtastic app connects
to it over a local TCP connection exactly as it would to a Raspberry Pi node.

Works with CH341-based USB radios: NULLHOP MeshToad (all revisions), MeshStick,
Pinedio USB, uMesh USB, RAK19714, and anything else `meshtasticd` supports with
`spidev: ch341`.

Meshdroid is an independent project and is not affiliated with or endorsed by
Meshtastic LLC. "Meshtastic" is a registered trademark of Meshtastic LLC.

```
+- Android phone ---------------------------------------------------------+
|  Meshtastic app --TCP 127.0.0.1:4403--> meshtasticd (arm64, bionic)     |
|                                              | libusb (wrapped fd)      |
|  Meshdroid ---- USB Host API ---- fd -------> |                          |
|    (foreground service, wake lock, UI)       v                          |
+------------------------------------------ USB-C OTG --> CH341 -SPI-> SX1262
```

## Contents

- [Requirements](#requirements)
- [Install](#install)
- [First run](#first-run)
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
- A CH341-based Meshtastic USB dongle with an antenna attached. Never power a
  LoRa radio without an antenna.
- A USB-C OTG adapter, or a USB-C to USB-C cable. Most C-to-C cables negotiate
  host mode on their own.
- The official Meshtastic app from the Play Store.

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
2. Meshdroid asks for your **LoRa region**. Pick the one you are in. Firmware
   2.8 and later will not create the node's identity keys, transmit, or write
   its saved config until a region is set, so this step is required before
   the node does anything useful.
3. Watch the Node tab. The status goes *Starting* then *Node running* and
   fills in the node ID, firmware version and the dongle's serial number.
4. In the Meshtastic app: **+** > **Network** > address `127.0.0.1`, port
   `4403` > Connect.

The default configuration is the MeshToad pinmap with transmit power capped
at 10 dBm. See [Power, battery and limits](#power-battery-and-limits) before
raising it.

## The app

### Node

- **Status card.** Node state, node ID, firmware version and radio serial.
  Start, Stop and Restart act on the meshtasticd process only; the app stays
  open.
- **LoRa region.** Writes `Lora: Region:` into `config.yaml` and restarts the
  node. The daemon applies it only while the node has no region yet, so a
  region changed later in the Meshtastic app takes precedence.
- **Allow LAN access.** Off by default: the API listens on `127.0.0.1` and only
  this phone can reach it. On: it listens on all interfaces and any device on
  the same Wi-Fi can connect to, read from and reconfigure the node. Takes
  effect on the next start.
- **Report phone battery to the mesh.** On by default. The node publishes the
  phone's charge level, and whether it is charging, the way a battery-powered
  node does. Other nodes then see how long this one is likely to last. Off means
  the node reports itself as USB powered, which is what `meshtasticd` does on a
  Linux box. Applies on the next start.
- **Start when radio is plugged in.** The USB auto-launch.
- **Back up / Restore.** Moves `config.yaml` plus the node database, channels
  and keys as a zip through the system file picker: Downloads, Drive, a USB
  stick, wherever. The zip holds the node's private key in the clear, so put it
  somewhere you would put a key. Restore asks first, then stops the node, waits
  for it to exit, and replaces `config.yaml` and the whole `prefs/` tree with
  what the backup contains. Start the node again afterwards.
- **Reset identity.** Deletes the node database and keys. The node gets a new
  keypair on its next start. `config.yaml` is kept. Only available while the
  node is stopped.
- **Close Meshdroid.** Stops the node and quits the app.

The bottom of the tab shows the app version and the firmware commit the bundled
daemon was built from.

### Config

The `config.yaml` editor, same format as `/etc/meshtasticd/config.yaml` on
Linux. **Presets** loads a known pinmap: MeshToad, MeshStick, Pinedio USB,
uMesh (SX1262 and SX1268), RAK19714. **Import** and **Export** work with any
YAML from a Linux install. Changes apply after a restart; **Save & restart**
does both.

### Mesh

A direct connection to the node, the same one the Meshtastic app makes. **Connect** shows
what the node is hearing as it arrives: text messages, node sightings and other traffic,
with a box to broadcast a message.

**Phone sensors** lists the environment sensors this phone has, with a live reading for
each. Barometric pressure and ambient light can be published to the mesh as environment
telemetry, off by default, sent every 30 minutes while the link is connected.

With the proxy on, connecting here leaves the Meshtastic app connected too. With it off,
meshtasticd accepts one client and connecting here disconnects that app. This tab is the foundation for the BBS: the link it uses
is an interface, so the same code will work through a multiplexer later, and against
MeshCore after that.

### BBS

A bulletin board and mail drop that answers commands sent to this node. Built in: `help`,
`about`, `ping`, `board list`, `board read <n>`, `board post <text>`, `mail` to collect
yours and `mail <to> <text>` to leave one.

Direct messages to the node are treated as commands. Channel messages count only when they
start with `!`, so ordinary conversation is left alone. Replies are spaced out and limited
per sender, because the channel is shared.

The tab carries a console that runs a command locally without transmitting, which is the
quickest way to see what a command does.

### Logs

The live daemon log. **Export** writes it to a file through the file picker,
**Clear** empties the view, **Follow** keeps the newest line in view, and
**Verbose** adds the daemon's `-v` output starting from the next start. The
filter box matches case-insensitively.

## Where the files live

Android keeps app data private, so these paths are reachable only through the
app (Back up, Export) or through `adb` on debug builds:

```
/data/data/studio.multitool.meshdroid/files/config.yaml          equivalent of /etc/meshtasticd/config.yaml
/data/data/studio.multitool.meshdroid/files/meshtasticd/prefs/   equivalent of /var/lib/meshtasticd/
/data/data/studio.multitool.meshdroid/files/battery              phone battery state, read by the daemon
```

A backup zip contains exactly `config.yaml` and `prefs/`, in the layout
meshtasticd uses on Linux. It can be unpacked onto a Linux install, and a
Linux install's files can be zipped up the same way and restored on the phone.

## Power, battery and limits

- **USB power.** Most phone ports supply about 500 mA. A 1 W dongle at full
  transmit power can exceed that. Symptoms: the node restarts by itself, or
  the log shows USB errors mid-session. Keep `SX126X_MAX_POWER` low or use a
  powered OTG hub, which also charges the phone.
- **Battery.** The USB-to-SPI bridge is polled continuously while the node
  runs. Expect a noticeable share of battery per hour. Stop releases the wake
  lock. The node reports the phone's charge to the mesh, so other operators can
  see it draining.
- **Sharing the node.** meshtasticd's TCP API accepts a single connection. Meshdroid holds
  it and shares it, so the Meshtastic app, the Mesh tab and the Python CLI can be attached
  at once. Turning off *Share the node with several apps* in the Node tab restores the
  one-client behaviour.
- **No Bluetooth.** The phone app connects over TCP, not BLE, by design.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "No CH341 radio found" | Check the OTG adapter or cable. Settings > Connected devices should list the dongle. |
| Node error: could not open the USB radio | Permission was denied, or another app holds the device. Unplug, replug, accept the prompt. |
| Node error: port 4403 in use | Another process on the phone is listening on 4403. If you ran the sim-radio test from `docs/BUILDING.md`, stop it: `adb shell pkill -f /data/local/tmp/mtd`. |
| Meshtastic app connects, then hangs | Turn on *Share the node with several apps* in the Node tab, or close whatever else is attached. |
| Keys blank in the Meshtastic app, `/prefs/config.proto does not exist` in the log | The LoRa region is unset. Set it in the Node tab. |
| Node restarts on its own | USB power. Lower `SX126X_MAX_POWER` in the Config tab or use a powered hub. |
| Status stays *Starting* | After 30 seconds Meshdroid says so and points at the Logs tab. Usually the pinmap in the Config tab does not match the dongle, or the radio is not answering on SPI. |
| Laptop cannot reach the node over Wi-Fi | Intended when *Allow LAN access* is off. Turn it on and restart the node. Check you are using the phone's IP, not another node's. |
| Log warns that the daemon never reported its bind address | The bundled daemon was built without firmware patch 0002, so it listens on every interface whatever the LAN switch says. Rebuild with `firmware/build-meshtasticd-android.sh`. |
| Other nodes show this node as powered, not on battery | Battery reporting is off in the Node tab, or the daemon predates firmware patches 0005 and 0006. Turn it on and restart, or rebuild the daemon. |

## Building from source

See [docs/BUILDING.md](docs/BUILDING.md) for host setup on Fedora (tested),
Debian and Arch (not yet fully tested), the firmware cross-build and the app
build. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how the pieces
fit and what each firmware patch does. [docs/RELEASING.md](docs/RELEASING.md)
covers signing, versioning and publishing.

## License

Copyright 2026 Multitool Studio. Meshdroid is released under the GNU General
Public License, version 3 (see [LICENSE](LICENSE)).

The daemon shipped in each release is built from
[meshtastic/firmware](https://github.com/meshtastic/firmware) (GPL-3.0) with
the patches in `firmware/`, at the commit pinned in
`firmware/build-meshtasticd-android.sh`. Those patches and build scripts are
the corresponding source for the binary.

The APK also contains the NDK's `libc++_shared.so`, redistributed unmodified
under the Apache 2.0 licence with LLVM exceptions.

## AI Disclosure

This codebase was developed with AI-assisted tooling. Prior to this project, I had no experience with the Android SDK or the meshtasticd source code. AI was used during development for research, boilerplate, and implementation support.

All architectural decisions, code review, and final implementation are my own. Every commit was audited and verified by me before merge. That said, I am a human.

Licensed under GPLv3. No warranty is provided; see LICENSE for full terms.
