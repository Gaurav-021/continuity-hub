package com.continuityhub.transport

import com.continuityhub.proto.Packet
import com.google.protobuf.CodedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object PacketFraming {

    fun writePacket(output: OutputStream, packet: Packet) {
        val size = packet.serializedSize

        // For packets <= 64KB (input, audio, control, telemetry, chunks), combine
        // 4-byte length prefix + Protobuf payload into a single atomic write to avoid
        // TCP segment / TLS record fragmentation.
        if (size <= 65536) {
            val combined = ByteArray(4 + size)
            combined[0] = (size ushr 24).toByte()
            combined[1] = (size ushr 16).toByte()
            combined[2] = (size ushr 8).toByte()
            combined[3] = size.toByte()

            val cos = CodedOutputStream.newInstance(combined, 4, size)
            packet.writeTo(cos)
            cos.flush()

            output.write(combined, 0, 4 + size)
            output.flush()
        } else {
            // For large frames (video/raw), stream directly without duplicate toByteArray() heap allocations
            val header = ByteArray(4)
            header[0] = (size ushr 24).toByte()
            header[1] = (size ushr 16).toByte()
            header[2] = (size ushr 8).toByte()
            header[3] = size.toByte()

            output.write(header)
            packet.writeTo(output)
            output.flush()
        }
    }

    fun readPacket(input: InputStream): Packet {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if ((b0 or b1 or b2 or b3) < 0) {
            throw EOFException("Stream closed while reading packet frame header")
        }
        val length = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3

        if (length < 0 || length > 16 * 1024 * 1024) {
            throw IllegalArgumentException("Invalid packet frame length: $length")
        }

        val payload = ByteArray(length)
        readFully(input, payload)
        return Packet.parseFrom(payload)
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val bytesRead = input.read(buffer, offset, buffer.size - offset)
            if (bytesRead == -1) {
                throw EOFException("Stream closed prematurely while reading packet frame")
            }
            offset += bytesRead
        }
    }
}
