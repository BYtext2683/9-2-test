package com.example.mediaalbum

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.example.mediaalbum.databinding.FragmentPlayerPageBinding
import com.example.mediaalbum.data.MediaItem
import com.example.mediaalbum.util.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * 单页播放：
 *  - video 类型 -> Media3 ExoPlayer（循环播放）。这是修复 MP4 闪退的关键：
 *    旧版用 VideoView/MediaPlayer，它在 ViewPager2 里会因为 SurfaceView 生命周期
 *    （"The surface has been released"）和 MediaPlayer 状态错误直接崩溃。
 *    ExoPlayer 用 texture_view + 显式 release + onPlayerError，出错只提示、不崩。
 *  - 动画 webp/gif -> ImageDecoder + AnimatedImageDrawable，无限循环，轻点暂停/继续。
 *  - 静态图 -> 直接显示。
 */
class PlayerPageFragment : Fragment() {

    private var _b: FragmentPlayerPageBinding? = null
    private val b get() = _b!!

    private var player: ExoPlayer? = null
    private var anim: AnimatedImageDrawable? = null
    private var isVideo = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentPlayerPageBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = requireArguments().getString(ARG_FILE).orEmpty()
        val mime = requireArguments().getString(ARG_MIME).orEmpty()
        val file = File(path)

        if (!file.exists() || file.length() <= 0L) {
            showError("文件不存在或已损坏：\n${file.name}")
            return
        }

        isVideo = mime.startsWith("video/")
        if (isVideo) setupVideo(file) else setupImage(file)
    }

    // ---------------- video ----------------

    private fun setupVideo(file: File) {
        b.ivImage.visibility = View.GONE
        b.playerView.visibility = View.VISIBLE

        val exo = ExoPlayer.Builder(requireContext()).build()
        player = exo

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        exo.setAudioAttributes(audioAttributes, true)

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    b.progress.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                b.progress.visibility = View.GONE
                b.playerView.visibility = View.GONE
                val detail = error.message?.takeIf { it.isNotBlank() } ?: "设备不支持该编码格式"
                showError("这个视频无法播放\n$detail\n（App 不会闪退，可左右滑动查看其它文件）")
            }
        })

        b.playerView.player = exo

        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.playWhenReady = true
        exo.prepare()
    }

    // ---------------- image ----------------

    private fun setupImage(file: File) {
        b.playerView.visibility = View.GONE
        b.ivImage.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val drawable: Drawable? = withContext(Dispatchers.IO) { decodeImage(file) }

            if (_b == null) return@launch
            b.progress.visibility = View.GONE

            if (drawable != null) {
                b.ivImage.setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) {
                    anim = drawable
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                    b.ivImage.setOnClickListener { toggleAnimation() }
                } else {
                    b.ivImage.setOnClickListener(null)
                }
            } else {
                // ImageDecoder 失败时退回 Glide（静态图 / GIF 仍可显示）
                Glide.with(b.ivImage)
                    .load(file)
                    .placeholder(android.R.color.black)
                    .into(b.ivImage)
            }
        }
    }

    /** 解码并按需降采样，避免超大 webp 直接 OOM。失败返回 null，不抛异常。 */
    private fun decodeImage(file: File): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val max = MAX_DECODE_PX
                if (w > max || h > max) {
                    val scale = max.toFloat() / maxOf(w, h).toFloat()
                    decoder.setTargetSize(
                        (w * scale).roundToInt(),
                        (h * scale).roundToInt()
                    )
                }
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun toggleAnimation() {
        val a = anim ?: return
        try {
            if (a.isRunning) a.stop() else a.start()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun showError(message: String) {
        b.progress.visibility = View.GONE
        b.tvError.text = message
        b.tvError.visibility = View.VISIBLE
    }

    // ---------------- lifecycle ----------------

    override fun onPause() {
        super.onPause()
        // ViewPager2 只让当前页处于 RESUMED，所以这里等于"滑走了就暂停"
        player?.playWhenReady = false
        runCatching { anim?.stop() }
    }

    override fun onResume() {
        super.onResume()
        if (isVideo) player?.playWhenReady = true
        runCatching { anim?.start() }
    }

    override fun onDestroyView() {
        runCatching { anim?.stop() }
        anim = null

        player?.let { exo ->
            b.playerView.player = null
            exo.stop()
            exo.clearMediaItems()
            exo.release()
        }
        player = null

        _b = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_FILE = "arg_file"
        private const val ARG_MIME = "arg_mime"
        private const val ARG_NAME = "arg_name"
        private const val MAX_DECODE_PX = 2048

        fun newInstance(item: MediaItem): PlayerPageFragment =
            PlayerPageFragment().apply {
                arguments = bundleOf(
                    ARG_FILE to FileStore.fileFor(item.fileName).absolutePath,
                    ARG_MIME to item.mimeType,
                    ARG_NAME to item.displayName
                )
            }
    }
}
