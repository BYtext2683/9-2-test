package com.example.mediaalbum

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediaalbum.data.Album
import com.example.mediaalbum.data.AlbumRepository
import com.example.mediaalbum.data.ImportProgress
import com.example.mediaalbum.data.MediaItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 当前网格在展示哪个范围：全部 / 未分类 / 某个分类 */
sealed interface AlbumFilter {
    data object All : AlbumFilter
    data object Uncategorized : AlbumFilter
    data class Of(val id: Long) : AlbumFilter
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs =
        application.getSharedPreferences("media_album_prefs", Application.MODE_PRIVATE)

    private val repo = AlbumRepository(
        application,
        (application as MediaAlbumApplication).database
    )

    val albums: Flow<List<Album>> = repo.albums()

    private val _filter = MutableStateFlow<AlbumFilter>(AlbumFilter.All)
    val filter: StateFlow<AlbumFilter> = _filter

    private val _sortMode = MutableStateFlow(loadSortMode())
    val sortMode: StateFlow<SortMode> = _sortMode

    /** 一次性提示（Toast） */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: Flow<String> = _messages

    /** 导入进度：null 表示当前没有导入任务 */
    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<MediaItem>> =
        combine(_filter, _sortMode) { filter, mode -> filter to mode }
            .flatMapLatest { (filter, mode) ->
                val source = when (filter) {
                    AlbumFilter.All -> repo.itemsAll()
                    AlbumFilter.Uncategorized -> repo.itemsUncategorized()
                    is AlbumFilter.Of -> repo.itemsForAlbum(filter.id)
                }
                source.map { Sorting.sort(it, mode) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun currentAlbumIdForImport(): Long? =
        (_filter.value as? AlbumFilter.Of)?.id

    fun setFilter(filter: AlbumFilter) { _filter.value = filter }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        prefs.edit().putString(KEY_SORT, mode.name).apply()
    }

    private fun loadSortMode(): SortMode =
        runCatching { SortMode.valueOf(prefs.getString(KEY_SORT, SortMode.TIME_DESC.name)!!) }
            .getOrDefault(SortMode.TIME_DESC)

    // ---------------- operations ----------------

    fun import(uris: List<Uri>) = viewModelScope.launch {
        _importProgress.value = ImportProgress(0, uris.size, "checking")
        val res = repo.importFrom(uris, currentAlbumIdForImport()) { progress ->
            _importProgress.value = progress
        }
        _importProgress.value = null
        val msg = if (res.imported == 0) {
            "没有可导入的文件（已跳过 ${res.skipped} 个重复或不支持的格式）"
        } else {
            "已导入 ${res.imported} 个" +
                if (res.skipped > 0) "，跳过 ${res.skipped} 个重复或不支持的文件" else ""
        }
        _messages.emit(msg)
    }

    fun importFolder(treeUri: Uri) = viewModelScope.launch {
        _messages.emit("正在扫描文件夹…")
        _importProgress.value = ImportProgress(0, 1, "checking")
        val res = repo.importFromTree(treeUri, currentAlbumIdForImport()) { progress ->
            _importProgress.value = progress
        }
        _importProgress.value = null
        val msg = if (res.imported == 0) {
            "这个文件夹里没有可导入的 webp / mp4 等文件"
        } else {
            "已导入 ${res.imported} 个" +
                if (res.skipped > 0) "，跳过 ${res.skipped} 个重复或不支持的文件" else ""
        }
        _messages.emit(msg)
    }

    fun reorder(orderedIds: List<Long>) = viewModelScope.launch { repo.reorderItems(orderedIds) }

    fun createAlbum(name: String) = viewModelScope.launch {
        if (repo.createAlbum(name) == -1L) _messages.emit("已存在同名分类")
    }

    fun renameAlbum(id: Long, name: String) = viewModelScope.launch { repo.renameAlbum(id, name) }

    fun deleteAlbum(id: Long) = viewModelScope.launch {
        repo.deleteAlbum(id)
        if ((_filter.value as? AlbumFilter.Of)?.id == id) _filter.value = AlbumFilter.All
    }

    fun moveToAlbum(ids: List<Long>, albumId: Long?) =
        viewModelScope.launch { repo.setAlbum(ids, albumId) }

    fun renameItem(id: Long, newName: String) =
        viewModelScope.launch { repo.renameItem(id, newName) }

    fun renameItems(ids: List<Long>, baseName: String) =
        viewModelScope.launch { repo.renameItems(ids, baseName) }

    fun deleteItems(ids: List<Long>) = viewModelScope.launch { repo.deleteItems(ids) }

    companion object {
        private const val KEY_SORT = "sort_mode"
    }
}
