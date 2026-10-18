package top.ccbase.campus.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * 用户自带的动图/视频当主题（2026-10-18 用户："也要支持用户自己上传图片或视频作为主题，
 * 支持 png/jpg/bmp/gif/mp4 等格式"）。
 *
 * 分工：
 * - **静态图**（png/jpg/bmp）走原来的 `BackgroundPhoto`（已支持，含浓淡、微动、粒子）；
 * - **gif / 视频**走这里：gif 用 `AnimatedImageDrawable`（系统自己解码循环，不耗 CPU 自己画），
 *   视频用 `VideoView` 循环、**静音**播放（背景不该出声，也不能抢音频焦点）；
 * - 桌面小组件放不了视频/gif，就从它们里头**抽 12 帧**当成动画（见 `WidgetFrames`）。
 *
 * 文件落在 `filesDir/appearance/theme_media.<ext>`，只留一份：换新的先删旧的。
 * 上限 80MB —— 再大就不是"当主题"了，手机解码也扛不住。
 */
object UserMedia {

    const val PREFIX = "media:"
    private const val DIR = "appearance"
    private const val MAX_BYTES = 80L * 1024 * 1024

    enum class Kind(val id: String, val label: String) {
        GIF("gif", "GIF 动图"),
        VIDEO("video", "视频"),
    }

    data class Media(val kind: Kind, val file: File)

    fun parse(spec: String): Kind? {
        if (!spec.startsWith(PREFIX)) return null
        val id = spec.removePrefix(PREFIX)
        return Kind.entries.firstOrNull { it.id == id }
    }

    fun spec(kind: Kind): String = PREFIX + kind.id

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** 当前这套媒体主题；文件被删了就当没设过 */
    fun current(ctx: Context): Media? {
        val d = dir(ctx)
        val f = d.listFiles()?.firstOrNull { it.name.startsWith("theme_media.") } ?: return null
        if (!f.exists() || f.length() == 0L) return null
        val kind = when (f.extension.lowercase()) {
            "gif" -> Kind.GIF
            "mp4", "webm", "mkv" -> Kind.VIDEO
            else -> return null
        }
        return Media(kind, f)
    }

    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.filter { it.name.startsWith("theme_media.") }?.forEach { runCatching { it.delete() } }
    }

    /**
     * 把选中的 uri 复制进来。
     *
     * @return 落盘后的类型；格式不支持/太大/读不出来返回 null（调用方给"换一个"的提示）
     */
    fun save(ctx: Context, uri: Uri): Kind? = runCatching {
        val cr = ctx.contentResolver
        val mime = (cr.getType(uri) ?: "").lowercase()
        val name = (uri.lastPathSegment ?: "").substringAfterLast('/').lowercase()
        val ext = when {
            mime == "image/gif" || name.endsWith(".gif") -> "gif"
            mime.startsWith("video/") && (mime.contains("mp4") || name.endsWith(".mp4")) -> "mp4"
            mime.startsWith("video/") -> "mp4" // 先按 mp4 存，解码交给 VideoView，不成会自己报错
            else -> return null
        }
        val bytes = cr.openInputStream(uri) ?: return null
        clear(ctx)
        val out = File(dir(ctx), "theme_media.$ext")
        bytes.use { input ->
            out.outputStream().use { o ->
                var total = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_BYTES) {
                        o.close()
                        out.delete()
                        return null
                    }
                    o.write(buf, 0, n)
                }
            }
        }
        if (out.length() == 0L) {
            out.delete()
            return null
        }
        if (ext == "gif") Kind.GIF else Kind.VIDEO
    }.getOrNull()

    /** 抽一帧/一张：给不做动画的地方（比如预览缩略图）用 */
    fun stillBitmap(ctx: Context, maxSide: Int = 720): Bitmap? {
        val m = current(ctx) ?: return null
        return runCatching {
            when (m.kind) {
                Kind.GIF -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(m.file)) { d, _, _ ->
                        d.setTargetSampleSize(sampleSize(m.file, maxSide))
                    }
                } else {
                    Bitmap.createScaledBitmap(
                        android.graphics.BitmapFactory.decodeFile(m.file.path) ?: return null,
                        maxSide, maxSide, true,
                    )
                }
                Kind.VIDEO -> android.media.MediaMetadataRetriever().use { r ->
                    r.setDataSource(m.file.path)
                    r.getFrameAtTime(0)
                }
            }
        }.getOrNull()
    }

    private fun sampleSize(f: File, maxSide: Int): Int {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return 1
        var s = 1
        while (o.outWidth / s > maxSide * 2) s *= 2
        return s
    }
}

/**
 * 铺满整屏的 gif/视频背景。静音、循环、不可点（不能挡住上面的按钮）。
 */
@Composable
fun UserMediaBackground(media: UserMedia.Media, modifier: Modifier = Modifier) {
    when (media.kind) {
        UserMedia.Kind.GIF -> AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setOnTouchListener { _, _ -> false }
                }
            },
            update = { iv ->
                if (iv.tag != media.file.path) {
                    iv.tag = media.file.path
                    iv.setImageDrawable(loadAnimated(media.file, iv.context))
                }
            },
        )
        UserMedia.Kind.VIDEO -> AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setOnTouchListener { _, _ -> false }
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        mp.setVolume(0f, 0f)   // 背景不该出声
                        start()
                    }
                }
            },
            update = { vv ->
                if (vv.tag != media.file.path) {
                    vv.tag = media.file.path
                    runCatching {
                        vv.setVideoPath(media.file.path)
                        vv.start()
                    }
                }
            },
        )
    }
}

private fun loadAnimated(f: File, ctx: Context): Drawable? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val src = ImageDecoder.createSource(f)
        val d = ImageDecoder.decodeDrawable(src, ImageDecoder.OnHeaderDecodedListener { d, _, _ ->
            if (d is AnimatedImageDrawable) {
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                d.start()
            }
        })
        d
    } else {
        // Android 9 以下没有 AnimatedImageDrawable：退回第一帧（静态），至少画面是对的
        android.graphics.drawable.BitmapDrawable(ctx.resources, android.graphics.BitmapFactory.decodeFile(f.path))
    }
}.getOrNull()

/** 让 VideoView 在离开页面时停下来，别在后台一直解码（省电，也避免和别的播放器抢设备） */
@Composable
fun StopOnLeave(vv: VideoView?) {
    DisposableEffect(vv) {
        onDispose { runCatching { vv?.pause() } }
    }
}
