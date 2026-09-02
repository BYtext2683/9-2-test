package com.example.mediaalbum

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.mediaalbum.data.AlbumRepository
import com.example.mediaalbum.data.MediaItem
import com.example.mediaalbum.databinding.ActivityPlayerBinding
import com.example.mediaalbum.databinding.DialogTextInputBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var repo: AlbumRepository
    private lateinit var pagerAdapter: PlayerPagerAdapter

    private var items: List<MediaItem> = emptyList()
    private var sortMode: SortMode = SortMode.TIME_DESC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val db = (application as MediaAlbumApplication).database
        repo = AlbumRepository(application, db)

        val albumId = intent.getLongExtra(EXTRA_ALBUM_ID, FILTER_ALL)
        val startId = intent.getLongExtra(EXTRA_START_ID, -1L)
        sortMode = runCatching { SortMode.valueOf(intent.getStringExtra(EXTRA_SORT) ?: "") }
            .getOrDefault(SortMode.TIME_DESC)

        lifecycleScope.launch {
            val raw = when (albumId) {
                FILTER_ALL -> db.mediaItemDao().observeAll().first()
                FILTER_UNCATEGORIZED -> db.mediaItemDao().observeUncategorized().first()
                else -> db.mediaItemDao().observeByAlbum(albumId).first()
            }
            items = Sorting.sort(raw, sortMode)

            if (items.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "没有可播放的文件", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            pagerAdapter = PlayerPagerAdapter(this@PlayerActivity, items)
            binding.pager.adapter = pagerAdapter
            binding.pager.offscreenPageLimit = 1

            val startPos = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)
            binding.pager.setCurrentItem(startPos, false)
            updateTitle(startPos)

            binding.pager.registerOnPageChangeCallback(object :
                ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = updateTitle(position)
            })
        }
    }

    private fun updateTitle(pos: Int) {
        if (pos !in items.indices) return
        val item = items[pos]
        supportActionBar?.title = "${item.displayName}  (${pos + 1}/${items.size})"
        supportActionBar?.subtitle = when {
            item.isVideo -> "轻点画面显示控制条，可暂停/拖动进度"
            item.isAnimated -> "轻点画面暂停 / 继续"
            else -> "静态图片"
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_player, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_rename -> { renameCurrent(); true }
            R.id.action_categorize -> { categorizeCurrent(); true }
            R.id.action_delete -> { deleteCurrent(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun currentItem(): MediaItem? {
        val pos = binding.pager.currentItem
        return items.getOrNull(pos)
    }

    private fun renameCurrent() {
        val item = currentItem() ?: return
        val b = DialogTextInputBinding.inflate(layoutInflater)
        b.til.hint = "重命名"
        b.et.setText(item.displayName)
        b.et.setSelection(b.et.text?.length ?: 0)
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(b.root)
            .setPositiveButton("确定") { _, _ ->
                val name = b.et.text.toString().trim()
                if (name.isNotBlank()) {
                    lifecycleScope.launch { repo.renameItem(item.id, name) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun categorizeCurrent() {
        val item = currentItem() ?: return
        lifecycleScope.launch {
            val albums = repo.albums().first()
            val options = arrayOf("移到：未分类", *albums.map { "移到：${it.name}" }.toTypedArray())
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("移动到分类")
                .setItems(options) { _, which ->
                    val target = if (which == 0) null else albums[which - 1].id
                    lifecycleScope.launch { repo.setAlbum(listOf(item.id), target) }
                    Toast.makeText(this@PlayerActivity, "已移动", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun deleteCurrent() {
        val item = currentItem() ?: return
        AlertDialog.Builder(this)
            .setTitle("删除这个文件？")
            .setMessage("${item.displayName}\n删除后不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    repo.deleteItems(listOf(item.id))
                    finish()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        const val EXTRA_ALBUM_ID = "album_id"
        const val EXTRA_START_ID = "start_id"
        const val EXTRA_SORT = "sort"
        const val FILTER_ALL = -1L
        const val FILTER_UNCATEGORIZED = -2L
    }
}
