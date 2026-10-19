package top.ccbase.campus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * 桌面小组件的**动态背景**：预渲染 12 帧交给布局里的 `ViewFlipper` 轮播。
 *
 * ## 为什么不用 AdapterViewFlipper（2026-10-18 用户截图：桌面「载入窗口小部件时出现问题」）
 * 那一版用的是 `AdapterViewFlipper` + `RemoteViewsService`。理论上是官方支持的组合，
 * 但它要求**桌面进程去 bind 我们的 Service**；MIUI 的桌面不这么干，于是它自己的 RemoteViews
 * inflater 直接把整张卡充气失败 —— 用户看到的就是那句报错。换成 `ViewFlipper` + 12 个
 * 现成的 ImageView：只用到 ImageView / ViewFlipper 这两个最基础的支持成员，帧由 provider
 * 直接 `setImageViewBitmap` 灌进去，**不需要任何 Service、不需要桌面配合**。
 *
 * ## 两种动态
 * 1. `dynw:<kind>:<argb>` —— 纯动效（极光/落雪…），整块现画；
 * 2. `dyni:<effect>` —— **让用户选的那张二次元图动起来**（微动 + 樱瓣/雪/光斑/星点），
 *    像动态壁纸。图来自 `WidgetThemes.userImageFile(ctx)`（外观里选的那张）。
 */
object WidgetFrames {

    /**
     * 帧数：**4**（不是 12，也不是 6）。
     *
     * RemoteViews 里的位图是连像素一起塞进 binder 事务的，一次 updateAppWidget 总大小超过
     * ~1MB 就会被 launcher 整块丢掉（MIUI 更是静默丢 → 桌面只剩文字，2026-10-19 用户截图）。
     * 6 帧 × 17 万像素 = 4MB，正是"主题显示不上"的元凶。现在 4 帧 × 5 万像素 ≈ 800KB。
     */
    const val FRAMES = 4
    const val STEP_SEC = 0.32f

    /** 叠在插画上的"动态效果" */
    enum class ImgEffect(val id: String, val label: String, val hint: String) {
        DRIFT("drift", "微动", "画面缓慢推近再退回，像实拍镜头"),
        PETALS("petals", "樱瓣", "花瓣从画面前面飘过"),
        SNOW("snow", "落雪", "细雪往下落"),
        BOKEH("bokeh", "光斑", "暖色光点一明一暗"),
        STARS("stars", "星点", "细小的星星在闪"),
        FIREFLIES("fireflies", "萤火", "夏夜的光点在飘"),
        BUBBLES("bubbles", "气泡", "气泡慢慢往上浮");

        companion object {
            fun of(id: String?): ImgEffect? = entries.firstOrNull { it.id == id }
        }
    }

    // 只留一份：一屏小组件只会用一种 spec，存 4 份等于白占 4 倍内存
    private val cache = LruCache<String, List<Bitmap>>(1)

    /** 换了播放速度/尺寸就得重做：帧是按旧"时间轴"画的，缓存里那批会跟不上 */
    fun clear() {
        cache.evictAll()
    }

    /** 按 spec 生成 [FRAMES] 帧；返回 null = 不是动态 spec（调用方走静态分支）。 */
    fun forSpec(ctx: Context, spec: String, wPx: Int, hPx: Int, speed: Float = 1f): List<Bitmap>? {
        WidgetDyn.parse(spec)?.let { (kind, accent) ->
            val key = "p|${kind.id}|$accent|$wPx|$hPx|s$speed"
            cache.get(key)?.let { return it }
            diskLoad(ctx, key)?.let { cache.put(key, it); return it }
            return render(ctx, key, wPx, hPx) { c, fw, fh, t0 ->
                // 播放速度：把"时间轴"走快/走慢，不是简单抽帧 —— 慢放时粒子真的更慢
                val t = t0 / speed.coerceAtLeast(0.2f)
                DynPainter.frame(c, kind, t, fw.toFloat(), fh.toFloat(), accent)
            }
        }
        WidgetDyn.parseImage(spec)?.let { effect ->
            val file = WidgetThemes.userImageFile(ctx)
            if (!file.exists()) return null
            val src = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
            val key = "i|${effect.id}|${file.lastModified()}|$wPx|$hPx|s$speed"
            cache.get(key)?.let { return it }
            diskLoad(ctx, key)?.let { cache.put(key, it); return it }
            val accent = WidgetThemes.of(WidgetThemes.spec(ctx)).accentColor
            return render(ctx, key, wPx, hPx) { c, fw, fh, t0 ->
                drawCinemagraph(c, src, effect, t0 / speed.coerceAtLeast(0.2f), fw.toFloat(), fh.toFloat(), accent)
            }
        }
        // 用户自己传的 gif / 视频（2026-10-18）：桌面放不了视频，就抽 12 帧轮播。
        // 视频取前 ~1.4 秒里的 12 个时间点；gif 按总时长均分 —— 两者都按"整张卡片"裁切填满。
        top.ccbase.campus.ui.theme.UserMedia.parse(spec)?.let { kind ->
            val media = top.ccbase.campus.ui.theme.UserMedia.current(
                ctx, top.ccbase.campus.ui.theme.UserMedia.Slot.WIDGET,
            ) ?: return null
            if (media.kind != kind) return null
            val key = "m|${kind.id}|${media.file.lastModified()}|$wPx|$hPx"
            cache.get(key)?.let { return it }
            diskLoad(ctx, key)?.let { cache.put(key, it); return it }
            return renderMedia(ctx, key, media, wPx, hPx)
        }
        return null
    }

    /**
     * 帧就按**视图实际大小**渲染（只在大尺寸时整体等比缩小，省内存）。
     *
     * 为什么不再按 0.6 渲染：2026-10-18 用户截图「没有做到完全圆角，太丑了」——
     * 那张图左边一个大圆角、右边却是直角竖切。根因是 ImageView 用 `centerCrop`：
     * 帧比视图小（0.6 倍）就必须放大填满，而 launcher 报的 minWidth 和视图真实宽高
     * 对不上，放大后被两边裁掉，右半边的圆角正好被裁在画外。
     * 现在：帧尺寸 = 视图尺寸（或等比缩小后的同比例），ImageView 改成 `fitXY`，
     * 1:1 铺进去，四个角都画在画内。
     */
    /**
     * 帧的**磁盘缓存**：换主题慢的主因不是下载，是"抽帧 + 画 6 张 + 过一遍圆角"。
     *
     * 每次桌面刷新都重算一遍，用户看到的就是转圈几秒；而且 provider 会被系统随时杀掉重建，
     * 光靠内存缓存等于没有。落成 PNG（必须 PNG：圆角是透明的，JPEG 会把角填黑），
     * 下次直接读文件，几十毫秒。目录名用 key 的哈希，key 里已经带了视频的修改时间，
     * 换了素材自然就换目录，不会读到旧帧。
     */
    private fun frameDir(ctx: Context, key: String): java.io.File =
        java.io.File(ctx.cacheDir, "wframes/" + Integer.toHexString(key.hashCode()))

    private fun diskLoad(ctx: Context, key: String): List<Bitmap>? = runCatching {
        val dir = frameDir(ctx, key)
        if (!dir.isDirectory) return null
        val out = ArrayList<Bitmap>(FRAMES)
        for (i in 0 until FRAMES) {
            val f = java.io.File(dir, "f$i.png")
            if (!f.isFile || f.length() == 0L) return null
            out += BitmapFactory.decodeFile(f.absolutePath) ?: return null
        }
        out
    }.getOrNull()

    private fun diskSave(ctx: Context, key: String, frames: List<Bitmap>) = runCatching {
        val dir = frameDir(ctx, key)
        dir.mkdirs()
        // 只保留当前这份，别的 spec 的旧帧清掉（桌面只会用一种）
        dir.parentFile?.listFiles()?.forEach { if (it.absolutePath != dir.absolutePath) it.deleteRecursively() }
        frames.forEachIndexed { i, b ->
            java.io.FileOutputStream(java.io.File(dir, "f$i.png")).use {
                b.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun frameSize(wPx: Int, hPx: Int): Triple<Int, Int, Float> {
        // 别按满尺寸画：fitXY 放大肉眼看不出（画面本来就是柔焦的图/光）。**缩小不会切掉圆角** ——
        // fitXY 只拉伸不裁切，这正是 3.2.1 那次修法的关键。
        // 每帧像素预算：4 帧 × 5 万像素 ≈ 800KB（ARGB_8888）。
        // RemoteViews 把像素一起塞进 binder 事务，总量超过 ~1MB 桌面就会整块丢掉。
        val perFrame = 40_000f
        val px = wPx.toLong() * hPx
        // 不设下限：预算优先。1200×600 的格子 scale≈0.24 刚好；2475×1375 的格子要 0.108，
        // 原来那个 0.24 下限会让 4 帧加起来 3MB，超了 binder 上限 → 桌面整块丢掉（画质换可见）。
        val scale = minOf(0.7f, sqrt(perFrame / px.toFloat())).coerceAtLeast(0.04f)
        val fw = max(120, (wPx * scale).toInt())
        val fh = max(80, (hPx * scale).toInt())
        return Triple(fw, fh, scale)
    }

    /** gif/视频 → 12 帧（按时间点抽，不是把同一帧画 12 遍） */
    private fun renderMedia(
        ctx: Context,
        key: String,
        media: top.ccbase.campus.ui.theme.UserMedia.Media,
        wPx: Int,
        hPx: Int,
    ): List<Bitmap>? {
        val (fw, fh, scale) = frameSize(wPx, hPx)
        val radius = WidgetArt.radiusFor(hPx, 1f) * scale
        val out = ArrayList<Bitmap>(FRAMES)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        fun blit(src: Bitmap?) {
            if (src == null) return
            val bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val scale = max(fw.toFloat() / src.width, fh.toFloat() / src.height)
            val dw = src.width * scale
            val dh = src.height * scale
            c.drawBitmap(
                src,
                Rect(0, 0, src.width, src.height),
                RectF((fw - dw) / 2f, (fh - dh) / 2f, (fw + dw) / 2f, (fh + dh) / 2f),
                paint,
            )
            // 暗到看不出画面的素材先抬一档（用户那张"我的图"只有 20/255）
            out += WidgetArt.rounded(ImgLift.lift(bmp), radius)
        }

        when (media.kind) {
            top.ccbase.campus.ui.theme.UserMedia.Kind.VIDEO -> {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(media.file.path)
                    // 2026-10-19：原来固定取 0 / 0.32 / 0.64 / 0.96s。很多动图开头是淡入黑场，
                    // 四帧全黑 → 桌面小组件"没有主题"。改成按**整段时长**均匀取 12%~84%，
                    // 保证每一帧都落在正片里，而不是片头过渡上。
                    val durMs = mmr.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
                    )?.toLongOrNull()?.takeIf { it > 400 } ?: 1_400L
                    for (i in 0 until FRAMES) {
                        val frac = 0.12f + 0.72f * i / (FRAMES - 1).coerceAtLeast(1)
                        val us = (durMs * 1000L * frac).toLong()
                        blit(mmr.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST))
                    }
                } catch (e: Exception) {
                    return null
                } finally {
                    runCatching { mmr.release() }
                }
            }
            top.ccbase.campus.ui.theme.UserMedia.Kind.GIF -> {
                val movie = runCatching { android.graphics.Movie.decodeFile(media.file.path) }.getOrNull()
                if (movie != null) {
                    val dur = movie.duration().takeIf { it > 0 } ?: 1000
                    for (i in 0 until FRAMES) {
                        val bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
                        val c = Canvas(bmp)
                        val scale = max(fw.toFloat() / movie.width(), fh.toFloat() / movie.height())
                        val dw = (movie.width() * scale).toInt()
                        val dh = (movie.height() * scale).toInt()
                        c.save()
                        c.translate((fw - dw) / 2f, (fh - dh) / 2f)
                        c.scale(scale, scale)
                        movie.setTime((i * dur / FRAMES) % dur)
                        movie.draw(c, 0f, 0f)
                        c.restore()
                        // 暗到看不出画面的素材先抬一档（用户那张"我的图"只有 20/255）
            out += WidgetArt.rounded(ImgLift.lift(bmp), radius)
                    }
                } else {
                    // Movie 解不了（有的 gif 是 PNG 伪装）：退回单帧
                    blit(BitmapFactory.decodeFile(media.file.path))
                }
            }
        }
        if (out.isEmpty()) return null
        while (out.size < FRAMES) out += out.last()   // 不足 6 帧就重复填补
        cache.put(key, out)
        diskSave(ctx, key, out)
        return out
    }

    /**
     * 渲染 N 帧并统一裁圆角（RemoteViews 没有圆角属性，只能画进位图）。
     * 帧按视图实际大小渲染（超大时整体等比缩小），配 `fitXY` 1:1 铺满 —— 见 [frameSize]。
     */
    private fun render(ctx: Context, key: String, wPx: Int, hPx: Int, draw: (Canvas, Int, Int, Float) -> Unit): List<Bitmap> {
        val (fw, fh, scale) = frameSize(wPx, hPx)
        val radius = WidgetArt.radiusFor(hPx, 1f) * scale
        val out = ArrayList<Bitmap>(FRAMES)
        for (i in 0 until FRAMES) {
            val bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            draw(c, fw, fh, i * STEP_SEC)
            val rounded = WidgetArt.rounded(bmp, radius)
            // 中间那张原始帧立刻回收：6 张一起留就是双倍峰值（真机上这一下最容易 OOM）
            if (rounded !== bmp) bmp.recycle()
            out += rounded
        }
        cache.put(key, out)
        diskSave(ctx, key, out)
        return out
    }

    /**
     * 一张静态插画 → 12 帧"活的"画面。
     *
     * 两件事叠起来才像动态壁纸：① 图自己在缓慢推近（Ken Burns，幅度只有 4%，但人眼立刻能看出"活的"）；
     * ② 画面前面再飘一层粒子（樱瓣/雪/光斑…）。只做粒子会像"贴纸在动"，只做推近又太闷。
     */
    // internal：动态壁纸（DynWallpaperService）也走这一支，两处画法必须一致
    internal fun drawCinemagraph(
        c: Canvas,
        src: Bitmap,
        effect: ImgEffect,
        t: Float,
        w: Float,
        h: Float,
        accent: Int,
    ) {
        val cycle = FRAMES * STEP_SEC
        val phase = (t / cycle) * 2f * PI.toFloat()
        // 推近 1.00 → 1.06 并轻微横移；偶数帧也不回零，避免"呼吸"太机械
        val zoom = 1.03f + 0.03f * sin(phase)
        val panX = w * 0.012f * sin(phase * 0.7f)
        val panY = h * 0.010f * cos(phase * 0.9f)

        val base = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val s = base * zoom
        val dw = src.width * s
        val dh = src.height * s
        val left = (w - dw) / 2f + panX
        val top = (h - dh) / 2f + panY

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(src, Rect(0, 0, src.width, src.height), RectF(left, top, left + dw, top + dh), paint)

        // 粒子层：只有"微动"不加粒子，其余按自己那一种
        if (effect != ImgEffect.DRIFT) {
            val kind = when (effect) {
                ImgEffect.PETALS -> DynPainter.Kind.PETALS
                ImgEffect.SNOW -> DynPainter.Kind.SNOW
                ImgEffect.BOKEH -> DynPainter.Kind.BOKEH
                ImgEffect.STARS -> DynPainter.Kind.STARS
                ImgEffect.FIREFLIES -> DynPainter.Kind.FIREFLIES
                ImgEffect.BUBBLES -> DynPainter.Kind.BUBBLES
                ImgEffect.DRIFT -> DynPainter.Kind.PETALS
            }
            DynPainter.particles(c, kind, t, w.toFloat(), h.toFloat(), accent, 0.55f)
        }

        // 底部压一点点，保证卡片里的文字读得清（和静态图片主题同一口径）
        val scrim = Paint()
        scrim.shader = android.graphics.LinearGradient(
            0f, h * 0.45f, 0f, h.toFloat(),
            intArrayOf(0x00000000, 0x66000000), null, android.graphics.Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, h * 0.45f, w.toFloat(), h.toFloat(), scrim)
    }
}

/** 动态 spec 的解析（App 与小组件共用一套写法）。 */
object WidgetDyn {
    const val PREFIX = "dynw:"
    const val IMG_PREFIX = "dyni:"

    fun spec(kind: DynPainter.Kind, accent: Int): String =
        PREFIX + kind.id + ":" + Integer.toHexString(accent)

    fun parse(spec: String): Pair<DynPainter.Kind, Int>? {
        if (!spec.startsWith(PREFIX)) return null
        val body = spec.removePrefix(PREFIX)
        val parts = body.split(":")
        val kind = DynPainter.Kind.of(parts.getOrElse(0) { "" }) ?: return null
        val accent = runCatching { parts.getOrNull(1)?.toLong(16)?.toInt() }.getOrNull()
            ?: 0xFFA06BFF.toInt()
        return kind to accent
    }

    /** `dyni:petals` —— 让选中的那张图动起来 */
    fun imageSpec(effect: WidgetFrames.ImgEffect): String = IMG_PREFIX + effect.id

    fun parseImage(spec: String): WidgetFrames.ImgEffect? {
        if (!spec.startsWith(IMG_PREFIX)) return null
        return WidgetFrames.ImgEffect.of(spec.removePrefix(IMG_PREFIX))
    }
}
