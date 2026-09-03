package com.example.mediaalbum

import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.mediaalbum.data.MediaItem
import com.example.mediaalbum.databinding.ItemMediaBinding
import com.example.mediaalbum.util.FileStore
import java.io.File

class GridAdapter(
    private val onItemClick: (MediaItem) -> Unit,
    private val onItemLongClick: (MediaItem) -> Unit,
    private val onDragHandleTouch: (RecyclerView.ViewHolder) -> Unit,
    private val canDrag: () -> Boolean
) : ListAdapter<MediaItem, GridAdapter.VH>(DIFF) {

    private var selection: Set<Long> = emptySet()
    private var quickSelect = false

    /** 拖拽期间的可变副本；null 表示没在拖拽。 */
    private var working: MutableList<MediaItem>? = null

    private val requestOptions = RequestOptions()
        .centerCrop()
        .placeholder(android.R.color.darker_gray)
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)

    // ---------------- selection ----------------

    fun setQuickSelect(enabled: Boolean) {
        if (quickSelect == enabled) return
        quickSelect = enabled
        notifyDataSetChanged()
    }

    fun setSelection(sel: Set<Long>) {
        val newSel = sel.toSet()
        val old = selection
        if (old.size == newSel.size && old.containsAll(newSel)) return
        selection = newSel
        currentList.forEachIndexed { index, item ->
            if (old.contains(item.id) != newSel.contains(item.id)) notifyItemChanged(index, PAYLOAD_SELECTION)
        }
    }

    fun currentIds(): List<Long> = currentList.map { it.id }

    // ---------------- drag reorder ----------------

    fun beginDrag() {
        working = currentList.toMutableList()
    }

    fun moveItem(from: Int, to: Int) {
        val list = working ?: return
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        notifyItemMoved(from, to)
    }

    /** 结束拖拽：把结果提交给 ListAdapter 并返回最终顺序。 */
    fun endDrag(): List<MediaItem>? {
        val result = working
        working = null
        if (result != null) submitList(ArrayList(result))
        return result
    }

    companion object {
        private const val PAYLOAD_SELECTION = "payload_selection"

        private val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.id == b.id
            override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a == b
        }
    }

    inner class VH(val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: MediaItem) {
            val selected = selection.contains(item.id)

            b.tvName.text = item.displayName

            // 视频优先用导入时生成好的 JPG 缩略图，没有再退回 Glide 抽帧
            val thumbFile: File? = item.thumbName?.let { FileStore.thumbFor(it) }
            val model: Any = when {
                thumbFile != null && thumbFile.exists() -> Uri.fromFile(thumbFile)
                else -> Uri.fromFile(FileStore.fileFor(item.fileName))
            }
            Glide.with(b.ivThumb.context)
                .asBitmap()
                .load(model)
                .apply(requestOptions)
                .into(b.ivThumb)

            if (item.isVideo) {
                b.tvBadge.visibility = android.view.View.VISIBLE
                b.tvBadge.text = "▶ 视频"
            } else if (item.isAnimated) {
                b.tvBadge.visibility = android.view.View.VISIBLE
                b.tvBadge.text = "动图"
            } else {
                b.tvBadge.visibility = android.view.View.GONE
            }

            b.ivCheck.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
            b.ivRing.visibility =
                if (quickSelect && !selected) android.view.View.VISIBLE else android.view.View.GONE
            b.ivDrag.visibility =
                if (canDrag()) android.view.View.VISIBLE else android.view.View.GONE

            b.card.strokeWidth = if (selected) 4 else 0
            b.card.alpha = if (selected) 0.7f else 1f

            b.root.setOnClickListener { onItemClick(item) }
            b.root.setOnLongClickListener { onItemLongClick(item); true }
            b.ivDrag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onDragHandleTouch(this)
                }
                true
            }
        }

        /** 仅更新选中/未选中相关视图，避免整张图片重新加载闪烁。 */
        fun updateSelection(item: MediaItem) {
            val selected = selection.contains(item.id)
            b.ivCheck.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
            b.ivRing.visibility =
                if (quickSelect && !selected) android.view.View.VISIBLE else android.view.View.GONE
            b.card.strokeWidth = if (selected) 4 else 0
            b.card.alpha = if (selected) 0.7f else 1f
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.updateSelection(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
