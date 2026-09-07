# Changelog

## 1.0.0

First release.

- meshtasticd (meshtastic/firmware, native target) cross-compiled for Android
  arm64 against bionic, packaged inside the app.
- CH341 USB LoRa dongles opened through the Android USB Host API and handed to
  the daemon over a Unix socket (patch 0001 to libch341-spi-userspace).
- Foreground service with wake lock; start on USB attach; clean stop,
  restart and close; orphaned daemon cleanup; port-in-use detection.
- API bound to localhost by default with an "Allow LAN access" switch
  (patch 0002 to the portduino WiFi shim).
- LoRa region picker; the daemon applies `Lora: Region:` from config.yaml on
  a node that has none yet, which triggers identity key generation
  (patch 0003 to the firmware).
- config.yaml editor with device presets (MeshToad, MeshStick, Pinedio USB,
  uMesh, RAK19714), import and export.
- Backup and restore of config.yaml plus the node database and keys as a zip,
  interchangeable with a Linux meshtasticd install.
- Live log view with filter, follow, verbose mode and export.
- Reset identity.
