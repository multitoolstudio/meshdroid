// target.cpp for the Meshdroid CH341 variant of MeshCore.
//
// Modelled on variants/heltec_v3/target.cpp. The difference is the bus: instead
// of an SPIClass on board pins, the SX1262 sits behind a CH341 USB-to-SPI
// bridge, reached through Ch341Hal, a RadioLib HAL over libpinedio that
// meshtasticd already uses on this exact dongle.
//
// Ch341Hal handles the three things this hardware needs and a naive HAL would
// get wrong: manual chip select (PINEDIO_OPTION_AUTO_CS 0), LSB-first bit
// reversal inside libpinedio, and DIO1 interrupts through
// pinedio_attach_interrupt, so no polling thread is required.

#include <Arduino.h>
#include "target.h"
#include "USBHal.h"

// Declares MeshdroidFS and the extern for AppFS. Included here explicitly
// rather than force-included by the env: putting it on every translation unit
// dragged <FS.h> and <Arduino.h> into framework-portduino's own sources, which
// made simulated/SimHardwareI2C.cpp collide with linux/LinuxHardwareI2C.h over
// arduino::Wire. MeshCore reaches it through the branch patch 0001 adds to
// src/helpers/IdentityStore.h; this file is outside that chain, so it asks
// directly.
#include <MeshdroidFS.h>

#include <cstdio>
#include <cstdlib>
#include <unistd.h>

MeshdroidBoard board;

// The filesystem MeshCore's IdentityStore and DataStore write through. It wraps
// portduino's global PortduinoFS, whose VFS root portduino's own main() mounts
// before setup() runs, so there is nothing to initialise here. MeshdroidFS.h is
// force-included by the env so that FILESYSTEM is defined before
// IdentityStore.h's #if chain is reached.
MeshdroidFS AppFS(PortduinoFS);

// ---------------------------------------------------------------------------
// The radio
//
// Ch341Hal's constructor throws when the dongle cannot be opened, and this is
// static initialisation, so a throw here would abort before main() and before
// anything could log why. Building it through a function that catches turns
// that into a null pointer and a message, which radio_init() then reports
// cleanly.
//
// Static initialisation also means the CH341 is opened before main() runs. On
// Android the descriptor arrives over the abstract-namespace socket named by
// PINEDIO_USB_FD_SOCKET (patch 0001), so DaemonService must already be
// listening on that socket before it execs this binary. It is, but the ordering
// is worth knowing when debugging a start-up failure.
// ---------------------------------------------------------------------------
#ifndef MESHTOAD_USB_VID
#define MESHTOAD_USB_VID 0x1A86
#endif
#ifndef MESHTOAD_USB_PID
#define MESHTOAD_USB_PID 0x5512
#endif

static Ch341Hal* make_ch341_hal() {
  // MESHCORE_USB_SERIAL selects between several dongles. Empty means take the
  // first CH341 that matches the VID and PID.
  const char* serial = getenv("MESHCORE_USB_SERIAL");
  try {
    return new Ch341Hal(0, serial != NULL ? serial : "",
                        MESHTOAD_USB_VID, MESHTOAD_USB_PID);
  } catch (const std::exception& e) {
    fprintf(stderr, "Could not open the CH341: %s\n", e.what());
    return NULL;
  }
}

static Ch341Hal* ch341_hal = make_ch341_hal();

// RADIOLIB_NC for the fifth pin: the CH341 exposes no general-purpose GPIO to
// RadioLib beyond the four the Module needs.
RADIO_CLASS radio = new Module(ch341_hal, P_LORA_NSS, P_LORA_DIO_1,
                               P_LORA_RESET, P_LORA_BUSY);

WRAPPER_CLASS radio_driver(radio, board);

LinuxRTCClock rtc_clock;

EnvironmentSensorManager sensors;

// ---------------------------------------------------------------------------
// Board members that need more than an inline body
// ---------------------------------------------------------------------------
uint16_t MeshdroidBoard::getBattMilliVolts() {
  const char* path = getenv("MESHCORE_BATTERY_FILE");
  if (path == NULL || path[0] == '\0') return 0;

  FILE* f = fopen(path, "r");
  if (f == NULL) return 0;

  unsigned int mv = 0;
  int matched = fscanf(f, "%u", &mv);
  fclose(f);
  if (matched != 1) return 0;

  // A plausible single-cell range. Anything outside it is a malformed file
  // rather than a real reading, and reporting nonsense to the app is worse
  // than reporting nothing.
  if (mv < 2500 || mv > 4500) return 0;
  return (uint16_t)mv;
}

void MeshdroidBoard::reboot() {
  fprintf(stderr, "reboot requested, exiting for the service to restart us\n");
  fflush(NULL);
  _exit(0);
}

// ---------------------------------------------------------------------------
// Radio bring-up
// ---------------------------------------------------------------------------
bool radio_init() {
  if (ch341_hal == NULL) {
    fprintf(stderr, "No CH341 HAL; the radio cannot start\n");
    return false;
  }
  // No SPIClass argument: the HAL owns the bus, so std_init() skips its
  // #if defined(P_LORA_SCLK) block entirely.
  return radio.std_init();
}

mesh::LocalIdentity radio_new_identity() {
  RadioNoiseListener rng(radio);
  return mesh::LocalIdentity(&rng);   // create a new random identity
}
