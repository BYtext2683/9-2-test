package com.example.mediaalbum.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MediaItem::class, Album::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaItemDao(): MediaItemDao
    abstract fun albumDao(): AlbumDao

    companion object {
        private const val DB_NAME = "media_album.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun build(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    // 升级安装后不允许因为数据库结构变化而打不开 App。
                    // 真出现结构变化时这里会清库重来，但磁盘文件不受影响，
                    // MediaAlbumApplication.healMediaRecords() 会把文件重新登记回来
                    // （最多丢分类，不会丢文件）。能补 Migration 时还是建议补上。
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
