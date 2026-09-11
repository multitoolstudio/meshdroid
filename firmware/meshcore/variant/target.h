// target.h for the Meshdroid CH341 variant of MeshCore.
//
// MyMesh.h includes this as <target.h>, so the variant directory has to be on
// the include path. build-meshcored-android.sh copies this file, target.cpp and
// platformio-android.ini into variants/meshdroid_ch341/ in the MeshCore
// checkout, and the env adds -I variants/meshdroid_ch341.
//
// Modelled on variants/heltec_v3/target.h, with the ESP32 board, the I2C RTC
// and the display removed. The hardware is a NullHop MeshToad V3: an
// E22P-915M30S behind a CH341 USB-to-SPI bridge, driven through the Ch341Hal
// that meshtasticd already uses.
#pragma once

#define RADIOLIB_STATIC_ONLY 1
#include <RadioLib.h>
#include <helpers/radiolib/RadioLibWrappers.h>
#include <helpers/radiolib/CustomSX1262Wrapper.h>
#include <helpers/sensors/EnvironmentSensorManager.h>

#include <ctime>

// ---------------------------------------------------------------------------
// Board
//
// mesh::MainBoard has four pure virtuals; everything else defaults. This is a
// USB dongle on a phone, so there is no battery of its own, no buttons and
// nothing to sleep.
// ---------------------------------------------------------------------------
class MeshdroidBoard : public mesh::MainBoard {
  uint8_t startup_reason = BD_STARTUP_NORMAL;

public:
  void begin() { startup_reason = BD_STARTUP_NORMAL; }

  // The phone's battery, not the dongle's. DaemonService writes millivolts to
  // the file named by MESHCORE_BATTERY_FILE, the same arrangement meshtasticd
  // patch 0005 uses. Returns 0 when the file is absent or unreadable, which is
  // what MeshCore reports for a board with no battery sensing.
  uint16_t getBattMilliVolts() override;

  const char* getManufacturerName() const override { return "Meshdroid MeshToad V3"; }

  // The daemon runs under DaemonService, which restarts it. Exiting is the
  // closest thing to a reboot available to an unprivileged process, and it lets
  // the service decide whether to come back.
  void reboot() override;

  uint8_t getStartupReason() const override { return startup_reason; }

  // DIO1, so callers that want to watch the radio's interrupt line get the
  // CH341 adapter index rather than -1.
  uint32_t getIRQGpio() override { return P_LORA_DIO_1; }
};

// ---------------------------------------------------------------------------
// Clock
//
// The phone's clock is already correct and an unprivileged process cannot set
// the system clock, so setCurrentTime() records an offset rather than trying.
// In practice the offset stays at zero and the companion protocol's
// CMD_SET_DEVICE_TIME becomes a no-op, which is the right outcome.
// ---------------------------------------------------------------------------
class LinuxRTCClock : public mesh::RTCClock {
  int32_t offset = 0;

public:
  uint32_t getCurrentTime() override {
    return (uint32_t)::time(NULL) + offset;
  }

  void setCurrentTime(uint32_t time) override {
    offset = (int32_t)(time - (uint32_t)::time(NULL));
  }
};

extern MeshdroidBoard board;
extern WRAPPER_CLASS radio_driver;
extern LinuxRTCClock rtc_clock;
extern EnvironmentSensorManager sensors;

bool radio_init();
mesh::LocalIdentity radio_new_identity();
