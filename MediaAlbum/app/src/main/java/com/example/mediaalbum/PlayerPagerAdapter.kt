package com.example.mediaalbum

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.mediaalbum.data.MediaItem

class PlayerPagerAdapter(
    activity: FragmentActivity,
    private val items: List<MediaItem>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.id ?: position.toLong()

    override fun containsItem(itemId: Long): Boolean =
        items.any { it.id == itemId }

    override fun createFragment(position: Int): Fragment =
        PlayerPageFragment.newInstance(items[position])
}
