package com.example.mediaalbum.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个已导入的媒体文件（动画 WebP / GIF / 静态图 / MP4 等视频）。
 */
@Entity(
    tableName = "media_items",
    foreignKeys = [ForeignKey(
        entity = Album::class,
        parentColumns = ["id"],
        childColumns = ["albumId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("albumId")]
)
data class MediaItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 所属分类；null = 未分类 */
    val albumId: Long? = null,

    /** 用户可改的名字（网格里显示、可重命名） */
    val displayName: String,

    /** 磁盘上的文件名（FileStore 目录下） */
    val fileName: String,

    /** 导入时的原始文件名 */
    val originalName: String,

    /** 视频首帧缩略图文件名（thumbs 目录下），图片为 null */
    val thumbName: String? = null,

    val fileSize: Long = 0,

    /** MIME 类型（image 或 video）—— 决定用哪种方式播放 */
    val mimeType: String = "image/webp",

    /** 自定义排序位置 */
    val position: Int = 0,

    val addedAt: Long = System.currentTimeMillis()
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isAnimated: Boolean get() = mimeType == "image/webp" || mimeType == "image/gif"
}
