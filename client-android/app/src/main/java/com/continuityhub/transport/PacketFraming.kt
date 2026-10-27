package com.continuityhub.transport

import com.continuityhub.proto.Packet
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

object PacketFraming {

    @Synchronized
    fun writePacket(output: OutputStream, packet: Packet) {
        val payload = packet.toByteArray()
        val lengthBuffer = ByteBuffer.allocate(4).putInt(payload.size).array()
        output.write(lengthBuffer)
        output.write(payload)
        output.flush()
    }

    @Synchronized
    fun readPacket(input: InputStream): Packet {
        val lengthHeader = ByteArray(4)
        readFully(input, lengthHeader)
        val length = ByteBuffer.wrap(lengthHeader).int

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
