package com.openshare.network

import com.openshare.core.FrameType
import com.openshare.core.ProtocolInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.nio.ByteBuffer

class FramedSocket(private val socket: Socket) {
    private val out = DataOutputStream(socket.getOutputStream().buffered())
    private val input = DataInputStream(socket.getInputStream().buffered())

    @Synchronized
    fun send(type: FrameType, payload: ByteArray) {
        require(payload.size + 1 <= ProtocolInfo.MAX_FRAME_PAYLOAD)
        out.writeInt(payload.size + 1)
        out.writeByte(type.code)
        out.write(payload)
        out.flush()
    }

    fun readFrame(): Pair<FrameType, ByteArray>? {
        val len = try { input.readInt() } catch (_: EOFException) { return null } catch (_: Exception) { return null }
        if (len < 1 || len > ProtocolInfo.MAX_FRAME_PAYLOAD + 1) return null
        val typeCode = try { input.readUnsignedByte() } catch (_: Exception) { return null }
        val payload = ByteArray(len - 1)
        try { input.readFully(payload) } catch (_: Exception) { return null }
        val type = FrameType.from(typeCode) ?: FrameType.ERROR
        return type to payload
    }

    fun close() = try { socket.close() } catch (_: Exception) {}
}

data class ChunkHeader(
    val fileId: String,
    val chunkIndex: Long,
    val endOfFile: Boolean,
    val crc32c: Int,
    val data: ByteArray,
)

object ChunkCodec {
    fun encode(fileId: String, chunkIndex: Long, endOfFile: Boolean, crc32c: Int, data: ByteArray): ByteArray {
        val idBytes = fileId.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(2 + idBytes.size + 8 + 1 + 4 + data.size)
        buf.putShort(idBytes.size.toShort())
        buf.put(idBytes)
        buf.putLong(chunkIndex)
        buf.put((if (endOfFile) 1 else 0).toByte())
        buf.putInt(crc32c)
        buf.put(data)
        return buf.array()
    }

    fun decode(payload: ByteArray): ChunkHeader? {
        return try {
            val buf = ByteBuffer.wrap(payload)
            val idLen = buf.short.toInt() and 0xFFFF
            val idBytes = ByteArray(idLen)
            buf.get(idBytes)
            val idx = buf.long
            val flags = buf.get().toInt()
            val crc = buf.int
            val data = ByteArray(buf.remaining())
            buf.get(data)
            ChunkHeader(String(idBytes, Charsets.UTF_8), idx, (flags and 1) == 1, crc, data)
        } catch (_: Exception) { null }
    }
}


object Crc32 {
    private val table: IntArray = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
        c
    }
    fun checksum(data: ByteArray): Int {
        var c = -1
        for (b in data) { c = table[(c xor b.toInt()) and 0xFF] xor (c ushr 8) }
        return c xor -1
    }
}

