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
- [Licence](#licence)

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
- **Start when radio is plugged in.** The USB auto-launch.
- **Back up / Restore.** Moves `config.yaml` plus the node database, channels
  and keys as a zip through the system file picker: Downloads, Drive, a USB
  stick, wherever. Restore stops the node first; start it again afterwards.
- **Reset identity.** Deletes the node database and keys. The node gets a new
  keypair on its next start. `config.yaml` is kept. Only available while the
  node is stopped.
- **Close Meshdroid.** Stops the node and quits the app.

### Config

The `config.yaml` editor, same format as `/etc/meshtasticd/config.yaml` on
Linux. **Presets** loads a known pinmap: MeshToad, MeshStick, Pinedio USB,
uMesh (SX1262 and SX1268), RAK19714. **Import** and **Export** work with any
YAML from a Linux install. Changes apply after a restart; **Save & restart**
does both.

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
  lock.
- **One client at a time.** meshtasticd's TCP API accepts a single connection.
  Connecting the Python CLI disconnects the phone app and the other way round.
- **No Bluetooth.** The phone app connects over TCP, not BLE, by design.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "No CH341 radio found" | Check the OTG adapter or cable. Settings > Connected devices should list the dongle. |
| Node error: could not open the USB radio | Permission was denied, or another app holds the device. Unplug, replug, accept the prompt. |
| Node error: port 4403 in use | Another process on the phone is listening on 4403. If you ran the sim-radio test from `docs/BUILDING.md`, stop it: `adb shell pkill -f /data/local/tmp/mtd`. |
| Meshtastic app connects, then hangs | Only one client can be attached. Close the Python CLI or any other client. |
| Keys blank in the Meshtastic app, `/prefs/config.proto does not exist` in the log | The LoRa region is unset. Set it in the Node tab. |
| Node restarts on its own | USB power. Lower `SX126X_MAX_POWER` in the Config tab or use a powered hub. |
| Laptop cannot reach the node over Wi-Fi | Intended when *Allow LAN access* is off. Turn it on and restart the node. Check you are using the phone's IP, not another node's. |

## Building from source

See [docs/BUILDING.md](docs/BUILDING.md) for host setup on Fedora (tested),
Debian and Arch (not yet fully tested), the firmware cross-build and the app
build. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how the pieces
fit and what each firmware patch does. [docs/RELEASING.md](docs/RELEASING.md)
covers signing, versioning and publishing.

## Licence

Copyright 2026 Multitool Studio. Meshdroid is released under the GNU General
Public License, version 3 (see [LICENSE](LICENSE)).

The daemon shipped in each release is built from
[meshtastic/firmware](https://github.com/meshtastic/firmware) (GPL-3.0) with
the patches in `firmware/`. Those patches and build scripts are the
corresponding source for the binary.
