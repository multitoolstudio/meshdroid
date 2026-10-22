package studio.multitool.meshdroid.repeater

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import studio.multitool.meshdroid.MainActivity
import java.io.IOException

/**
 * Minimal USB-Host serial driver for MeshCore repeaters attached over OTG.
 *
 * Covers the bridges and native USB stacks used by MeshCore repeater boards:
 *   - USB CDC-ACM (nRF52840, RP2040, ESP32-S3/C3 native USB)
 *   - Silicon Labs CP210x (Heltec V3, T-Beam, etc.)
 *   - WCH CH340 / CH341-UART / CH9102
 *   - FTDI FT232 / FT231X
 *
 * The CH341 in SPI/EPP mode (0x1A86:0x5512) is Meshdroid's own node radio and is
 * explicitly excluded here so the two features never claim each other's device.
 */
object RepeaterUsb {

    enum class DriverType { CDC_ACM, CP210X, CH34X, FTDI }

    fun classify(device: UsbDevice): DriverType? {
        val vid = device.vendorId
        val pid = device.productId
        // Never touch the CH341 SPI node dongle.
        if (vid == MainActivity.CH341_VID && pid == MainActivity.CH341_PID) return null

        if (vid == 0x10C4 && (pid == 0xEA60 || pid == 0xEA70 || pid == 0xEA71)) return DriverType.CP210X
        if (vid == 0x1A86 && (pid == 0x7523 || pid == 0x5523 || pid == 0x55D4)) return DriverType.CH34X
        if (vid == 0x0403 && (pid == 0x6001 || pid == 0x6010 || pid == 0x6014 || pid == 0x6015)) return DriverType.FTDI

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                intf.interfaceClass == UsbConstants.USB_CLASS_COMM
            ) {
                return DriverType.CDC_ACM
            }
        }
        return null
    }

    fun findDevice(usb: UsbManager): UsbDevice? =
        usb.deviceList.values.firstOrNull { classify(it) != null }

    fun describe(device: UsbDevice): String {
        val name = device.productName?.takeIf { it.isNotBlank() }
            ?: device.manufacturerName?.takeIf { it.isNotBlank() }
            ?: classify(device)?.name
            ?: "USB Serial"
        return "%s (%04x:%04x)".format(name, device.vendorId, device.productId)
    }

    fun open(usb: UsbManager, device: UsbDevice, baudRate: Int = 115200): UsbSerialPort {
        val type = classify(device) ?: throw IOException("Unsupported USB device")
        val conn = usb.openDevice(device) ?: throw IOException("Could not open USB device")
        try {
            return UsbSerialPort(conn, device, type, baudRate)
        } catch (t: Throwable) {
            conn.close()
            throw t
        }
    }
}

class UsbSerialPort(
    private val conn: UsbDeviceConnection,
    val device: UsbDevice,
    private val type: RepeaterUsb.DriverType,
    baudRate: Int,
) : AutoCloseable {

    private val claimedInterfaces = mutableListOf<UsbInterface>()
    private val inEndpoint: UsbEndpoint
    private val outEndpoint: UsbEndpoint
    private var controlInterfaceIndex: Int = 0
    @Volatile private var closed = false

    init {
        var foundIn: UsbEndpoint? = null
        var foundOut: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                controlInterfaceIndex = intf.id
                if (conn.claimInterface(intf, true)) claimedInterfaces.add(intf)
            }
        }

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var hasBulkIn: UsbEndpoint? = null
            var hasBulkOut: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) hasBulkIn = ep
                    else if (ep.direction == UsbConstants.USB_DIR_OUT) hasBulkOut = ep
                }
            }
            if (hasBulkIn != null && hasBulkOut != null) {
                if (!claimedInterfaces.contains(intf)) {
                    if (!conn.claimInterface(intf, true)) {
                        throw IOException("Could not claim USB serial data interface")
                    }
                    claimedInterfaces.add(intf)
                }
                if (type != RepeaterUsb.DriverType.CDC_ACM) controlInterfaceIndex = intf.id
                foundIn = hasBulkIn
                foundOut = hasBulkOut
                break
            }
        }

        inEndpoint = foundIn ?: throw IOException("No bulk IN endpoint on USB serial device")
        outEndpoint = foundOut ?: throw IOException("No bulk OUT endpoint on USB serial device")

        configure(baudRate)
    }

    private fun configure(baudRate: Int) {
        when (type) {
            RepeaterUsb.DriverType.CDC_ACM -> {
                // SET_LINE_CODING (0x20): 7 bytes = dwDTERate (LE 32), bCharFormat (0=1 stop), bParityType (0=none), bDataBits (8)
                val coding = byteArrayOf(
                    (baudRate and 0xFF).toByte(),
                    ((baudRate shr 8) and 0xFF).toByte(),
                    ((baudRate shr 16) and 0xFF).toByte(),
                    ((baudRate shr 24) and 0xFF).toByte(),
                    0, 0, 8
                )
                conn.controlTransfer(0x21, 0x20, 0, controlInterfaceIndex, coding, coding.size, 1000)
                // SET_CONTROL_LINE_STATE (0x22): DTR=1 (bit 0) | RTS=1 (bit 1) = 0x03.
                // Native USB CDC on nRF52 / ESP32-S3 / RP2040 checks DTR before writing to Serial.
                conn.controlTransfer(0x21, 0x22, 0x0003, controlInterfaceIndex, null, 0, 1000)
            }
            RepeaterUsb.DriverType.CP210X -> {
                // IFC_ENABLE (0x00) = 1
                conn.controlTransfer(0x41, 0x00, 0x0001, controlInterfaceIndex, null, 0, 1000)
                // SET_BAUDRATE (0x1E): 4-byte LE baud
                val baud = byteArrayOf(
                    (baudRate and 0xFF).toByte(),
                    ((baudRate shr 8) and 0xFF).toByte(),
                    ((baudRate shr 16) and 0xFF).toByte(),
                    ((baudRate shr 24) and 0xFF).toByte()
                )
                conn.controlTransfer(0x41, 0x1E, 0, controlInterfaceIndex, baud, baud.size, 1000)
                // SET_LINE_CTL (0x03): 8 data bits, no parity, 1 stop bit = 0x0800
                conn.controlTransfer(0x41, 0x03, 0x0800, controlInterfaceIndex, null, 0, 1000)
                // SET_MHS (0x07): DTR=1, RTS=1 with masks (0x0303)
                conn.controlTransfer(0x41, 0x07, 0x0303, controlInterfaceIndex, null, 0, 1000)
            }
            RepeaterUsb.DriverType.CH34X -> {
                // Standard CH340/CH9102 init + 115200 8N1 (divisor 0xCC03, lcr 0x00C3)
                conn.controlTransfer(0x40, 0xA1, 0xC29C, 0xB2B9, null, 0, 1000)
                conn.controlTransfer(0x40, 0x9A, 0x1312, 0xCC03, null, 0, 1000)
                conn.controlTransfer(0x40, 0x9A, 0x2518, 0x00C3, null, 0, 1000)
                conn.controlTransfer(0x40, 0xA4, 0xFF9F, 0, null, 0, 1000)
            }
            RepeaterUsb.DriverType.FTDI -> {
                // Reset, 8N1, 115200 baud (divisor 26 = 0x001A), DTR+RTS on
                conn.controlTransfer(0x40, 0x00, 0, controlInterfaceIndex, null, 0, 1000)
                conn.controlTransfer(0x40, 0x04, 0x0008, controlInterfaceIndex, null, 0, 1000)
                conn.controlTransfer(0x40, 0x03, 0x001A, controlInterfaceIndex, null, 0, 1000)
                conn.controlTransfer(0x40, 0x01, 0x0303, controlInterfaceIndex, null, 0, 1000)
            }
        }
    }

    /** Reads available bytes into [dest]. Returns > 0 on data, 0 on timeout, < 0 on disconnect. */
    fun read(dest: ByteArray, timeoutMs: Int = 200): Int {
        if (closed) return -1
        val n = conn.bulkTransfer(inEndpoint, dest, dest.size, timeoutMs)
        if (n < 0) return 0
        if (type == RepeaterUsb.DriverType.FTDI) {
            // FTDI prepends 2 modem status bytes to every bulk IN packet.
            if (n <= 2) return 0
            System.arraycopy(dest, 2, dest, 0, n - 2)
            return n - 2
        }
        return n
    }

    fun write(src: ByteArray, timeoutMs: Int = 1000) {
        if (closed) throw IOException("Port closed")
        var offset = 0
        while (offset < src.size) {
            val chunk = minOf(src.size - offset, outEndpoint.maxPacketSize.coerceAtLeast(64))
            val slice = src.copyOfRange(offset, offset + chunk)
            val written = conn.bulkTransfer(outEndpoint, slice, slice.size, timeoutMs)
            if (written <= 0) throw IOException("USB serial write failed")
            offset += written
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        for (intf in claimedInterfaces) {
            runCatching { conn.releaseInterface(intf) }
        }
        runCatching { conn.close() }
    }
}
