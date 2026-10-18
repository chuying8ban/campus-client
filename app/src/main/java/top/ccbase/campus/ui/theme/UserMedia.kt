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
import android.view.View.MeasureSpec
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * 媒体**分槽存**（2026-10-18 用户："小部件的视频变成主题的了"）。
     *
     * 之前 App 背景和桌面小组件共用同一个 `theme_media.<ext>`：给小组件换一个视频，
     * App 的背景也跟着变，反过来也一样 —— 用户看到的就是"乱"。现在两套各存各的。
     */
    enum class Slot(val key: String) {
        APP("app"),
        WIDGET("widget"),
    }

    data class Media(val kind: Kind, val file: File)

    fun parse(spec: String): Kind? {
        if (!spec.startsWith(PREFIX)) return null
        val id = spec.removePrefix(PREFIX)
        return Kind.entries.firstOrNull { it.id == id }
    }

    fun spec(kind: Kind): String = PREFIX + kind.id

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun fileOf(ctx: Context, slot: Slot): File? {
        val d = dir(ctx)
        val hit = d.listFiles()?.firstOrNull { it.name.startsWith("theme_media_${slot.key}.") }
        if (hit != null) return hit
        // 旧版本只存了一份 `theme_media.<ext>`：把它认成 App 的，用户不至于升级后背景凭空消失
        if (slot == Slot.APP) {
            return d.listFiles()?.firstOrNull { it.name.startsWith("theme_media.") }
        }
        return null
    }

    /** 当前这套媒体主题；文件被删了就当没设过 */
    fun current(ctx: Context, slot: Slot = Slot.APP): Media? {
        val f = fileOf(ctx, slot) ?: return null
        if (!f.exists() || f.length() == 0L) return null
        val kind = when (f.extension.lowercase()) {
            "gif" -> Kind.GIF
            "mp4", "webm", "mkv" -> Kind.VIDEO
            else -> return null
        }
        return Media(kind, f)
    }

    fun clear(ctx: Context, slot: Slot? = null) {
        dir(ctx).listFiles()
            ?.filter { f ->
                if (slot == null) f.name.startsWith("theme_media") else f.name.startsWith("theme_media_${slot.key}.")
            }
            ?.forEach { runCatching { it.delete() } }
    }

    /**
     * 把选中的 uri 复制进来。
     *
     * @return 落盘后的类型；格式不支持/太大/读不出来返回 null（调用方给"换一个"的提示）
     */
    fun save(ctx: Context, uri: Uri, slot: Slot = Slot.APP): Kind? = runCatching {
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
        clear(ctx, slot)
        val out = File(dir(ctx), "theme_media_${slot.key}.$ext")
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

    /**
     * 直接从字节落盘（图库里点一张动态图时用）。
     *
     * 图库返回的是 mp4/webm；不认 mime 就按魔数判：`ftyp` 在 offset 4 = MP4，`1A 45 DF A3` = WebM。
     */
    fun saveBytes(ctx: Context, bytes: ByteArray, slot: Slot = Slot.APP): Kind? = runCatching {
        val isMp4 = bytes.size > 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
        val isWebm = bytes.size > 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        if (!isMp4 && !isWebm) return null
        clear(ctx, slot)
        val out = File(dir(ctx), "theme_media_${slot.key}." + if (isWebm) "webm" else "mp4")
        out.outputStream().use { it.write(bytes) }
        if (out.length() == 0L) {
            out.delete()
            return null
        }
        Kind.VIDEO
    }.getOrNull()

    /** 把一个槽的媒体复制到另一个槽（"小组件也用 App 那段视频"） */
    fun copyTo(ctx: Context, from: Slot, to: Slot): Kind? = runCatching {
        val src = fileOf(ctx, from) ?: return null
        val bytes = src.readBytes()
        saveBytes(ctx, bytes, to)
    }.getOrNull()

    /** 抽一帧/一张：给不做动画的地方（比如预览缩略图）用 */
    fun stillBitmap(ctx: Context, slot: Slot = Slot.APP, maxSide: Int = 720): Bitmap? {
        val m = current(ctx, slot) ?: return null
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
 *
 * 2026-10-18 用户："动态主题很不稳定，有时候无法填充完整个 app 或是切换的速度很慢"：
 * - **填不满**：`VideoView.onMeasure` 是按视频自身宽高比量尺寸的，放进全屏 Box 就成了上下黑边
 *   （截图里下半截是黑的）。改用 [FillVideoView] 强制按容器尺寸量。
 * - **切换慢/黑一下**：视频 prepare 之前是黑的。先异步解一帧当封面铺上，等真正开始播再淡出。
 * - **不稳**：解不出来/编解码不支持时停在封面，而不是留一块黑。
 */
@Composable
fun UserMediaBackground(media: UserMedia.Media, modifier: Modifier = Modifier) {
    when (media.kind) {
        UserMedia.Kind.GIF -> {
            // GIF 解码放到 IO 线程：以前在 `update` 里同步解，换主题那一下会明显卡一下
            // （用户 2026-10-19："app 主题的切换要快速"）。解好之前先不铺图，铺底色，不闪。
            val gifCtx = LocalContext.current
            val drawable by produceState<android.graphics.drawable.Drawable?>(null, media.file.path) {
                value = withContext(Dispatchers.IO) {
                    runCatching { loadAnimated(media.file, gifCtx) }.getOrNull()
                }
            }
            AndroidView(
                modifier = modifier.fillMaxSize(),
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setOnTouchListener { _, _ -> false }
                        // 必须自己给 MATCH_PARENT：AndroidView 默认按 wrap 量，ImageView 会缩到图片大小
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { iv ->
                    if (iv.tag != media.file.path) {
                        iv.tag = media.file.path
                        iv.setImageDrawable(null)
                    }
                    val d = drawable
                    if (d != null && iv.drawable !== d) iv.setImageDrawable(d)
                },
            )
        }
        UserMedia.Kind.VIDEO -> {
            val ctx = LocalContext.current
            var playing by remember(media.file.path) { mutableStateOf(false) }
            // 封面帧：跟着素材走，换素材自动重解
            val poster by produceState<Bitmap?>(null, media.file.path) {
                value = withContext(Dispatchers.IO) { runCatching { frameOf(media.file) }.getOrNull() }
            }
            Box(modifier.fillMaxSize()) {
                poster?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize().alpha(if (playing) 1f else 0f),
                    factory = { c ->
                        FillVideoView(c).apply {
                            setOnTouchListener { _, _ -> false }
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                mp.setVolume(0f, 0f)   // 背景不该出声
                                playing = true
                                start()
                            }
                            setOnErrorListener { _, _, _ ->
                                playing = false   // 停在封面，别留黑
                                true
                            }
                            setOnInfoListener { _, what, _ ->
                                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) playing = true
                                false
                            }
                        }
                    },
                    update = { vv ->
                        if (vv.tag != media.file.path) {
                            vv.tag = media.file.path
                            playing = false
                            runCatching { vv.setVideoPath(media.file.path) }
                        }
                    },
                )
            }
        }
    }
}

/** 视频背景必须按容器尺寸量，否则 VideoView 会按片源自带比例缩成一条（上下/左右留黑）。 */
private class FillVideoView(ctx: Context) : VideoView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(
            if (w > 0) w else suggestedMinimumWidth,
            if (h > 0) h else suggestedMinimumHeight,
        )
    }
}

/** 取视频的第一帧（给封面用；失败返回 null，调用方继续走黑底而不是崩） */
private fun frameOf(f: File): Bitmap? = runCatching {
    android.media.MediaMetadataRetriever().use { r ->
        r.setDataSource(f.path)
        r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }
}.getOrNull()

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
