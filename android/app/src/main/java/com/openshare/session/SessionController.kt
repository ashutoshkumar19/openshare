package com.openshare.session

import android.content.ContentResolver
import android.net.Uri
import com.openshare.core.*
import com.openshare.network.ChunkCodec
import com.openshare.network.FramedSocket
import com.openshare.transfer.ManifestBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class IncomingOffer(val offer: TransferOffer, val from: String)

class SessionController(
    private val role: Role,
    private var token: String,
    private val deviceName: String,
) {
    enum class Role { SENDER, RECEIVER }
    enum class State { IDLE, PAIRING, AWAITING_ACCEPT, TRANSFERRING, VERIFYING, COMPLETE, FAILED, CANCELLED }

    var onStatus: (String) -> Unit = {}
    var onState: (State) -> Unit = {}
    var onProgress: (Long, Long, Int, Int, String) -> Unit = { _,_,_,_,_ -> }
    var onIncomingOffer: (IncomingOffer) -> Unit = {}
    var onComplete: () -> Unit = {}
    var onFailure: (String) -> Unit = {}

    private var socket: FramedSocket? = null
    private val running = AtomicBoolean(true)
    private var peer: Hello? = null
    private var pendingOffer: TransferOffer? = null
    private var sendOffer: TransferOffer? = null
    private var sendUris: Map<String, Uri>? = null
    private var resolver: ContentResolver? = null
    private var destRoot: File? = null
    private var stagingRoot: File? = null
    private var receiveState: ReceiveState? = null
    private val builder = ManifestBuilder()
    private var offerSent = false
    private var keepaliveThread: Thread? = null
    private val ackLock = Object()
    @Volatile private var ackedFileId: String? = null
    @Volatile private var ackedChunkIndex: Long? = null

    private fun safeOnState(s: State) { try { onState(s) } catch (_: Exception) {} }
    private fun safeOnStatus(m: String) { try { onStatus(m) } catch (_: Exception) {} }
    private fun safeOnProgress(sent: Long, total: Long, done: Int, files: Int, name: String) { try { onProgress(sent,total,done,files,name) } catch (_: Exception) {} }
    private fun safeOnIncomingOffer(o: IncomingOffer) { try { onIncomingOffer(o) } catch (_: Exception) {} }
    private fun safeOnComplete() { try { onComplete() } catch (_: Exception) {} }
    private fun safeOnFailure(m: String) { try { onFailure(m) } catch (_: Exception) {} }


    fun setSendData(offer: TransferOffer, uriByEntryId: Map<String, Uri>, resolver: ContentResolver) {
        this.sendOffer = offer
        this.sendUris = uriByEntryId
        this.resolver = resolver
    }

    fun attach(raw: Socket) {
        val fs = FramedSocket(raw)
        socket = fs
        safeOnState(State.PAIRING)
        // Sender initiates handshake with authoritative session token; receiver waits and adopts it.
        if (role == Role.SENDER) {
            safeOnStatus("Handshaking…")
            sendHello(fs)
            thread(name = "openshare-offer", isDaemon = true) {
                try { Thread.sleep(150) } catch (_: Exception) {}
                sendOfferIfReady()
            }
        } else {
            safeOnStatus("Waiting for sender handshake…")
        }
        thread(name = "openshare-recv", isDaemon = true) { readLoop(fs) }
        startKeepalive()
    }

    fun acceptOffer(destination: File) {
        val offer = pendingOffer ?: return
        try {
            destRoot = destination
            destination.mkdirs()
            stagingRoot = File(destination, ".openshare-staging-${offer.transferId}").apply { mkdirs() }
            val usable = destination.usableSpace
            if (usable in 1 until offer.totalBytes) { sendError(ProtocolErrorCode.DISK_FULL, "Insufficient disk space"); fail("Insufficient disk space"); return }
            receiveState = ReceiveState(offer)
            offer.entries.filter { it.type == "dir" && PathSafety.isSafe(it.path) }.forEach { File(destination, it.path).mkdirs() }
            val fs = socket ?: run { fail("Not connected"); return }
            fs.send(FrameType.ACCEPT, json(AcceptMsg(offer.transferId)).toByteArray())
            safeOnState(State.TRANSFERRING); safeOnStatus("Receiving…")
        } catch (e: Exception) {
            try { sendError(ProtocolErrorCode.IO_ERROR, e.message ?: "Accept failed") } catch (_: Exception) {}
            fail("Accept failed: ${e.message ?: "connection error"}")
        }
    }

    fun declineOffer(reason: String? = null) {
        val offer = pendingOffer ?: return
        socket?.send(FrameType.DECLINE, json(DeclineMsg(offer.transferId, reason)).toByteArray())
        safeOnState(State.CANCELLED)
    }

    fun cancel() {
        running.set(false)
        try { socket?.send(FrameType.CANCEL, json(CancelMsg("user")).toByteArray()) } catch (_: Exception) {}
        synchronized(ackLock) { (ackLock as Object).notifyAll() }
        socket?.close(); cleanupStaging(); safeOnState(State.CANCELLED)
    }



    private fun startKeepalive() {
        stopKeepalive()
        keepaliveThread = thread(name = "openshare-keepalive", isDaemon = true) {
            while (running.get()) {
                try { Thread.sleep(5000) } catch (_: Exception) { break }
                if (!running.get()) break
                try { socket?.send(FrameType.PING, ByteArray(0)) } catch (_: Exception) {}
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveThread?.interrupt()
        keepaliveThread = null
    }

    private fun readLoop(fs: FramedSocket) {
        try {
            while (running.get()) {
                val frame = fs.readFrame() ?: break
                try { handle(frame.first, frame.second) } catch (e: Exception) { fail(e.message ?: "Session error"); return }
            }
        } catch (e: Exception) {
            if (running.get()) fail("Connection error: ${e.message}")
            return
        }
        if (running.get()) fail("Connection closed")
    }

    private fun handle(type: FrameType, payload: ByteArray) {
        when (type) {
            FrameType.HELLO -> onHello(payload)
            FrameType.HELLO_ACK -> onHelloAck(payload)
            FrameType.TRANSFER_OFFER -> onOffer(payload)
            FrameType.ACCEPT -> onAccept()
            FrameType.DECLINE -> fail("Receiver declined")
            FrameType.CHUNK -> onChunk(payload)
            FrameType.CHUNK_ACK -> {
                if (role == Role.SENDER) {
                    val o = try { JSONObject(String(payload)) } catch (_: Exception) { null } ?: return
                    synchronized(ackLock) { ackedFileId = o.optString("fileId"); ackedChunkIndex = o.optLong("chunkIndex", -1L); (ackLock as Object).notifyAll() }
                }
            }
            FrameType.FILE_DONE -> onFileDone(payload)
            FrameType.FILE_VERIFIED -> {}
            FrameType.COMPLETE -> if (role == Role.SENDER) { safeOnState(State.COMPLETE); safeOnStatus("Transfer complete"); safeOnComplete() }
            FrameType.CANCEL -> { running.set(false); cleanupStaging(); fail("Cancelled by peer") }
            FrameType.ERROR -> fail(parseError(payload))
            FrameType.PING -> socket?.send(FrameType.PONG, ByteArray(0))
            else -> {}
        }
    }

    private fun onHello(payload: ByteArray) {
        val h = parseHello(payload) ?: return
        peer = h
        if (h.v != ProtocolInfo.VERSION) { socket?.send(FrameType.HELLO_ACK, json(HelloAck(false, "VERSION_UNSUPPORTED")).toByteArray()); fail("Version unsupported"); return }
        if (role == Role.RECEIVER) {
            token = h.token
        } else {
            if (h.token != token) { socket?.send(FrameType.HELLO_ACK, json(HelloAck(false, "TOKEN_INVALID")).toByteArray()); fail("Token mismatch"); return }
        }
        socket?.send(FrameType.HELLO_ACK, json(HelloAck(true)).toByteArray())
        if (role == Role.SENDER) sendOffer?.let {
            socket?.send(FrameType.TRANSFER_OFFER, offerJson(it).toByteArray())
            safeOnState(State.AWAITING_ACCEPT); safeOnStatus("Offer sent, waiting for accept…")
        } else if (role == Role.RECEIVER) {
            safeOnStatus("Connected. Waiting for offer…")
        }
    }

    private fun onHelloAck(payload: ByteArray) {
        val obj = try { JSONObject(String(payload)) } catch (_: Exception) { null }
        if (obj != null && obj.has("ok") && !obj.optBoolean("ok", false)) {
            val reason = if (obj.isNull("reason")) null else obj.optString("reason", null)
            fail(reason ?: "Handshake rejected"); return
        }
        sendOfferIfReady()
    }

    private fun sendOfferIfReady() {
        if (role != Role.SENDER || offerSent) return
        val offer = sendOffer ?: return
        offerSent = true
        try { socket?.send(FrameType.TRANSFER_OFFER, offerJson(offer).toByteArray()) } catch (e: Exception) { fail(e.message ?: "Failed to send offer"); return }
        safeOnState(State.AWAITING_ACCEPT); safeOnStatus("Offer sent, waiting for accept…")
    }

    private fun onOffer(payload: ByteArray) {
        if (role != Role.RECEIVER) return
        val offer = parseOffer(payload) ?: return
        pendingOffer = offer
        safeOnState(State.AWAITING_ACCEPT)
        safeOnIncomingOffer(IncomingOffer(offer, peer?.deviceName ?: "Peer"))
    }

    private fun onAccept() {
        if (role != Role.SENDER) return
        val offer = sendOffer ?: return
        val map = sendUris ?: return
        val res = resolver ?: return
        safeOnState(State.TRANSFERRING); safeOnStatus("Sending…")
        thread(name = "openshare-send", isDaemon = true) { sendTransfer(offer, map, res) }
    }

    private fun sendChunkAndWaitAck(fileId: String, chunkIndex: Long, endOfFile: Boolean, data: ByteArray) {
        synchronized(ackLock) { ackedFileId = null; ackedChunkIndex = null }
        socket?.send(FrameType.CHUNK, ChunkCodec.encode(fileId, chunkIndex, endOfFile, com.openshare.network.Crc32.checksum(data), data))
        val deadline = System.currentTimeMillis() + 60_000
        synchronized(ackLock) {
            while (!(ackedFileId == fileId && ackedChunkIndex == chunkIndex)) {
                if (!running.get()) throw RuntimeException("Cancelled")
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw RuntimeException("Timed out waiting for receiver acknowledgement")
                try { (ackLock as Object).wait(remaining.coerceAtMost(500)) } catch (_: InterruptedException) { throw RuntimeException("Cancelled") }
            }
        }
    }

    private fun sendTransfer(offer: TransferOffer, map: Map<String, Uri>, res: ContentResolver) {
        try {
            var sent = 0L
            var filesDone = 0
            val totalFiles = offer.fileCount
            for (e in offer.entries.filter { it.type == "file" }) {
                if (!running.get()) throw RuntimeException("Cancelled")
                val uri = map[e.id] ?: continue
                val hash = sha256(res, uri)
                val input = res.openInputStream(uri) ?: continue
                input.use { ins ->
                    val buf = ByteArray(ProtocolInfo.CHUNK_SIZE)
                    var idx = 0L
                    var read: Int
                    var totalRead = 0L
                    val size = e.size ?: -1L
                    if (size == 0L) {
                        sendChunkAndWaitAck(e.id, 0, true, ByteArray(0))
                    } else {
                        while (running.get()) {
                            read = ins.read(buf)
                            if (read <= 0) break
                            totalRead += read
                            val eof = if (size >= 0) totalRead >= size else read < buf.size
                            val data = if (read == buf.size) buf else buf.copyOf(read)
                            sendChunkAndWaitAck(e.id, idx++, eof, data)
                            sent += read
                            safeOnProgress(sent, offer.totalBytes, filesDone, totalFiles, e.path)
                            if (eof) break
                        }
                    }
                }
                socket?.send(FrameType.FILE_DONE, json(FileDoneMsg(e.id, hash)).toByteArray())
                filesDone++
                safeOnProgress(sent, offer.totalBytes, filesDone, totalFiles, e.path)
            }
            safeOnState(State.VERIFYING); safeOnStatus("All data received by receiver. Verifying…")
        } catch (e: Exception) { fail(e.message ?: "Send failed") }
    }

    private fun onChunk(payload: ByteArray) {
        if (role != Role.RECEIVER) return
        val rs = receiveState ?: return
        val ch = ChunkCodec.decode(payload) ?: return
        val entry = rs.entryById[ch.fileId] ?: return
        if (!PathSafety.isSafe(entry.path)) { sendError(ProtocolErrorCode.PATH_UNSAFE, "Unsafe path"); return }
        val staging = stagingRoot ?: return
        try {
            if (ch.crc32c != 0) {
                val actual = com.openshare.network.Crc32.checksum(ch.data)
                if (actual != ch.crc32c) { sendError(ProtocolErrorCode.HASH_MISMATCH, "Chunk CRC mismatch: ${entry.path}"); fail("Chunk CRC mismatch"); return }
            }
            val part = File(staging, entry.path + ".part").apply { parentFile?.mkdirs() }
            FileOutputStream(part, true).use { it.write(ch.data); it.flush() }
            rs.bytesReceived += ch.data.size
            socket?.send(FrameType.CHUNK_ACK, json(ChunkAckMsg(ch.fileId, ch.chunkIndex)).toByteArray())
            safeOnProgress(rs.bytesReceived, rs.offer.totalBytes, rs.filesVerified, rs.offer.fileCount, entry.path)
        } catch (e: Exception) { sendError(ProtocolErrorCode.IO_ERROR, e.message ?: "IO"); fail(e.message ?: "IO") }
    }

    private fun onFileDone(payload: ByteArray) {
        if (role != Role.RECEIVER) return
        val o = try { JSONObject(String(payload)) } catch (_: Exception) { return }
        val fileId = o.optString("fileId")
        val expected = o.optString("sha256")
        val rs = receiveState ?: return
        val entry = rs.entryById[fileId] ?: return
        val staging = stagingRoot ?: return
        val dest = destRoot ?: return
        try {
            val part = File(staging, entry.path + ".part")
            val actual = sha256File(part)
            if (!actual.equals(expected, ignoreCase = true)) { sendError(ProtocolErrorCode.HASH_MISMATCH, entry.path); fail("Hash mismatch: ${entry.path}"); return }
            val out = File(dest, entry.path).apply { parentFile?.mkdirs() }
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
            rs.filesVerified++
            safeOnProgress(rs.bytesReceived, rs.offer.totalBytes, rs.filesVerified, rs.offer.fileCount, entry.path)
            socket?.send(FrameType.FILE_VERIFIED, json(FileVerifiedMsg(fileId)).toByteArray())
            if (rs.filesVerified >= rs.offer.fileCount) {
                socket?.send(FrameType.COMPLETE, json(CompleteMsg(rs.offer.transferId)).toByteArray())
                cleanupStaging(); safeOnState(State.COMPLETE); safeOnStatus("Transfer complete"); safeOnComplete()
            }
        } catch (e: Exception) { sendError(ProtocolErrorCode.IO_ERROR, e.message ?: "IO"); fail(e.message ?: "Finalize failed") }
    }

    private fun sendHello(fs: FramedSocket) {
        val h = Hello(ProtocolInfo.VERSION, UUID.randomUUID().toString(), deviceName, "android", token, listOf("files","folders","sha256"))
        fs.send(FrameType.HELLO, json(h).toByteArray())
    }

    private fun sendError(code: ProtocolErrorCode, message: String) {
        try { socket?.send(FrameType.ERROR, json(ErrorMsg(code.code, message)).toByteArray()) } catch (_: Exception) {}
    }

    private fun fail(msg: String) { running.set(false); synchronized(ackLock) { ackedFileId = "__failed__"; ackedChunkIndex = -1L; (ackLock as Object).notifyAll() }; safeOnState(State.FAILED); safeOnFailure(msg); safeOnStatus(msg) }
    private fun cleanupStaging() { stagingRoot?.deleteRecursively() }

    private fun json(v: Any): String = when (v) {
        is Hello -> JSONObject().put("v", v.v).put("deviceId", v.deviceId).put("deviceName", v.deviceName).put("platform", v.platform).put("token", v.token).put("caps", JSONArray(v.caps)).toString()
        is HelloAck -> JSONObject().put("ok", v.ok).put("reason", v.reason).toString()
        is AcceptMsg -> JSONObject().put("transferId", v.transferId).toString()
        is DeclineMsg -> JSONObject().put("transferId", v.transferId).put("reason", v.reason).toString()
        is FileDoneMsg -> JSONObject().put("fileId", v.fileId).put("sha256", v.sha256).toString()
        is FileVerifiedMsg -> JSONObject().put("fileId", v.fileId).toString()
        is CompleteMsg -> JSONObject().put("transferId", v.transferId).toString()
        is CancelMsg -> JSONObject().put("reason", v.reason).toString()
        is ErrorMsg -> JSONObject().put("code", v.code).put("message", v.message).toString()
        is ChunkAckMsg -> JSONObject().put("fileId", v.fileId).put("chunkIndex", v.chunkIndex).toString()
        else -> "{}"
    }

    private fun offerJson(o: TransferOffer): String {
        val arr = JSONArray()
        o.entries.forEach { e -> arr.put(JSONObject().put("id", e.id).put("type", e.type).put("path", e.path).put("size", e.size).put("sha256", e.sha256).put("mtime", e.mtime)) }
        return JSONObject().put("transferId", o.transferId).put("totalBytes", o.totalBytes).put("fileCount", o.fileCount).put("rootName", o.rootName).put("entries", arr).toString()
    }

    private fun parseHello(p: ByteArray): Hello? = try { val o = JSONObject(String(p)); Hello(o.getInt("v"), o.getString("deviceId"), o.getString("deviceName"), o.getString("platform"), o.getString("token"), o.getJSONArray("caps").let { a -> (0 until a.length()).map { i -> a.getString(i) } }) } catch (_: Exception) { null }
    private fun parseOffer(p: ByteArray): TransferOffer? = try {
        val o = JSONObject(String(p)); val arr = o.getJSONArray("entries")
        val entries = (0 until arr.length()).map { i -> val e = arr.getJSONObject(i); ManifestEntry(e.getString("id"), e.getString("type"), e.getString("path"), if (e.has("size") && !e.isNull("size")) e.getLong("size") else null, e.optString("sha256", null), if (e.has("mtime") && !e.isNull("mtime")) e.getLong("mtime") else null) }
        TransferOffer(o.getString("transferId"), o.getLong("totalBytes"), o.getInt("fileCount"), o.getString("rootName"), entries)
    } catch (_: Exception) { null }
    private fun parseError(p: ByteArray): String = try { JSONObject(String(p)).optString("message", "Remote error") } catch (_: Exception) { "Remote error" }

    private fun sha256(res: ContentResolver, uri: Uri): String = builder.sha256Hex(res, uri)
    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { ins -> val buf = ByteArray(1024 * 1024); while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ReceiveState(val offer: TransferOffer) {
        val entryById = offer.entries.associateBy { it.id }
        var bytesReceived = 0L
        var filesVerified = 0
    }
}
