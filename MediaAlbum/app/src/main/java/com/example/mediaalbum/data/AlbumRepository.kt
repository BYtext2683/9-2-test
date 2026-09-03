package com.example.mediaalbum.data

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.example.mediaalbum.util.FileStore
import com.example.mediaalbum.util.MimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

data class ImportResult(val imported: Int, val skipped: Int)

data class ImportProgress(
    val current: Int,
    val total: Int,
    val stage: String
)

class AlbumRepository(
    private val app: Application,
    private val db: AppDatabase
) {

    private val resolver get() = app.contentResolver

    fun albums() = db.albumDao().observeAll()
    fun itemsForAlbum(albumId: Long?) = db.mediaItemDao().observeByAlbum(albumId)
    fun itemsAll() = db.mediaItemDao().observeAll()
    fun itemsUncategorized() = db.mediaItemDao().observeUncategorized()

    // ---------------- import ----------------

    suspend fun importFrom(
        uris: List<Uri>,
        albumId: Long?,
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult {
        val picked = uris.mapNotNull { uri ->
            val name = queryName(uri)
            val mime = MimeUtils.normalize(name, resolver.getType(uri))
            if (mime == null) null else Triple(uri, name ?: defaultName(mime), mime)
        }
        return importPicked(picked, albumId, skipped = uris.size - picked.size, onProgress)
    }

    /** 从「选择文件夹」得到的树 URI 里递归找受支持的文件后导入。 */
    suspend fun importFromTree(
        treeUri: Uri,
        albumId: Long?,
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val rootDir = try {
                DocumentFile.fromTreeUri(app, treeUri)
            } catch (e: Exception) {
                null
            }
            if (rootDir == null) return@withContext ImportResult(0, 0)

            val found = mutableListOf<Uri>()
            collect(rootDir, found, depth = 0)

            val picked = found.mapNotNull { uri ->
                val name = queryName(uri)
                val mime = MimeUtils.normalize(name, resolver.getType(uri))
                if (mime == null) null else Triple(uri, name ?: defaultName(mime), mime)
            }
            importPicked(picked, albumId, skipped = found.size - picked.size, onProgress)
        }

    private fun collect(dir: DocumentFile, out: MutableList<Uri>, depth: Int) {
        if (depth > 6 || out.size >= MAX_FOLDER_FILES) return
        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            emptyArray<DocumentFile>()
        }
        for (child in children) {
            if (out.size >= MAX_FOLDER_FILES) return
            if (child.isDirectory) collect(child, out, depth + 1)
            else if (child.isFile) out += child.uri
        }
    }

    private suspend fun importPicked(
        picked: List<Triple<Uri, String, String>>,
        albumId: Long?,
        skipped: Int,
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var duplicates = 0
        var copyFailed = 0
        var pos = db.mediaItemDao().maxPosition()
        val total = picked.size

        picked.forEachIndexed { index, (uri, originalName, mime) ->
            onProgress(ImportProgress(current = index + 1, total = total, stage = "checking"))

            // 1) 预检重复：URI 能查到 size 且数据库已有同名同大小记录
            val remoteSize = querySize(uri)
            if (remoteSize > 0 && db.mediaItemDao().countByOriginalNameAndSize(originalName, remoteSize) > 0) {
                duplicates += 1
                return@forEachIndexed
            }

            pos += 1
            val dest = FileStore.newFile(originalName)
            val copied = try {
                resolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                } != null
            } catch (e: Exception) {
                false
            }
            if (!copied) {
                runCatching { dest.delete() }
                copyFailed += 1
                return@forEachIndexed
            }

            val size = dest.length()
            if (size <= 0L) {                       // 空文件 / 读取失败，不入库
                runCatching { dest.delete() }
                copyFailed += 1
                return@forEachIndexed
            }

            // 2) 复制后再查一次重复（部分 URI 不返回 size，需以实际文件大小为准）
            if (db.mediaItemDao().countByOriginalNameAndSize(originalName, size) > 0) {
                duplicates += 1
                runCatching { dest.delete() }
                return@forEachIndexed
            }

            onProgress(ImportProgress(current = index + 1, total = total, stage = "processing"))

            // 视频：预先生成一张首帧缩略图，网格里直接读 JPEG，滚动更顺
            var thumbName: String? = null
            if (mime.startsWith("video/")) {
                val tName = FileStore.newThumbName()
                if (makeVideoThumb(dest, FileStore.thumbFor(tName))) thumbName = tName
            }

            val item = MediaItem(
                albumId = albumId,
                displayName = FileStore.sanitize(originalName.substringBeforeLast('.')),
                fileName = dest.name,
                originalName = originalName,
                thumbName = thumbName,
                fileSize = size,
                mimeType = mime,
                position = pos
            )
            db.mediaItemDao().insert(item)
            imported += 1
        }
        ImportResult(imported, skipped + duplicates + copyFailed)
    }

    // ---------------- rename / album / delete ----------------

    suspend fun renameItems(ids: List<Long>, baseName: String) =
        withContext(Dispatchers.IO) {
            ids.forEachIndexed { index, id ->
                val name = if (ids.size == 1) baseName else "${baseName}_${index + 1}"
                renameOne(id, name)
            }
        }

    suspend fun renameItem(id: Long, newName: String) =
        withContext(Dispatchers.IO) { renameOne(id, newName) }

    private suspend fun renameOne(id: Long, rawName: String) {
        val item = db.mediaItemDao().get(id) ?: return
        val clean = FileStore.sanitize(rawName)
        val newFileName = FileStore.renameFile(item.fileName, clean) ?: item.fileName
        db.mediaItemDao().update(item.copy(displayName = clean, fileName = newFileName))
    }

    suspend fun createAlbum(name: String): Long = withContext(Dispatchers.IO) {
        val clean = FileStore.sanitize(name)
        if (db.albumDao().countByName(clean) > 0) return@withContext -1L
        db.albumDao().insert(Album(name = clean))
    }

    suspend fun renameAlbum(id: Long, name: String) = withContext(Dispatchers.IO) {
        val a = db.albumDao().get(id) ?: return@withContext
        db.albumDao().update(a.copy(name = FileStore.sanitize(name)))
    }

    /** 删除分类：外键 SET_NULL，文件会退回「未分类」，不会被删掉。 */
    suspend fun deleteAlbum(id: Long) = withContext(Dispatchers.IO) {
        val a = db.albumDao().get(id) ?: return@withContext
        db.albumDao().delete(a)
    }

    suspend fun setAlbum(ids: List<Long>, albumId: Long?) =
        withContext(Dispatchers.IO) {
            ids.forEach { db.mediaItemDao().setAlbum(it, albumId) }
        }

    suspend fun deleteItems(ids: List<Long>) = withContext(Dispatchers.IO) {
        ids.forEach { id ->
            val item = db.mediaItemDao().get(id) ?: return@forEach
            FileStore.deleteFile(item.fileName)
            FileStore.deleteThumb(item.thumbName)
            db.mediaItemDao().deleteById(id)
        }
    }

    /** 拖拽排序后把列表顺序写回 position。 */
    suspend fun reorderItems(orderedIds: List<Long>) =
        withContext(Dispatchers.IO) {
            orderedIds.forEachIndexed { index, id -> db.mediaItemDao().setPosition(id, index) }
        }

    /** 视频缩略图丢了（比如手动清过缓存）时补一张。 */
    suspend fun rebuildThumb(id: Long): Boolean = withContext(Dispatchers.IO) {
        val item = db.mediaItemDao().get(id) ?: return@withContext false
        if (!item.isVideo) return@withContext false
        val name = FileStore.newThumbName()
        if (!makeVideoThumb(FileStore.fileFor(item.fileName), FileStore.thumbFor(name))) {
            return@withContext false
        }
        FileStore.deleteThumb(item.thumbName)
        db.mediaItemDao().update(item.copy(thumbName = name))
        true
    }

    // ---------------- helpers ----------------

    private fun queryName(uri: Uri): String? {
        var name: String? = null
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        } catch (e: Exception) {
            name = null
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment
        return name
    }

    private fun querySize(uri: Uri): Long {
        return try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun defaultName(mime: String): String =
        "media_${System.currentTimeMillis()}.${MimeUtils.extensionFor(mime)}"

    private fun makeVideoThumb(source: File, dest: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            val raw = retriever.getFrameAtTime(
                THUMB_TIME_US,
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: retriever.getFrameAtTime(0L) ?: return false

            val scale = (THUMB_MAX_PX.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * scale).roundToInt(),
                    (raw.height * scale).roundToInt(),
                    true
                )
            } else raw

            try {
                dest.outputStream().use { stream ->
                    out.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                }
            } finally {
                raw.recycle()
                if (out !== raw) out.recycle()
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val THUMB_TIME_US = 1_000_000L
        private const val THUMB_MAX_PX = 400
        private const val MAX_FOLDER_FILES = 3000
    }
}
