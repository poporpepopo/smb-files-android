package io.github.poporpepopo.smbdocs

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.StrictMode
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import java.io.File as JFile
import java.io.FileNotFoundException
import java.io.IOException
import java.util.EnumSet

/**
 * Exposes one SMB share to the Storage Access Framework, so it shows up as a location in
 * the system Files app and in every app's file picker.
 *
 * Document ids are the path within the share, always leading-slash normalised: "/" is the
 * share root, "/Photos/a.png" is a file. SMB itself wants backslashes, so [smbPath] is the
 * single place that conversion happens.
 */
class SmbDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val TAG = "SmbDocsProvider"
        const val AUTHORITY = "io.github.poporpepopo.smbdocs.documents"
        const val ROOT_ID = "smb"
        const val ROOT_DOC_ID = "/"

        /**
         * Thumbnails need the whole image in memory, so anything bigger than this keeps its
         * generic icon rather than pulling tens of megabytes over the network on a scroll.
         */
        private const val MAX_THUMBNAIL_SOURCE_BYTES = 32L * 1024 * 1024

        private val DEFAULT_ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_ICON,
        )

        private val DEFAULT_DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }

    private lateinit var ioThread: HandlerThread
    private lateinit var ioHandler: Handler

    override fun onCreate(): Boolean {
        ioThread = HandlerThread("smb-fd").apply { start() }
        ioHandler = Handler(ioThread.looper)
        // Proxy-fd reads and deferred uploads run on this thread and talk to the network,
        // so it must not carry a policy that forbids that.
        ioHandler.post {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
        }
        return true
    }

    // ---- path helpers -------------------------------------------------------------

    /** "/a/b.txt" -> "a\b.txt"; the share root becomes "". */
    private fun smbPath(documentId: String): String =
        documentId.trim('/').replace('/', '\\')

    private fun childId(parentId: String, name: String): String =
        if (parentId == ROOT_DOC_ID) "/$name" else "$parentId/$name"

    private fun displayName(documentId: String): String =
        if (documentId == ROOT_DOC_ID) config().share else documentId.substringAfterLast('/')

    private fun parentOf(documentId: String): String {
        val trimmed = documentId.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        return if (cut <= 0) ROOT_DOC_ID else trimmed.substring(0, cut)
    }

    private fun mimeOf(name: String, isDir: Boolean): String {
        if (isDir) return Document.MIME_TYPE_DIR
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun config(): SmbConfigData = SmbSettings.load(context!!)

    private fun <T> withShare(block: (DiskShare) -> T): T {
        val cfg = config()
        if (!cfg.isComplete) throw FileNotFoundException("SMB connection is not configured")
        return SmbConnection.withShare(cfg, block)
    }

    // ---- roots --------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_COLUMNS)
        val cfg = config()
        if (!cfg.isComplete) return cursor // no root advertised until configured

        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, "//${cfg.host}/${cfg.share}")
            add(Root.COLUMN_ICON, R.drawable.ic_launcher_foreground)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or
                    Root.FLAG_SUPPORTS_IS_CHILD or
                    Root.FLAG_LOCAL_ONLY,
            )
        }
        cursor.setNotificationUri(
            context!!.contentResolver,
            android.provider.DocumentsContract.buildRootsUri(AUTHORITY),
        )
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT_DOC_ID || documentId.startsWith("$parentDocumentId/")

    // ---- reading metadata ---------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOC_COLUMNS)

        if (documentId == ROOT_DOC_ID) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(Document.COLUMN_DISPLAY_NAME, config().share)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
                add(Document.COLUMN_SIZE, null)
            }
            return cursor
        }

        val path = smbPath(documentId)
        val info = try {
            withShare { share -> share.getFileInformation(path) }
        } catch (e: Exception) {
            Log.w(TAG, "queryDocument($documentId) path='$path' failed", e)
            throw FileNotFoundException("$documentId: $e")
        }
        val isDir = info.standardInformation.isDirectory
        val name = displayName(documentId)

        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        flags = flags or if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(name, isDir))
            add(Document.COLUMN_LAST_MODIFIED, info.basicInformation.lastWriteTime.toEpochMillis())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (isDir) null else info.standardInformation.endOfFile)
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOC_COLUMNS)
        val path = smbPath(parentDocumentId)

        val entries = try {
            withShare { share -> share.list(path) }
        } catch (e: Exception) {
            Log.w(TAG, "queryChildDocuments($parentDocumentId) path='$path' failed", e)
            throw FileNotFoundException("$parentDocumentId: $e")
        }

        for (entry in entries) {
            val name = entry.fileName
            if (name == "." || name == "..") continue

            val isDir = (entry.fileAttributes and
                FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L

            var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            flags = flags or if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
            if (!isDir && mimeOf(name, false).startsWith("image/")) {
                flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
            }

            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, childId(parentDocumentId, name))
                add(Document.COLUMN_DISPLAY_NAME, name)
                add(Document.COLUMN_MIME_TYPE, mimeOf(name, isDir))
                add(Document.COLUMN_LAST_MODIFIED, entry.lastWriteTime.toEpochMillis())
                add(Document.COLUMN_FLAGS, flags)
                add(Document.COLUMN_SIZE, if (isDir) null else entry.endOfFile)
            }
        }
        // Without this the listing is a dead snapshot: DocumentsUI never registers an
        // observer, notifyChange() reaches nobody, and the UI keeps acting on document ids
        // that renames and deletes have already invalidated.
        cursor.setNotificationUri(
            context!!.contentResolver,
            android.provider.DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
        )
        return cursor
    }

    // ---- opening ------------------------------------------------------------------

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: android.os.CancellationSignal?,
    ): ParcelFileDescriptor {
        val path = smbPath(documentId)
        val wantsWrite = mode.contains('w')

        return if (!wantsWrite) {
            openForRead(documentId, path)
        } else {
            openForWrite(documentId, path)
        }
    }

    /**
     * Streams reads straight off the share through a proxy fd, so opening a 4 GB video
     * does not first copy 4 GB into the app cache.
     */
    private fun openForRead(documentId: String, path: String): ParcelFileDescriptor {
        val cfg = config()
        val storage = context!!.getSystemService(StorageManager::class.java)

        val handle = try {
            SmbConnection.withShare(cfg) { share ->
                share.openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "openForRead($documentId) path='$path' open failed", e)
            throw FileNotFoundException("$documentId: $e")
        }

        val size = try {
            handle.fileInformation.standardInformation.endOfFile
        } catch (e: Exception) {
            Log.w(TAG, "openForRead($documentId) size lookup failed", e)
            runCatching { handle.close() }
            throw FileNotFoundException("$documentId: $e")
        }

        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = size

            /**
             * Fills the whole requested range before returning. A single SMB read is capped
             * at the negotiated max (64 KB here) while the framework asks for 128 KB, and a
             * short return is taken as end-of-file — which silently truncated every file to
             * its first chunk.
             */
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                return try {
                    var filled = 0
                    while (filled < size) {
                        val n = handle.read(data, offset + filled, filled, size - filled)
                        if (n <= 0) break // -1 is EOF
                        filled += n
                    }
                    filled
                } catch (e: Exception) {
                    Log.e(TAG, "onRead off=$offset req=$size failed", e)
                    throw ErrnoException("onRead", OsConstants.EIO, e)
                }
            }

            override fun onRelease() {
                runCatching { handle.close() }
            }
        }
        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            callback,
            ioHandler,
        )
    }

    /**
     * Writes go to a cache file first and are uploaded when the caller closes the
     * descriptor. SMB has no way to hand out a writable fd, and buffering locally also
     * keeps a half-finished upload from truncating the file on the share.
     */
    private fun openForWrite(documentId: String, path: String): ParcelFileDescriptor {
        val cfg = config()
        val temp = JFile.createTempFile("upload", null, context!!.cacheDir)

        return ParcelFileDescriptor.open(
            temp,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
            ioHandler,
        ) { error ->
            try {
                if (error != null) {
                    Log.w(TAG, "write of $documentId failed locally", error)
                    return@open
                }
                SmbConnection.withShare(cfg) { share ->
                    share.openFile(
                        path,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        null,
                    ).use { remote ->
                        remote.outputStream.use { out -> temp.inputStream().use { it.copyTo(out) } }
                    }
                }
                notifyChange(parentOf(documentId))
            } catch (e: Exception) {
                Log.e(TAG, "upload of $documentId failed", e)
            } finally {
                temp.delete()
            }
        }
    }

    /**
     * DocumentsUI asks for these once a row advertises FLAG_SUPPORTS_THUMBNAIL. Declaring
     * the flag without implementing this throws on every image in the listing.
     */
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: android.os.CancellationSignal?,
    ): AssetFileDescriptor {
        val path = smbPath(documentId)
        val raw = try {
            withShare { share ->
                share.openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use { file ->
                    val size = file.fileInformation.standardInformation.endOfFile
                    if (size > MAX_THUMBNAIL_SOURCE_BYTES) {
                        throw IOException("too large to thumbnail: $size bytes")
                    }
                    file.inputStream.use { it.readBytes() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "openDocumentThumbnail($documentId) failed", e)
            throw FileNotFoundException("$documentId: $e")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizeHint.x, sizeHint.y)
        }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
            ?: throw FileNotFoundException("$documentId: not a decodable image")

        val temp = JFile.createTempFile("thumb", ".jpg", context!!.cacheDir)
        try {
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } finally {
            bitmap.recycle()
        }
        val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
        // The descriptor keeps the bytes alive after the name is gone, so the cache does not
        // accumulate one file per thumbnail ever rendered.
        temp.delete()
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun sampleSize(width: Int, height: Int, targetW: Int, targetH: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val tw = maxOf(targetW, 1)
        val th = maxOf(targetH, 1)
        var sample = 1
        while (width / (sample * 2) >= tw && height / (sample * 2) >= th) sample *= 2
        return sample
    }

    // ---- mutating -----------------------------------------------------------------

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val newId = childId(parentDocumentId, displayName)
        val path = smbPath(newId)
        try {
            withShare { share ->
                if (mimeType == Document.MIME_TYPE_DIR) {
                    share.mkdir(path)
                } else {
                    share.openFile(
                        path,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_CREATE,
                        null,
                    ).close()
                }
            }
        } catch (e: Exception) {
            throw FileNotFoundException("create $newId: ${e.message}")
        }
        notifyChange(parentDocumentId)
        return newId
    }

    override fun deleteDocument(documentId: String) {
        val path = smbPath(documentId)
        try {
            withShare { share ->
                if (share.folderExists(path)) share.rmdir(path, true) else share.rm(path)
            }
        } catch (e: Exception) {
            throw FileNotFoundException("delete $documentId: ${e.message}")
        }
        notifyChange(parentOf(documentId))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val parent = parentOf(documentId)
        val newId = childId(parent, displayName)
        val target = smbPath(newId)
        try {
            withShare { share ->
                val src = smbPath(documentId)
                if (share.folderExists(src)) {
                    share.openDirectory(
                        src,
                        EnumSet.of(AccessMask.GENERIC_ALL),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    ).use { it.rename(target) }
                } else {
                    share.openFile(
                        src,
                        EnumSet.of(AccessMask.GENERIC_ALL),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    ).use { it.rename(target) }
                }
            }
        } catch (e: Exception) {
            throw FileNotFoundException("rename $documentId: ${e.message}")
        }
        notifyChange(parent)
        return newId
    }

    private fun notifyChange(parentDocumentId: String) {
        val uri = android.provider.DocumentsContract.buildChildDocumentsUri(
            AUTHORITY, parentDocumentId,
        )
        context?.contentResolver?.notifyChange(uri, null)
    }
}
