package com.example.mediaalbum.util

import android.webkit.MimeTypeMap

/**
 * 只接受确定能处理的格式：
 *  - 图片：webp / gif / png / jpeg / bmp / heic / avif
 *  - 视频：mp4 / m4v / mov / mkv / webm / 3gp
 * 其余一律在导入时跳过，避免"导进来了却播不了"。
 */
object MimeUtils {

    private val EXT_TO_MIME = mapOf(
        "webp" to "image/webp",
        "gif" to "image/gif",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mov" to "video/quicktime",
        "qt" to "video/quicktime",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "3gp" to "video/3gpp",
        "3gpp" to "video/3gpp"
    )

    private val MIME_TO_EXT = mapOf(
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/bmp" to "bmp",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/avif" to "avif",
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/x-matroska" to "mkv",
        "video/webm" to "webm",
        "video/3gpp" to "3gp",
        "video/3gpp2" to "3gp"
    )

    /** 兜底：系统 MIME 表里查一下（主要为了处理 *.webp 在某些机型上返回 null 的情况）。 */
    fun guessFromExtension(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return EXT_TO_MIME[ext] ?: MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext)
            ?.takeIf { isSupportedMime(it) }
    }

    fun isSupportedMime(mime: String): Boolean {
        val base = mime.substringBefore(';').trim().lowercase()
        return base in MIME_TO_EXT
    }

    /**
     * 综合"URI 的显示名 + ContentResolver 报告的 MIME"判定是否支持，
     * 并返回一个规范化后的 MIME；不支持返回 null。
     */
    fun normalize(name: String?, declaredMime: String?): String? {
        val base = declaredMime?.substringBefore(';')?.trim()?.lowercase()
        if (base != null && base in MIME_TO_EXT) return base

        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        EXT_TO_MIME[ext]?.let { return it }

        return null
    }

    fun extensionFor(mime: String): String =
        MIME_TO_EXT[mime.substringBefore(';').trim().lowercase()] ?: "webp"
}
