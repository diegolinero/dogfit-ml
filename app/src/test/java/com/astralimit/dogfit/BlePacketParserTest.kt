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
    fun `parseBattery parses single byte percentage`() {
        val bytes = byteArrayOf(0x64.toByte())
        assertEquals(100, BlePacketParser.parseBattery(bytes))
    }
}
