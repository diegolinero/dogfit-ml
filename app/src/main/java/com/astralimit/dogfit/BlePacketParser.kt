package com.astralimit.dogfit

data class LivePacket(
    val tMs: Long,
    val label: Int,
    val conf: Int
)

data class ResRecord(
    val tMs: Long,
    val label: Int,
    val conf: Int,
    val seq: Long
)

object BlePacketParser {
    private const val RES_RECORD_BYTES = 10

    fun u32le(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 3 < bytes.size) { "Offset fuera de rango para u32le" }
        val b0 = bytes[offset + 0].toLong() and 0xFF
        val b1 = (bytes[offset + 1].toLong() and 0xFF) shl 8
        val b2 = (bytes[offset + 2].toLong() and 0xFF) shl 16
        val b3 = (bytes[offset + 3].toLong() and 0xFF) shl 24
        return (b0 or b1 or b2 or b3) and 0xFFFFFFFFL
    }

    fun parseLive(value: ByteArray): LivePacket {
        require(value.size >= 6) { "LIVE requiere al menos 6 bytes" }
        return LivePacket(
            tMs = u32le(value, 0),
            label = value[4].toInt() and 0xFF,
            conf = value[5].toInt() and 0xFF
        )
    }

    fun parseRes(value: ByteArray): List<ResRecord> {
        require(value.isNotEmpty() && value.size % RES_RECORD_BYTES == 0) {
            "RES requiere longitud múltiplo de $RES_RECORD_BYTES"
        }

        return buildList {
            var offset = 0
            while (offset + RES_RECORD_BYTES <= value.size) {
                add(
                    ResRecord(
                        tMs = u32le(value, offset + 0),
                        label = value[offset + 4].toInt() and 0xFF,
                        conf = value[offset + 5].toInt() and 0xFF,
                        seq = u32le(value, offset + 6)
                    )
                )
                offset += RES_RECORD_BYTES
            }
        }
    }

    fun parseBattery(value: ByteArray): Int {
        require(value.isNotEmpty()) { "Battery requiere al menos 1 byte" }
        return value[0].toInt() and 0xFF
    }
}
