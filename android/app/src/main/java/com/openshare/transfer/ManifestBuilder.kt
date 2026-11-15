package com.openshare.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.openshare.core.ManifestEntry
import com.openshare.core.PathSafety
import com.openshare.core.TransferOffer
import java.security.MessageDigest
import java.util.UUID

class ManifestBuilder {
    data class Built(val offer: TransferOffer, val uriByEntryId: Map<String, Uri>)

    fun fromUris(resolver: ContentResolver, uris: List<Uri>, rootName: String = "Transfer"): Built {
        val entries = mutableListOf<ManifestEntry>()
        val uriById = mutableMapOf<String, Uri>()
        var total = 0L
        for (uri in uris) {
            if (isTree(uri)) {
                buildTree(resolver, uri, entries, uriById) { total += it }
            } else {
                val name = sanitizeName(queryName(resolver, uri) ?: "file-${entries.size}")
                val size = querySize(resolver, uri)
                require(PathSafety.isSafe(name)) { "Unsafe path: $name" }
                val id = UUID.randomUUID().toString()
                total += size
                entries.add(ManifestEntry(id, "file", name, size, null, null))
                uriById[id] = uri
            }
        }
        val computedRoot = rootName.ifBlank { "Transfer" }
        val offer = TransferOffer(UUID.randomUUID().toString(), total, entries.count { it.type == "file" }, computedRoot, entries)
        return Built(offer, uriById)
    }

    private fun buildTree(resolver: ContentResolver, treeUri: Uri, entries: MutableList<ManifestEntry>, uriById: MutableMap<String, Uri>, addTotal: (Long) -> Unit) {
        val docId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { return }
        val rootName = sanitizeName(queryDocName(resolver, treeUri, docId) ?: "folder")
        if (PathSafety.isSafe(rootName)) entries.add(ManifestEntry(UUID.randomUUID().toString(), "dir", rootName, null, null, null))
        walk(resolver, treeUri, docId, rootName, entries, uriById, addTotal)
    }

    private fun walk(resolver: ContentResolver, treeUri: Uri, parentDocId: String, parentPath: String, entries: MutableList<ManifestEntry>, uriById: MutableMap<String, Uri>, addTotal: (Long) -> Unit) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        resolver.query(childrenUri, proj, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val childId = c.getString(idIdx)
                val rawName = c.getString(nameIdx) ?: continue
                val name = sanitizeName(rawName)
                val mime = c.getString(mimeIdx)
                val rel = "$parentPath/$name"
                if (!PathSafety.isSafe(rel)) continue
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    entries.add(ManifestEntry(UUID.randomUUID().toString(), "dir", rel, null, null, null))
                    walk(resolver, treeUri, childId, rel, entries, uriById, addTotal)
                } else {
                    val size = if (!c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    val id = UUID.randomUUID().toString()
                    entries.add(ManifestEntry(id, "file", rel, size, null, null))
                    uriById[id] = childUri
                    addTotal(size)
                }
            }
        }
    }

    fun sha256Hex(resolver: ContentResolver, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L

    private fun queryDocName(resolver: ContentResolver, treeUri: Uri, docId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return queryName(resolver, docUri)
    }

    private fun isTree(uri: Uri): Boolean = try { DocumentsContract.isTreeUri(uri) } catch (_: Exception) { false }
    private fun sanitizeName(name: String): String = name.replace('/', '_').replace('\\', '_')
}
