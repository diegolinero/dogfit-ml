package com.astralimit.dogfit

import org.junit.Assert.assertEquals
import org.junit.Test

class BlePacketParserTest {

    @Test
    fun `u32le parses little-endian uint32`() {
        val bytes = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        assertEquals(0x12345678L, BlePacketParser.u32le(bytes, 0))
    }

    @Test
    fun `parseLive parses 6-byte packet`() {
        val bytes = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x02, 0x64.toByte())
        val parsed = BlePacketParser.parseLive(bytes)

        assertEquals(1L, parsed.tMs)
        assertEquals(2, parsed.label)
        assertEquals(100, parsed.conf)
    }

    @Test
    fun `parseCapture parses 12-byte IMU sample`() {
        val bytes = byteArrayOf(
            0x01, 0x00,
            0xFE.toByte(), 0xFF.toByte(),
            0x10, 0x27,
            0x20, 0x00,
            0x30, 0x00,
            0x40, 0x00
        )

        val sample = BlePacketParser.parseCapture(bytes).first()

        assertEquals(1, sample.ax.toInt())
        assertEquals(-2, sample.ay.toInt())
        assertEquals(10000, sample.az.toInt())
        assertEquals(32, sample.gx.toInt())
        assertEquals(48, sample.gy.toInt())
        assertEquals(64, sample.gz.toInt())
    }

    @Test
    fun `parseBattery parses single byte percentage`() {
        val bytes = byteArrayOf(0x64.toByte())
        assertEquals(100, BlePacketParser.parseBattery(bytes))
    }
}
