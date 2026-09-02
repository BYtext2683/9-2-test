package com.example.mediaalbum

import com.example.mediaalbum.data.MediaItem

enum class SortMode { CUSTOM, TIME_DESC, TIME_ASC, NAME, SIZE_DESC, TYPE }

object Sorting {

    val LABELS = arrayOf(
        "自定义顺序（按住右下角手柄拖动）",
        "导入时间：新 → 旧",
        "导入时间：旧 → 新",
        "名称：A → Z",
        "文件大小：大 → 小",
        "文件类型（视频 / 动图 / 静态图）"
    )

    fun sort(list: List<MediaItem>, mode: SortMode): List<MediaItem> = when (mode) {
        SortMode.CUSTOM -> list.sortedBy { it.position }
        SortMode.TIME_DESC -> list.sortedByDescending { it.addedAt }
        SortMode.TIME_ASC -> list.sortedBy { it.addedAt }
        SortMode.NAME -> list.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
        SortMode.SIZE_DESC -> list.sortedByDescending { it.fileSize }
        SortMode.TYPE -> list.sortedWith(
            compareBy({ typeRank(it.mimeType) }, { it.displayName.lowercase() })
        )
    }

    private fun typeRank(mime: String): Int = when {
        mime.startsWith("video/") -> 0
        mime == "image/webp" || mime == "image/gif" -> 1
        else -> 2
    }
}
