// AndroidCompat.h: declarations for Arduino-isms MeshCore uses without
// including a header that declares them.
//
// Force-included ahead of everything by the env's
//   -include variants/meshdroid_ch341/AndroidCompat.h
//
// ltoa and friends are AVR extensions. framework-portduino declares them in
// cores/arduino/api/itoa.h and defines them in cores/portduino/itoa.cpp, so
// both the symbols and a canonical declaration exist. What is missing is any
// path from MeshCore's includes to that header: src/helpers/TxtDataHelpers.cpp
// calls ltoa directly when formatting telemetry numbers, and Arduino.h does not
// pull itoa.h in.
//
// extern "C" is not decoration. api/itoa.h declares these with C linkage, and a
// C++-linkage declaration of the same names is a hard conflict in every
// translation unit that reaches both.
//
// Two earlier versions of this file got it wrong: the first defined these
// functions, colliding with portduino's definitions, and the second declared
// them with the wrong linkage. This one declares, with C linkage, and nothing
// else.
#pragma once

#ifdef __cplusplus

extern "C" {

char* itoa(int value, char* str, int radix);
char* ltoa(long value, char* str, int radix);
char* utoa(unsigned value, char* str, int radix);
char* ultoa(unsigned long value, char* str, int radix);

}  // extern "C"

#endif  // __cplusplus
