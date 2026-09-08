// variant.h for the Android build (portduino on bionic).
//
// Derived from variants/native/portduino/variant.h with the Raspberry Pi hat
// hardware removed: no I2C bus and therefore no RV3028 RTC, no attached GPS,
// no TFT. The phone supplies time through the system clock and position
// through the Meshtastic app's phone-GPS sharing.
//
// build-meshtasticd-android.sh copies this file to
// variants/native/portduino-android/variant.h, and the env lists that
// directory ahead of variants/native/portduino, which keeps the native
// malloc.h and HUB75Native.h reachable.
#pragma once

#ifndef HAS_SCREEN
#define HAS_SCREEN 0
#endif
#define HAS_GPS 0
#define MAX_RX_TOPHONE portduino_config.maxtophone
#define MAX_NUM_NODES portduino_config.MaxNodes

#ifndef HAS_TRAFFIC_MANAGEMENT
#define HAS_TRAFFIC_MANAGEMENT 1
#endif
#ifndef HAS_VARIABLE_HOPS
#define HAS_VARIABLE_HOPS 1
#endif
