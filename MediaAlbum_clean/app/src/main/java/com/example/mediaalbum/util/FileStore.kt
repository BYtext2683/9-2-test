package com.example.mediaalbum.util

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * 已导入文件的磁盘存储。
 * 放在 App 私有目录，App 完全掌控命名 / 分类 / 删除，不依赖原文件位置，
 * 也无需任何存储权限。（卸载 App 时文件会一起删除。）
 */
object FileStore {

    private lateinit var root: File
    private lateinit var thumbRoot: File

    fun init(context: Context) {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        root = File(base, "media").apply { if (!exists()) mkdirs() }
        thumbRoot = File(base, "thumbs").apply { if (!exists()) mkdirs() }
    }

    fun fileFor(fileName: String): File = File(root, fileName)

    fun thumbFor(thumbName: String): File = File(thumbRoot, thumbName)

    fun newFileName(originalName: String): String {
        val ext = originalName.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotBlank() && it.length <= 5 }
            ?: "webp"
        return "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext"
    }

    fun newFile(originalName: String): File = File(root, newFileName(originalName))

    fun newThumbName(): String =
        "t_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"

    fun deleteFile(fileName: String) {
        runCatching { fileFor(fileName).delete() }
    }

    fun deleteThumb(thumbName: String?) {
        if (!thumbName.isNullOrBlank()) runCatching { thumbFor(thumbName).delete() }
    }

    /** 文件名里不能出现的字符统一替换成下划线。 */
    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\r\n\t]"), "_").trim()
            .trimEnd('.')
            .trim()
        return cleaned.ifEmpty { "未命名" }
    }

    /**
     * 把磁盘文件改名成 [newDisplayName].[原扩展名]。
     * @return 新的文件名；失败（目标已存在 / IO 错误）返回 null，此时只更新显示名。
     */
    fun renameFile(oldFileName: String, newDisplayName: String): String? {
        val ext = oldFileName.substringAfterLast('.', "webp")
        val target = File(root, "${sanitize(newDisplayName)}.$ext")
        if (target.exists()) return null
        return if (fileFor(oldFileName).renameTo(target)) target.name else null
    }
}
