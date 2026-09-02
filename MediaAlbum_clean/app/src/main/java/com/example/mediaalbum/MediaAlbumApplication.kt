package com.example.mediaalbum

import android.app.Application
import com.example.mediaalbum.data.AppDatabase
import com.example.mediaalbum.util.FileStore

class MediaAlbumApplication : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        FileStore.init(this)
        database = AppDatabase.build(this)
    }
}
