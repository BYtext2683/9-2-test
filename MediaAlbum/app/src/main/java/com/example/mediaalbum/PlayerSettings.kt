package com.example.mediaalbum

import android.content.Context
import android.content.SharedPreferences

/** 播放页面的屏幕方向模式。 */
enum class PlayOrientation(val label: String) {
    /** 跟随系统（默认） */
    AUTO("跟随系统"),

    /** 强制竖屏 */
    PORTRAIT("竖屏播放"),

    /** 强制横屏 */
    LANDSCAPE("横屏播放")
}

/**
 * 播放页偏好（横竖屏 / 全屏），写入与主界面共用的 SharedPreferences，
 * 更新安装后依然保留。
 */
object PlayerSettings {

    private const val KEY_ORIENTATION = "player_orientation"
    private const val KEY_FULLSCREEN = "player_fullscreen"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("media_album_prefs", Context.MODE_PRIVATE)

    fun orientation(context: Context): PlayOrientation =
        runCatching {
            PlayOrientation.valueOf(
                prefs(context).getString(KEY_ORIENTATION, PlayOrientation.AUTO.name)!!
            )
        }.getOrDefault(PlayOrientation.AUTO)

    fun setOrientation(context: Context, mode: PlayOrientation) {
        prefs(context).edit().putString(KEY_ORIENTATION, mode.name).apply()
    }

    /** true = 全屏播放；false = 窗口播放（显示工具栏和系统栏） */
    fun fullscreen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FULLSCREEN, false)

    fun setFullscreen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULLSCREEN, on).apply()
    }
}
