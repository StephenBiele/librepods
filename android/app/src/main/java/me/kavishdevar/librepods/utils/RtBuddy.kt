/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.utils

/**
 * Minimal RTBuddy (opcode 0x17) codec.
 *
 * Heart rate does not arrive as an AACP control command: it rides the "buddy" sensor
 * stream as a protobuf `SensorDataWX` message whose `command` field carries the service
 * id and its payload. Upstream's rewrite branch (android/rewrite) parses this with the
 * generated `rtbuddy` protobuf classes; here we read the two fields we need by hand so
 * the app does not grow a protobuf toolchain for one number.
 *
 * Frame layout after the 6-byte AACP header (type, service, opcode, ...):
 *   [0..3] descriptor, little endian (SENSOR_DATA_WX = 0x00100000)
 *   [4..5] payload length, little endian
 *   [6..]  protobuf SensorDataWX
 */
object RtBuddy {
    /** Message descriptor carrying sensor data (see RTBuddyDescriptor.SENSOR_DATA_WX). */
    const val DESCRIPTOR_SENSOR_DATA_WX = 0x00100000

    const val SERVICE_HEARTRATE = 19
    const val SERVICE_HEARTRATE_V2 = 20

    /** SensorServiceSetting.setting value used upstream for a report-interval change. */
    private const val SETTING_REPORT_INTERVAL = 2

    data class SensorCommand(val service: Int, val payload: ByteArray?)

    /**
     * Parse a 0x17 packet into the SensorDataWX `command` field, or null when the frame
     * is not a sensor-data frame / carries no command.
     */
    fun sensorCommand(packet: ByteArray): SensorCommand? {
        val frame = parseFrame(packet) ?: return null
        if (frame.descriptor != DESCRIPTOR_SENSOR_DATA_WX) return null
        return readCommand(frame.data)
    }

    data class Frame(val descriptor: Int, val data: ByteArray)

    fun parseFrame(packet: ByteArray): Frame? {
        if (packet.size < 12) return null
        val descriptor = (packet[6].toInt() and 0xFF) or
            ((packet[7].toInt() and 0xFF) shl 8) or
            ((packet[8].toInt() and 0xFF) shl 16) or
            ((packet[9].toInt() and 0xFF) shl 24)
        val length = (packet[10].toInt() and 0xFF) or ((packet[11].toInt() and 0xFF) shl 8)
        if (length <= 0 || packet.size < 12 + length) return null
        return Frame(descriptor, packet.copyOfRange(12, 12 + length))
    }

    /**
     * Walk the SensorDataWX fields we care about: field 7 (`command`) holding
     * SensorCommand{service = 1, payload = 3}.
     */
    fun readCommand(data: ByteArray): SensorCommand? {
        var i = 0
        while (i < data.size) {
            val (tag, afterTag) = readVarint(data, i) ?: return null
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            i = afterTag
            when (wire) {
                0 -> i = readVarint(data, i)?.second ?: return null       // varint
                1 -> i += 8                                               // fixed64
                5 -> i += 4                                               // fixed32
                2 -> {
                    val (len, afterLen) = readVarint(data, i) ?: return null
                    i = afterLen
                    val length = len.toInt()
                    if (length < 0 || i + length > data.size) return null
                    if (field == 7) return readSensorCommand(data.copyOfRange(i, i + length))
                    i += length
                }
                else -> return null
            }
        }
        return null
    }

    private fun readSensorCommand(data: ByteArray): SensorCommand? {
        var service: Int? = null
        var payload: ByteArray? = null
        var i = 0
        while (i < data.size) {
            val (tag, afterTag) = readVarint(data, i) ?: return null
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            i = afterTag
            when (wire) {
                0 -> {
                    val (value, next) = readVarint(data, i) ?: return null
                    i = next
                    if (field == 1) service = value.toInt()
                }
                2 -> {
                    val (len, afterLen) = readVarint(data, i) ?: return null
                    i = afterLen
                    val length = len.toInt()
                    if (length < 0 || i + length > data.size) return null
                    if (field == 3) payload = data.copyOfRange(i, i + length)
                    i += length
                }
                else -> return null
            }
        }
        return SensorCommand(service ?: return null, payload)
    }

    /**
     * Heart rate samples are an 18-byte payload: byte 1 is the bpm, byte 17 is a validity
     * flag (non-zero means discard). Matches android/rewrite AACPManager HEARTRATE handling.
     */
    fun heartRateBpm(packet: ByteArray): Int? {
        val command = sensorCommand(packet) ?: return null
        if (command.service != SERVICE_HEARTRATE && command.service != SERVICE_HEARTRATE_V2) return null
        val payload = command.payload ?: return null
        if (payload.size != 18) return null
        if (payload[17].toInt() != 0) return null
        val bpm = payload[1].toInt() and 0xFF
        return if (bpm in 25..250) bpm else null
    }

    /**
     * Build the SensorServiceSetting message that asks the pods to report a sensor service
     * at [intervalMicros]: SensorDataWX{seq, service_settings{service, setting=2, config}}.
     */
    fun sensorIntervalSetting(seq: Int, service: Int, intervalMicros: Int): ByteArray {
        val config = byteArrayOf(
            0x01,
            (intervalMicros and 0xFF).toByte(),
            ((intervalMicros shr 8) and 0xFF).toByte(),
            ((intervalMicros shr 16) and 0xFF).toByte(),
            ((intervalMicros shr 24) and 0xFF).toByte()
        )
        val setting = buildMessage(
            // field 1: service (varint), field 2: setting (varint), field 3: configuration
            varintField(1, service.toLong()) +
                varintField(2, SETTING_REPORT_INTERVAL.toLong()) +
                bytesField(3, config)
        )
        return buildMessage(
            varintField(1, seq.toLong()) +
                bytesField(8, setting)
        )
    }

    /** Wrap a SensorDataWX payload into the 6-byte RTBuddy frame header. */
    fun frame(descriptor: Int, data: ByteArray): ByteArray = byteArrayOf(
        (descriptor and 0xFF).toByte(),
        ((descriptor shr 8) and 0xFF).toByte(),
        ((descriptor shr 16) and 0xFF).toByte(),
        ((descriptor shr 24) and 0xFF).toByte(),
        (data.size and 0xFF).toByte(),
        ((data.size shr 8) and 0xFF).toByte()
    ) + data

    // --- protobuf helpers -------------------------------------------------------------

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < data.size && shift <= 63) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    private fun varintField(field: Int, value: Long): ByteArray {
        val tag = (field shl 3) or 0
        return varint(tag.toLong()) + varint(value)
    }

    private fun bytesField(field: Int, value: ByteArray): ByteArray {
        val tag = (field shl 3) or 2
        return varint(tag.toLong()) + varint(value.size.toLong()) + value
    }

    private fun buildMessage(body: ByteArray): ByteArray = body

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>(10)
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.add(b.toByte())
                break
            }
            out.add((b or 0x80).toByte())
        }
        return out.toByteArray()
    }
}
