package com.example.mediaalbum

import android.app.Application
import com.example.mediaalbum.data.AppDatabase
import com.example.mediaalbum.data.MediaItem
import com.example.mediaalbum.util.FileStore
import com.example.mediaalbum.util.MimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MediaAlbumApplication : Application() {

    lateinit var database: AppDatabase
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        FileStore.init(this)
        database = AppDatabase.build(this)
        appScope.launch { healMediaRecords() }
    }

    /**
     * 启动自愈：保证「磁盘文件」和「数据库记录」一致，更新安装后不丢文件。
     *  - 文件还在、记录没了（数据库被重置/异常） -> 按磁盘文件重新登记，文件不会从 App 里消失；
     *  - 记录还在、文件没了 -> 清理这条记录，避免网格里出现打不开的项。
     */
    private suspend fun healMediaRecords() {
        runCatching {
            val dir = FileStore.mediaDir()
            val files = dir.listFiles()
                ?.filter { it.isFile && it.length() > 0L }
                ?: return@runCatching

            val dao = database.mediaItemDao()
            val rows = dao.observeAll().first()
            val known = rows.map { it.fileName }.toSet()

            var pos = dao.maxPosition()
            files.forEach { f ->
                if (f.name in known) return@forEach
                val mime = MimeUtils.guessFromExtension(f.name) ?: return@forEach
                pos += 1
                dao.insert(
                    MediaItem(
                        albumId = null,
                        displayName = FileStore.sanitize(f.name.substringBeforeLast('.')),
                        fileName = f.name,
                        originalName = f.name,
                        fileSize = f.length(),
                        mimeType = mime,
                        position = pos,
                        addedAt = f.lastModified()
                    )
                )
            }

            rows.forEach { row ->
                if (!FileStore.fileFor(row.fileName).exists()) {
                    FileStore.deleteThumb(row.thumbName)
                    dao.deleteById(row.id)
                }
            }
        }
    }
}
