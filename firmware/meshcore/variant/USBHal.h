// USBHal.h: a RadioLib HAL over a CH341 USB-to-SPI bridge.
//
// Vendored from meshtastic/firmware src/platform/portduino/USBHal.h and adapted
// for MeshCore. Both projects are GPL-3, so this is a clean reuse.
//
// Changes from the original, all of them removals:
//   * dropped #include "platform/portduino/PortduinoGlue.h", which is Meshtastic
//     firmware and does not exist here
//   * LOG_ERROR became fprintf(stderr, ...); MeshCore has no such macro
//   * dropped the portduino_status.LoRa_in_error assignments, which set a flag
//     in Meshtastic's global status struct
//   * dropped the rebootAtMsec guard around error logging; MeshCore has no
//     equivalent, so errors always log
//   * dropped the unused CH341_PIN_CS / CH341_PIN_IRQ defines. The Meshtoad's
//     pins come from P_LORA_* in the variant, matching how PortduinoGlue passes
//     config.yaml values straight through to the Module.
//
// What this handles that a naive HAL would get wrong, all confirmed on hardware:
//   * manual chip select, via PINEDIO_OPTION_AUTO_CS 0. There is no hardware CS.
//   * LSB-first bit order, reversed inside libpinedio in both directions.
//   * DIO1 interrupts through pinedio_attach_interrupt, so no polling thread is
//     needed even though the CH341 offers no host interrupt line.

#ifndef MESHDROID_USB_HAL_H
#define MESHDROID_USB_HAL_H

#include <RadioLib.h>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <libpinedio-usb.h>
#include <sys/time.h>
#include <unistd.h>

#define PI_RISING (PINEDIO_INT_MODE_RISING)
#define PI_FALLING (PINEDIO_INT_MODE_FALLING)
#define PI_INPUT (0)
#define PI_OUTPUT (1)
#define PI_LOW (0)
#define PI_HIGH (1)

class Ch341Hal : public RadioLibHal
{
  public:
    explicit Ch341Hal(uint8_t spiChannel, const std::string &serial = "", uint32_t vid = 0x1A86,
                      uint32_t pid = 0x5512, uint32_t spiSpeed = 2000000, uint8_t spiDevice = 0,
                      uint8_t gpioDevice = 0)
        : RadioLibHal(PI_INPUT, PI_OUTPUT, PI_LOW, PI_HIGH, PI_RISING, PI_FALLING)
    {
        (void)spiChannel;
        (void)spiSpeed;
        (void)spiDevice;
        (void)gpioDevice;

        if (serial != "") {
            std::strncpy(pinedio.serial_number, serial.c_str(), 8);
            pinedio_set_option(&pinedio, PINEDIO_OPTION_SEARCH_SERIAL, 1);
        }

        // There is no vendor 0x0, so a zero here means "take the default".
        if (vid != 0x0) {
            pinedio_set_option(&pinedio, PINEDIO_OPTION_VID, vid);
            pinedio_set_option(&pinedio, PINEDIO_OPTION_PID, pid);
        }

        int32_t ret = pinedio_init(&pinedio, NULL);
        if (ret != 0) {
            std::string s = "Could not open SPI: ";
            throw std::runtime_error(s + std::to_string(ret));
        }

        pinedio_set_option(&pinedio, PINEDIO_OPTION_AUTO_CS, 0);
        pinedio_set_pin_mode(&pinedio, 3, true);
        pinedio_set_pin_mode(&pinedio, 5, true);
    }

    ~Ch341Hal() { pinedio_deinit(&pinedio); }

    void getSerialString(char *_serial, size_t len)
    {
        if (len == 0)
            return;
        size_t bytesCopied = (len - 1) < 8 ? (len - 1) : 8;
        std::strncpy(_serial, pinedio.serial_number, bytesCopied);
        _serial[bytesCopied] = '\0';
    }

    void getProductString(char *_product_string, size_t len)
    {
        len = len > 95 ? 95 : len;
        memcpy(_product_string, pinedio.product_string, len);
    }

    void init() override {}
    void term() override {}

    // RADIOLIB_NC marks a pin as not connected; every GPIO method has to check.
    void pinMode(uint32_t pin, uint32_t mode) override
    {
        if (checkError() || pin == RADIOLIB_NC) {
            return;
        }
        auto res = pinedio_set_pin_mode(&pinedio, pin, mode);
        if (res < 0) {
            fprintf(stderr, "USBHal pinMode: cannot set pin %u mode to %u: %d\n", pin, mode, res);
        }
    }

    void digitalWrite(uint32_t pin, uint32_t value) override
    {
        if (checkError() || pin == RADIOLIB_NC) {
            return;
        }
        auto res = pinedio_digital_write(&pinedio, pin, value);
        if (res < 0) {
            fprintf(stderr, "USBHal digitalWrite: cannot write pin %u: %d\n", pin, res);
        }
    }

    uint32_t digitalRead(uint32_t pin) override
    {
        if (checkError() || pin == RADIOLIB_NC) {
            return 0;
        }
        auto res = pinedio_digital_read(&pinedio, pin);
        if (res < 0) {
            fprintf(stderr, "USBHal digitalRead: cannot read pin %u: %d\n", pin, res);
            return 0;
        }
        return res;
    }

    void attachInterrupt(uint32_t interruptNum, void (*interruptCb)(void), uint32_t mode) override
    {
        if (checkError() || interruptNum == RADIOLIB_NC) {
            return;
        }
        pinedio_attach_interrupt(&this->pinedio, (pinedio_int_pin)interruptNum,
                                 (pinedio_int_mode)mode, interruptCb);
    }

    void detachInterrupt(uint32_t interruptNum) override
    {
        if (checkError() || interruptNum == RADIOLIB_NC) {
            return;
        }
        pinedio_deattach_interrupt(&this->pinedio, (pinedio_int_pin)interruptNum);
    }

    void delay(unsigned long ms) override { delayMicroseconds(ms * 1000); }

    void delayMicroseconds(unsigned long us) override
    {
        if (us == 0) {
            sched_yield();
            return;
        }
        usleep(us);
    }

    void yield() override { sched_yield(); }

    unsigned long millis() override
    {
        struct timeval tv;
        gettimeofday(&tv, NULL);
        return (tv.tv_sec * 1000ULL) + (tv.tv_usec / 1000ULL);
    }

    unsigned long micros() override
    {
        struct timeval tv;
        gettimeofday(&tv, NULL);
        return (tv.tv_sec * 1000000ULL) + tv.tv_usec;
    }

    long pulseIn(uint32_t pin, uint32_t state, unsigned long timeout) override
    {
        (void)state;
        (void)timeout;
        fprintf(stderr, "pulseIn for pin %u is not supported\n", pin);
        return 0;
    }

    void spiBegin() {}
    void spiBeginTransaction() {}

    void spiTransfer(uint8_t *out, size_t len, uint8_t *in)
    {
        if (checkError()) {
            return;
        }
        int32_t ret = pinedio_transceive(&this->pinedio, out, in, len);
        if (ret < 0) {
            fprintf(stderr, "Could not perform SPI transfer: %d\n", ret);
        }
    }

    void spiEndTransaction() {}
    void spiEnd() {}

    bool checkError()
    {
        if (pinedio.in_error) {
            if (!has_warned) {
                fprintf(stderr, "USBHal: libch341 in_error detected\n");
            }
            has_warned = true;
            return true;
        }
        has_warned = false;
        return false;
    }

  private:
    pinedio_inst pinedio = {0};
    bool has_warned = false;
};

#endif
