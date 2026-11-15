package com.openshare.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openshare.core.HotspotQrPayload
import com.openshare.core.ProtocolInfo
import com.openshare.discovery.NsdDiscovery
import com.openshare.hotspot.HotspotHost
import com.openshare.network.Tls
import com.openshare.qr.QrPayloadCodec
import com.openshare.session.IncomingOffer
import com.openshare.session.SessionController
import com.openshare.transfer.ManifestBuilder
import com.openshare.transfer.TransferForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket
import java.util.UUID

enum class Mode { SAME_WIFI, HOTSPOT }
enum class Role { SEND, RECEIVE }

data class Peer(val id: String, val name: String, val host: String, val port: Int)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    var mode by mutableStateOf(Mode.SAME_WIFI)
    var role by mutableStateOf(Role.RECEIVE)
    var state by mutableStateOf("idle")
    var status by mutableStateOf("Select mode and role to begin.")
    var running by mutableStateOf(false)
    var peers by mutableStateOf(listOf<Peer>())
    var selectedUris by mutableStateOf<List<Uri>>(emptyList())
    var incomingOffer by mutableStateOf<IncomingOffer?>(null)
    var progressText by mutableStateOf("")
    var progressBytes by mutableStateOf(0L)
    var progressTotal by mutableStateOf(0L)
    var progressFilesDone by mutableStateOf(0)
    var progressFilesTotal by mutableStateOf(0)
    var transferStartMs by mutableStateOf<Long?>(null)
    var elapsedMs by mutableStateOf(0L)
    var totalDurationMs by mutableStateOf<Long?>(null)
    var hotspotQrJson by mutableStateOf("")
    var hotspotSsid by mutableStateOf("")
    var hotspotPassword by mutableStateOf("")
    var pendingSaveTreeUri by mutableStateOf<Uri?>(null)
    var sessionToken: String = UUID.randomUUID().toString()
        private set

    private val discovery = NsdDiscovery(app)
    private val hotspotHost = HotspotHost(app)
    private val manifestBuilder = ManifestBuilder()
    private var server: ServerSocket? = null
    private var session: SessionController? = null
    private var receiveCacheDir: File? = null
    private var elapsedJob: kotlinx.coroutines.Job? = null

    fun formatDuration(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    private fun startElapsedTimer(startMs: Long) {
        elapsedJob?.cancel(); elapsedMs = 0
        elapsedJob = viewModelScope.launch(Dispatchers.Main) {
            while (transferStartMs != null) {
                elapsedMs = System.currentTimeMillis() - startMs
                kotlinx.coroutines.delay(250)
            }
        }
    }

    private fun stopElapsedTimer(finalize: Boolean) {
        val start = transferStartMs
        if (start != null) {
            val d = System.currentTimeMillis() - start
            elapsedMs = d
            if (finalize) totalDurationMs = d
        }
        elapsedJob?.cancel(); elapsedJob = null
    }

    fun setSelected(uris: List<Uri>) { selectedUris = uris; status = "Selected ${uris.size} item(s)." }

    fun start(preflightError: String? = null) {
        // stop any previous session resources before starting fresh
        try { server?.close() } catch (_: Exception) {}
        server = null
        session?.cancel(); session = null
        discovery.stop(); hotspotHost.stop()

        if (preflightError != null) {
            running = false
            state = "failed"
            TransferForegroundService.stop(getApplication())
            status = preflightError
            return
        }

        running = true
        TransferForegroundService.start(getApplication(), "Preparing transfer…")
        sessionToken = UUID.randomUUID().toString()
        incomingOffer = null
        progressText = ""
        hotspotQrJson = ""
        hotspotSsid = ""; hotspotPassword = ""
        progressBytes = 0L; progressTotal = 0L; progressFilesDone = 0; progressFilesTotal = 0
        transferStartMs = null; elapsedMs = 0L; totalDurationMs = null; elapsedJob?.cancel()
        pendingSaveTreeUri = null
        receiveCacheDir = null
        if (mode == Mode.SAME_WIFI) startSameWifi() else startHotspot()
    }

    private fun startSameWifi() {
        state = "discovering"
        if (role == Role.RECEIVE) {
            status = "Starting receiver on port ${ProtocolInfo.DEFAULT_PORT}…"
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val srv = Tls.serverSocket(ProtocolInfo.DEFAULT_PORT)
                    server = srv
                    discovery.register("Android-${Build.MODEL}", ProtocolInfo.DEFAULT_PORT)
                    postStatus("Advertising on local network. Waiting for sender…")
                    val sock = srv.accept()
                    com.openshare.network.Tls.configure(sock)
                    val sc = newSession(SessionController.Role.RECEIVER)
                    session = sc
                    sc.attach(sock)
                } catch (e: Exception) {
                    if (running) { postStatus("Listen failed: ${e.message}"); postState("failed") }
                }
            }
        } else {
            discovery.discover { found -> peers = found }
            status = "Browsing for receivers…"
        }
    }

    private fun startHotspot() {
        if (role == Role.RECEIVE) {
            state = "pairing"
            status = "Starting hotspot (Local Only Hotspot)… Make sure Wi‑Fi and Location services are ON."
            hotspotHost.start(ProtocolInfo.DEFAULT_PORT, onReady = { payload ->
                try {
                    hotspotQrJson = QrPayloadCodec.encode(payload)
                } catch (e: Exception) {
                    status = "Hotspot ready but QR generation failed: ${e.message}. Use Advanced payload."
                }
                hotspotSsid = payload.ssid
                hotspotPassword = payload.passphrase ?: ""
                sessionToken = payload.token
                state = "pairing"
                status = "Hotspot ready (SSID: ${payload.ssid}). On Mac: join this Wi‑Fi using the password shown, then scan the OpenShare QR."
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val srv = Tls.serverSocket(ProtocolInfo.DEFAULT_PORT)
                        server = srv
                        val sock = srv.accept()
                        if (!running) { try { sock.close(); srv.close() } catch (_: Exception) {}; return@launch }
                        com.openshare.network.Tls.configure(sock)
                        val sc = newSession(SessionController.Role.RECEIVER)
                        session = sc
                        sc.attach(sock)
                    } catch (e: Exception) {
                        if (running) { postStatus("Hotspot receive failed: ${e.message ?: "listener error"}"); postState("failed") }
                    }
                }
            }, onFailed = { reason ->
                state = "failed"
                running = false
                TransferForegroundService.stop(getApplication())
                status = reason
            })
        } else {
            state = "pairing"
            status = "Join the host device’s hotspot Wi‑Fi in system settings, select files, then tap Scan Host QR."
        }
    }

    fun connectToPeer(peer: Peer) {
        if (role != Role.SEND) return
        if (selectedUris.isEmpty()) { status = "Select files/folders first."; return }
        state = "pairing"
        status = "Preparing transfer to ${peer.name}…"
        TransferForegroundService.start(getApplication(), "Connecting…")
        discovery.stop()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                postStatus("Building manifest…")
                val built = manifestBuilder.fromUris(getApplication<Application>().contentResolver, selectedUris)
                postStatus("Connecting to ${peer.name}…")
                val sock = Tls.clientSocket(peer.host, peer.port)
                val sc = newSession(SessionController.Role.SENDER)
                sc.setSendData(built.offer, built.uriByEntryId, getApplication<Application>().contentResolver)
                session = sc
                sc.attach(sock)
            } catch (e: Exception) { postStatus("Connect failed: ${e.message}"); postState("failed") }
        }
    }

    fun connectFromHotspotQr(text: String) {
        val trimmed = text.trim()
        // OpenShare hotspot payload (JSON)
        if (trimmed.startsWith("{")) {
            try {
                val p = QrPayloadCodec.decode(trimmed)
                sessionToken = p.token
                hotspotQrJson = trimmed
                if (role == Role.SEND && selectedUris.isEmpty()) { status = "Select files/folders first."; return }
                state = "pairing"; status = "Connecting to hotspot host ${p.host}:${p.port}…"
                TransferForegroundService.start(getApplication(), "Connecting…")
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val sock = Tls.clientSocket(p.host, p.port)
                        val sc = newSession(if (role == Role.SEND) SessionController.Role.SENDER else SessionController.Role.RECEIVER)
                        if (role == Role.SEND) {
                            val built = manifestBuilder.fromUris(getApplication<Application>().contentResolver, selectedUris)
                            sc.setSendData(built.offer, built.uriByEntryId, getApplication<Application>().contentResolver)
                        }
                        session = sc
                        sc.attach(sock)
                    } catch (e: Exception) { postStatus("Connect failed: ${e.message}. Ensure you joined the host hotspot Wi‑Fi."); postState("failed") }
                }
                return
            } catch (e: Exception) { status = "Invalid OpenShare QR payload: ${e.message}"; return }
        }
        // System Wi-Fi QR (e.g. Android hotspot QR): WIFI:S:<ssid>;T:<WPA|>;P:<pass>;;
        if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
            val ssid = Regex("S:([^;]*)").find(trimmed)?.groupValues?.getOrNull(1) ?: ""
            status = "Scanned system Wi‑Fi QR${if (ssid.isNotBlank()) " (SSID: $ssid)" else ""}. Join that hotspot in Android Wi‑Fi settings, then start Same Wi‑Fi mode on both devices to connect. For direct OpenShare hotspot pairing, scan the OpenShare payload QR shown by the host app."
            return
        }
        status = "Unsupported QR content. Scan a OpenShare hotspot payload QR (JSON) shown by the host."
    }

    fun acceptIncomingToChosenFolder(treeUri: Uri) {
        incomingOffer = null
        val dest = File(getApplication<Application>().cacheDir, "openshare-recv-${System.currentTimeMillis()}").apply { mkdirs() }
        receiveCacheDir = dest
        pendingSaveTreeUri = treeUri
        status = "Accepting… connecting transfer."
        TransferForegroundService.start(getApplication(), "Receiving files…")
        viewModelScope.launch(Dispatchers.IO) {
            try { session?.acceptOffer(dest) }
            catch (e: Exception) { postStatus("Accept failed: ${e.message}"); postState("failed") }
        }
    }

    fun declineIncoming() { session?.declineOffer("User declined"); incomingOffer = null }

    fun stop() {
        session?.cancel(); session = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        discovery.stop(); hotspotHost.stop()
        running = false; state = "idle"; peers = emptyList(); incomingOffer = null; progressText = ""; hotspotQrJson = ""; hotspotSsid = ""; hotspotPassword = ""
        stopElapsedTimer(finalize = false)
        TransferForegroundService.stop(getApplication())
        progressBytes = 0L; progressTotal = 0L; progressFilesDone = 0; progressFilesTotal = 0; pendingSaveTreeUri = null; receiveCacheDir = null
        status = "Stopped."
    }

    private fun newSession(r: SessionController.Role): SessionController = SessionController(r, sessionToken, "Android-${Build.MODEL}").apply {
        onStatus = { postStatus(it) }
        onState = { st ->
            if (st == SessionController.State.TRANSFERRING && transferStartMs == null) {
                val start = System.currentTimeMillis()
                viewModelScope.launch(Dispatchers.Main) { if (transferStartMs == null) { transferStartMs = start; totalDurationMs = null; startElapsedTimer(start) } }
            }
            if (st == SessionController.State.COMPLETE) viewModelScope.launch(Dispatchers.Main) { stopElapsedTimer(finalize = true) }
            if (st == SessionController.State.FAILED || st == SessionController.State.CANCELLED) viewModelScope.launch(Dispatchers.Main) { stopElapsedTimer(finalize = false) }
            postState(st.name.lowercase())
        }
        onProgress = { sent, total, done, files, name -> postStatusProgress(sent, total, done, files, name) }
        onIncomingOffer = { offer -> viewModelScope.launch(Dispatchers.Main) { incomingOffer = offer; status = "Incoming offer from ${offer.from}" } }
        onFailure = { postStatus(it) }
        onComplete = {
            viewModelScope.launch(Dispatchers.Main) { stopElapsedTimer(finalize = true) }
            val tree = pendingSaveTreeUri
            if (tree != null) {
                postStatus("Transfer received. Saving to selected folder…")
                viewModelScope.launch(Dispatchers.IO) {
                    val ok = try { copyCacheToTree(tree) } catch (_: Exception) { false }
                    if (ok) postStatus("Transfer complete. Saved to selected folder.") else postStatus("Transfer received but failed to copy to selected folder (check permissions/space).")
                    TransferForegroundService.stop(getApplication())
                }
            } else {
                postStatus("Transfer complete.")
                TransferForegroundService.stop(getApplication())
            }
        }
    }

    private fun copyCacheToTree(treeUri: Uri): Boolean {
        val app = getApplication<Application>()
        val root = receiveCacheDir?.takeIf { it.exists() } ?: File(app.cacheDir.absolutePath).listFiles()?.filter { it.isDirectory && it.name.startsWith("openshare-recv-") }?.maxByOrNull { it.lastModified() } ?: return false
        val resolver = app.contentResolver
        val treeDocId = try { android.provider.DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { return false }
        val rootUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        fun ensureDir(parentUri: Uri, parentDocId: String, name: String): Pair<Uri, String>? {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            resolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                val idI = c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameI = c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeI = c.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    if (c.getString(nameI) == name && c.getString(mimeI) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                        val id = c.getString(idI)
                        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        return uri to id
                    }
                }
            }
            val created = android.provider.DocumentsContract.createDocument(resolver, parentUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, name) ?: return null
            val id = android.provider.DocumentsContract.getDocumentId(created)
            return created to id
        }
        fun copyFile(src: File, parentUri: Uri, name: String): Boolean {
            val mime = guessMime(name)
            val target = android.provider.DocumentsContract.createDocument(resolver, parentUri, mime, name) ?: return false
            resolver.openOutputStream(target, "w")?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: return false
            return true
        }
        fun copyRec(dir: File, parentUri: Uri, parentDocId: String): Boolean {
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith(".openshare-staging-")) return@forEach
                if (f.isDirectory) {
                    val d = ensureDir(parentUri, parentDocId, f.name) ?: return false
                    if (!copyRec(f, d.first, d.second)) return false
                } else {
                    if (!copyFile(f, parentUri, f.name)) return false
                }
            }
            return true
        }
        val ok = copyRec(root, rootUri, treeDocId)
        if (ok) runCatching { root.deleteRecursively() }
        pendingSaveTreeUri = null
        receiveCacheDir = null
        return ok
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg","jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "pdf" -> "application/pdf"; "txt" -> "text/plain"
            "zip" -> "application/zip"; "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun postStatus(msg: String) { viewModelScope.launch(Dispatchers.Main) { status = msg } }
    private fun postState(s: String) {
        viewModelScope.launch(Dispatchers.Main) {
            state = s
            if (s == "failed" || s == "cancelled") TransferForegroundService.stop(getApplication())
            if (s == "complete" && pendingSaveTreeUri == null) TransferForegroundService.stop(getApplication())
        }
    }
    private fun postStatusProgress(sent: Long, total: Long, done: Int, files: Int, name: String) {
        viewModelScope.launch(Dispatchers.Main) {
            progressBytes = sent; progressTotal = total; progressFilesDone = done; progressFilesTotal = files
            progressText = "$done/$files files • ${formatBytes(sent)} / ${formatBytes(total)} • $name"
        }
    }

    override fun onCleared() { stop(); super.onCleared() }
}
