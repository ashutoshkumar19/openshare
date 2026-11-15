package com.openshare.core

object ProtocolInfo {
    const val VERSION = 1
    const val SERVICE_TYPE = "_openshare._tcp."
    const val DEFAULT_PORT = 8765
    const val MAX_FRAME_PAYLOAD = 1_048_576
    const val CHUNK_SIZE = 256 * 1024
}

enum class FrameType(val code: Int) {
    HELLO(0x01), HELLO_ACK(0x02), TRANSFER_OFFER(0x03), ACCEPT(0x04), DECLINE(0x05),
    CHUNK(0x06), CHUNK_ACK(0x07), FILE_DONE(0x08), FILE_VERIFIED(0x09),
    COMPLETE(0x0A), CANCEL(0x0B), ERROR(0x0C), PING(0x0D), PONG(0x0E);
    companion object { fun from(code: Int) = entries.firstOrNull { it.code == code } }
}

data class ManifestEntry(
    val id: String,
    val type: String, // "file" | "dir"
    val path: String,
    val size: Long? = null,
    val sha256: String? = null,
    val mtime: Long? = null,
)

data class TransferOffer(
    val transferId: String,
    val totalBytes: Long,
    val fileCount: Int,
    val rootName: String,
    val entries: List<ManifestEntry>,
)

data class Hello(
    val v: Int,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val token: String,
    val caps: List<String>,
)
data class HelloAck(val ok: Boolean, val reason: String? = null)
data class AcceptMsg(val transferId: String)
data class DeclineMsg(val transferId: String, val reason: String? = null)
data class FileDoneMsg(val fileId: String, val sha256: String)
data class FileVerifiedMsg(val fileId: String)
data class CompleteMsg(val transferId: String)
data class CancelMsg(val reason: String? = null)
data class ErrorMsg(val code: String, val message: String)
data class ChunkAckMsg(val fileId: String, val chunkIndex: Long)

data class HotspotQrPayload(
    val mode: String,
    val v: Int,
    val ssid: String,
    val passphrase: String?,
    val host: String,
    val port: Int,
    val token: String,
    val name: String,
)

enum class ProtocolErrorCode(val code: String) {
    TOKEN_INVALID("TOKEN_INVALID"),
    VERSION_UNSUPPORTED("VERSION_UNSUPPORTED"),
    DECLINED("DECLINED"),
    PATH_UNSAFE("PATH_UNSAFE"),
    HASH_MISMATCH("HASH_MISMATCH"),
    DISK_FULL("DISK_FULL"),
    IO_ERROR("IO_ERROR"),
    CANCELLED("CANCELLED"),
    TIMEOUT("TIMEOUT"),
    NETWORK_ISOLATION_SUSPECTED("NETWORK_ISOLATION_SUSPECTED"),
}

object PathSafety {
    fun normalized(path: String): String = path.replace('\\', '/')
    fun isSafe(relativePath: String): Boolean {
        val p = normalized(relativePath)
        if (p.isEmpty() || p.startsWith("/")) return false
        if (p.contains('\u0000')) return false
        val parts = p.split('/')
        if (parts.any { it.isEmpty() || it == ".." }) return false
        return true
    }
}
