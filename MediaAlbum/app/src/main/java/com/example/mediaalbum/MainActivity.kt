package com.example.mediaalbum

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.mediaalbum.data.Album
import com.example.mediaalbum.data.ImportProgress
import com.example.mediaalbum.data.MediaItem
import com.example.mediaalbum.databinding.ActivityMainBinding
import com.example.mediaalbum.databinding.DialogTextInputBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: GridAdapter
    private lateinit var touchHelper: ItemTouchHelper

    /** 已选中的文件 id（有序，保证批量重命名顺序稳定） */
    private val selectedIds = LinkedHashSet<Long>()

    /** 是否处于「快速多选」模式 */
    private var quickSelect = false

    private var currentAlbums: List<Album> = emptyList()
    private var currentItems: List<MediaItem> = emptyList()
    private lateinit var backCallback: OnBackPressedCallback

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.import(uris)
    }

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.importFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        setupGrid()
        setupSelectionBar()
        setupBackHandling()

        binding.fab.setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }

        lifecycleScope.launch {
            viewModel.items.collectLatest { list ->
                currentItems = list
                adapter.submitList(list)
                cleanupSelection(list)
                updateSelectionUI()
            }
        }
        lifecycleScope.launch {
            viewModel.albums.collectLatest { albums ->
                currentAlbums = albums
                renderAlbumChips(albums)
            }
        }
        lifecycleScope.launch {
            viewModel.filter.collectLatest { renderAlbumChips(currentAlbums) }
        }
        lifecycleScope.launch {
            viewModel.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
        }
        lifecycleScope.launch {
            viewModel.importProgress.collect { updateImportProgress(it) }
        }
    }

    // ---------------- grid ----------------

    private fun setupGrid() {
        val span = (resources.displayMetrics.widthPixels /
            (resources.displayMetrics.density * 108)).toInt().coerceIn(2, 6)

        adapter = GridAdapter(
            onItemClick = { handleItemClick(it) },
            onItemLongClick = { handleItemLongClick(it) },
            onDragHandleTouch = { vh ->
                if (canDrag()) {
                    adapter.beginDrag()
                    touchHelper.startDrag(vh)
                }
            },
            canDrag = { canDrag() }
        )

        binding.rvGrid.layoutManager = GridLayoutManager(this, span)
        binding.rvGrid.adapter = adapter

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateEmptyState()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateEmptyState()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateEmptyState()
        })

        attachDragToReorder()
    }

    private fun canDrag(): Boolean =
        viewModel.sortMode.value == SortMode.CUSTOM && !quickSelect

    private fun attachDragToReorder() {
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false
            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int = makeMovementFlags(
                if (canDrag()) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                } else 0,
                0
            )

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (!canDrag()) return false
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                val ordered = adapter.endDrag()
                if (ordered != null && ordered.size > 1) {
                    viewModel.reorder(ordered.map { it.id })
                }
            }
        })
        touchHelper.attachToRecyclerView(binding.rvGrid)
    }

    private fun updateEmptyState() {
        binding.tvEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun updateImportProgress(progress: ImportProgress?) {
        if (progress == null) {
            binding.cardProgress.visibility = View.GONE
            return
        }
        binding.cardProgress.visibility = View.VISIBLE
        val percent = if (progress.total > 0) (progress.current * 100 / progress.total) else 0
        binding.progressBar.progress = percent.coerceIn(0, 100)
        binding.tvProgressCount.text = "${progress.current} / ${progress.total}"
        binding.tvProgressTitle.text = when (progress.stage) {
            "checking" -> "正在检查重复文件…"
            else -> "正在导入：${progress.current}/${progress.total}"
        }
    }

    // ---------------- album chips ----------------

    private fun renderAlbumChips(albums: List<Album>) {
        binding.chipAlbums.removeAllViews()

        addChip("全部", isFilterAll()) { viewModel.setFilter(AlbumFilter.All) }
        addChip("未分类", viewModel.filter.value is AlbumFilter.Uncategorized) {
            viewModel.setFilter(AlbumFilter.Uncategorized)
        }

        albums.forEach { album ->
            val chip = Chip(this).apply {
                text = album.name
                isCheckable = true
                isChecked = (viewModel.filter.value as? AlbumFilter.Of)?.id == album.id
                setOnClickListener { viewModel.setFilter(AlbumFilter.Of(album.id)) }
                setOnLongClickListener { showAlbumMenu(album); true }
            }
            binding.chipAlbums.addView(chip)
        }

        val newAlbumChip = Chip(this).apply {
            text = "＋ 新建分类"
            isCheckable = false
            setOnClickListener { promptText("新建分类", "") { viewModel.createAlbum(it) } }
        }
        binding.chipAlbums.addView(newAlbumChip)
    }

    private fun addChip(text: String, checked: Boolean, onClick: () -> Unit) {
        binding.chipAlbums.addView(Chip(this).apply {
            this.text = text
            isCheckable = true
            isChecked = checked
            setOnClickListener { onClick() }
        })
    }

    private fun isFilterAll(): Boolean = viewModel.filter.value is AlbumFilter.All

    private fun showAlbumMenu(album: Album) {
        AlertDialog.Builder(this)
            .setTitle(album.name)
            .setItems(arrayOf("重命名", "删除分类")) { _, which ->
                when (which) {
                    0 -> promptText("重命名分类", album.name) { viewModel.renameAlbum(album.id, it) }
                    1 -> AlertDialog.Builder(this)
                        .setTitle("删除分类？")
                        .setMessage("文件不会被删除，只会退回「未分类」。")
                        .setPositiveButton("删除") { _, _ -> viewModel.deleteAlbum(album.id) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }.show()
    }

    private fun showManageAlbums() {
        val options = arrayOf("＋ 新建分类", *currentAlbums.map { it.name }.toTypedArray())
        AlertDialog.Builder(this)
            .setTitle("管理分类")
            .setItems(options) { _, which ->
                if (which == 0) promptText("新建分类", "") { viewModel.createAlbum(it) }
                else currentAlbums.getOrNull(which - 1)?.let { showAlbumMenu(it) }
            }.show()
    }

    // ---------------- 快速多选 ----------------

    private fun setupSelectionBar() {
        binding.btnClose.setOnClickListener { exitQuickSelect() }
        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnInvert.setOnClickListener { invertSelection() }
        binding.btnRename.setOnClickListener { renameSelection() }
        binding.btnCategorize.setOnClickListener { moveSelection() }
        binding.btnDelete.setOnClickListener { deleteSelection() }
    }

    private fun setupBackHandling() {
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = exitQuickSelect()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    private fun handleItemClick(item: MediaItem) {
        if (quickSelect) toggleSelection(item.id) else openPlayer(item)
    }

    private fun handleItemLongClick(item: MediaItem) {
        if (!quickSelect) {
            enterQuickSelect()
            toggleSelection(item.id)
        } else {
            toggleSelection(item.id)
        }
    }

    private fun enterQuickSelect() {
        if (quickSelect) return
        quickSelect = true
        showQuickSelectHintOnce()
        updateSelectionUI()
    }

    private fun exitQuickSelect() {
        quickSelect = false
        selectedIds.clear()
        updateSelectionUI()
    }

    private fun toggleSelection(id: Long) {
        if (!selectedIds.add(id)) selectedIds.remove(id)
        updateSelectionUI()
    }

    /** 当前列表里真正显示着的 id（用 flow 里的列表，避免 submitList 异步导致的一帧误差） */
    private fun visibleIds(): List<Long> = currentItems.map { it.id }

    private fun allVisibleSelected(): Boolean {
        val ids = visibleIds()
        return ids.isNotEmpty() && selectedIds.containsAll(ids)
    }

    private fun toggleSelectAll() {
        val ids = visibleIds()
        if (allVisibleSelected()) selectedIds.removeAll(ids.toSet())
        else selectedIds.addAll(ids)
        updateSelectionUI()
    }

    private fun invertSelection() {
        visibleIds().forEach { id ->
            if (!selectedIds.add(id)) selectedIds.remove(id)
        }
        updateSelectionUI()
    }

    /** 切换分类 / 删除文件后，把已经不存在的 id 从选中集合里清掉。 */
    private fun cleanupSelection(list: List<MediaItem>) {
        if (selectedIds.isEmpty()) return
        val existing = list.map { it.id }.toSet()
        val before = selectedIds.size
        selectedIds.removeAll { it !in existing }
        if (selectedIds.size != before) updateSelectionUI()
    }

    private fun updateSelectionUI() {
        backCallback.isEnabled = quickSelect
        updateEmptyState()
        adapter.setQuickSelect(quickSelect)
        adapter.setSelection(selectedIds)

        binding.selectionBar.visibility = if (quickSelect) View.VISIBLE else View.GONE
        binding.tvSelCount.text = "已选 ${selectedIds.size}"

        if (quickSelect) binding.fab.hide() else binding.fab.show()

        supportActionBar?.title =
            if (quickSelect) "快速选择" else getString(R.string.app_name)
        binding.toolbar.navigationIcon =
            if (quickSelect) {
                androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_close)
            } else null
        binding.toolbar.setNavigationOnClickListener { if (quickSelect) exitQuickSelect() }

        binding.btnSelectAll.text = if (allVisibleSelected()) "取消全选" else "全选"
        binding.btnRename.isEnabled = selectedIds.isNotEmpty()
        binding.btnCategorize.isEnabled = selectedIds.isNotEmpty()
        binding.btnDelete.isEnabled = selectedIds.isNotEmpty()

        invalidateOptionsMenu()
    }

    private fun showQuickSelectHintOnce() {
        val prefs = getSharedPreferences("media_album_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("hint_quick_select", true)) {
            prefs.edit().putBoolean("hint_quick_select", false).apply()
            Toast.makeText(this, getString(R.string.hint_quick_select), Toast.LENGTH_LONG).show()
        }
    }

    private fun renameSelection() {
        when {
            selectedIds.isEmpty() -> toast(getString(R.string.msg_nothing_selected))
            selectedIds.size > 1 -> {
                val template = currentItems.firstOrNull { it.id == selectedIds.first() }?.displayName ?: ""
                promptText("批量重命名", template) { name ->
                    if (name.isNotBlank()) {
                        viewModel.renameItems(selectedIds.toList(), name)
                        exitQuickSelect()
                    }
                }
            }
            else -> {
                val id = selectedIds.first()
                val item = currentItems.firstOrNull { it.id == id } ?: return
                promptText("重命名", item.displayName) { name ->
                    if (name.isNotBlank()) {
                        viewModel.renameItem(id, name)
                        exitQuickSelect()
                    }
                }
            }
        }
    }

    private fun moveSelection() {
        if (selectedIds.isEmpty()) {
            toast(getString(R.string.msg_nothing_selected))
            return
        }
        val options = arrayOf(
            "移到：未分类",
            *currentAlbums.map { "移到：${it.name}" }.toTypedArray()
        )
        AlertDialog.Builder(this)
            .setTitle("移动到分类（${selectedIds.size} 项）")
            .setItems(options) { _, which ->
                val target = if (which == 0) null else currentAlbums[which - 1].id
                viewModel.moveToAlbum(selectedIds.toList(), target)
                exitQuickSelect()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelection() {
        if (selectedIds.isEmpty()) {
            toast(getString(R.string.msg_nothing_selected))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除 ${selectedIds.size} 项？")
            .setMessage("文件会从设备中删除，且不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteItems(selectedIds.toList())
                exitQuickSelect()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- menu ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_quick_select)?.isVisible = !quickSelect
        menu.findItem(R.id.action_sort)?.isVisible = !quickSelect
        menu.findItem(R.id.action_import_files)?.isVisible = !quickSelect
        menu.findItem(R.id.action_import_folder)?.isVisible = !quickSelect
        menu.findItem(R.id.action_manage_albums)?.isVisible = !quickSelect
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_quick_select -> { enterQuickSelect(); true }
            R.id.action_sort -> { showSortDialog(); true }
            R.id.action_import_files -> { importLauncher.launch(arrayOf("*/*")); true }
            R.id.action_import_folder -> { folderLauncher.launch(null); true }
            R.id.action_manage_albums -> { showManageAlbums(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSortDialog() {
        val modes = SortMode.values()
        val current = modes.indexOf(viewModel.sortMode.value).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("排序方式")
            .setSingleChoiceItems(Sorting.LABELS, current) { dialog, which ->
                viewModel.setSortMode(modes[which])
                adapter.notifyDataSetChanged()   // 拖拽手柄的显隐随排序方式变化
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- misc ----------------

    private fun promptText(title: String, preset: String, onOk: (String) -> Unit) {
        val b = DialogTextInputBinding.inflate(layoutInflater)
        b.til.hint = title
        b.et.setText(preset)
        b.et.setSelection(b.et.text?.length ?: 0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(b.root)
            .setPositiveButton("确定") { _, _ -> onOk(b.et.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun openPlayer(item: MediaItem) {
        val albumId = when (val f = viewModel.filter.value) {
            AlbumFilter.All -> PlayerActivity.FILTER_ALL
            AlbumFilter.Uncategorized -> PlayerActivity.FILTER_UNCATEGORIZED
            is AlbumFilter.Of -> f.id
        }
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_ALBUM_ID, albumId)
            putExtra(PlayerActivity.EXTRA_START_ID, item.id)
            putExtra(PlayerActivity.EXTRA_SORT, viewModel.sortMode.value.name)
        }
        startActivity(intent)
    }
}
