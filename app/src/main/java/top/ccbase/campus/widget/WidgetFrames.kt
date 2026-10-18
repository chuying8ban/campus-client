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

    const val FRAMES = 12
    const val STEP_SEC = 0.12f

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

    private val cache = LruCache<String, List<Bitmap>>(4)

    /** 按 spec 生成 12 帧；返回 null = 不是动态 spec（调用方走静态分支）。 */
    fun forSpec(ctx: Context, spec: String, wPx: Int, hPx: Int): List<Bitmap>? {
        WidgetDyn.parse(spec)?.let { (kind, accent) ->
            val key = "p|${kind.id}|$accent|$wPx|$hPx"
            cache.get(key)?.let { return it }
            return render(key, wPx, hPx) { c, fw, fh, t ->
                DynPainter.frame(c, kind, t, fw.toFloat(), fh.toFloat(), accent)
            }
        }
        WidgetDyn.parseImage(spec)?.let { effect ->
            val file = WidgetThemes.userImageFile(ctx)
            if (!file.exists()) return null
            val src = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
            val key = "i|${effect.id}|${file.lastModified()}|$wPx|$hPx"
            cache.get(key)?.let { return it }
            val accent = WidgetThemes.of(WidgetThemes.spec(ctx)).accentColor
            return render(key, wPx, hPx) { c, fw, fh, t ->
                drawCinemagraph(c, src, effect, t, fw.toFloat(), fh.toFloat(), accent)
            }
        }
        // 用户自己传的 gif / 视频（2026-10-18）：桌面放不了视频，就抽 12 帧轮播。
        // 视频取前 ~1.4 秒里的 12 个时间点；gif 按总时长均分 —— 两者都按"整张卡片"裁切填满。
        top.ccbase.campus.ui.theme.UserMedia.parse(spec)?.let { kind ->
            val media = top.ccbase.campus.ui.theme.UserMedia.current(ctx) ?: return null
            if (media.kind != kind) return null
            val key = "m|${kind.id}|${media.file.lastModified()}|$wPx|$hPx"
            cache.get(key)?.let { return it }
            return renderMedia(ctx, key, media, wPx, hPx)
        }
        return null
    }

    /** gif/视频 → 12 帧（按时间点抽，不是把同一帧画 12 遍） */
    private fun renderMedia(
        ctx: Context,
        key: String,
        media: top.ccbase.campus.ui.theme.UserMedia.Media,
        wPx: Int,
        hPx: Int,
    ): List<Bitmap>? {
        val fw = max(120, (wPx * 0.6f).toInt())
        val fh = max(80, (hPx * 0.6f).toInt())
        val radius = WidgetArt.radiusFor(hPx, 1f) * 0.6f
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
            out += WidgetArt.rounded(bmp, radius)
        }

        when (media.kind) {
            top.ccbase.campus.ui.theme.UserMedia.Kind.VIDEO -> {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(media.file.path)
                    for (i in 0 until FRAMES) {
                        // 抽帧用微秒；取 0~1.4s 这段，正好和 12 帧 × 120ms 对上
                        val us = (i * STEP_SEC * 1_000_000f).toLong()
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
                        out += WidgetArt.rounded(bmp, radius)
                    }
                } else {
                    // Movie 解不了（有的 gif 是 PNG 伪装）：退回单帧
                    blit(BitmapFactory.decodeFile(media.file.path))
                }
            }
        }
        if (out.isEmpty()) return null
        while (out.size < FRAMES) out += out.last()   // 不足 12 帧就重复填补
        cache.put(key, out)
        return out
    }

    /**
     * 渲染 N 帧并统一裁圆角（RemoteViews 没有圆角属性，只能画进位图）。
     * 帧按 60% 尺寸渲染：画面是模糊的光/图，放大一点看不出来，内存省 60%。
     */
    private fun render(key: String, wPx: Int, hPx: Int, draw: (Canvas, Int, Int, Float) -> Unit): List<Bitmap> {
        val fw = max(120, (wPx * 0.6f).toInt())
        val fh = max(80, (hPx * 0.6f).toInt())
        val radius = WidgetArt.radiusFor(hPx, 1f) * 0.6f
        val out = ArrayList<Bitmap>(FRAMES)
        for (i in 0 until FRAMES) {
            val bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            draw(c, fw, fh, i * STEP_SEC)
            out += WidgetArt.rounded(bmp, radius)
        }
        cache.put(key, out)
        return out
    }

    /**
     * 一张静态插画 → 12 帧"活的"画面。
     *
     * 两件事叠起来才像动态壁纸：① 图自己在缓慢推近（Ken Burns，幅度只有 4%，但人眼立刻能看出"活的"）；
     * ② 画面前面再飘一层粒子（樱瓣/雪/光斑…）。只做粒子会像"贴纸在动"，只做推近又太闷。
     */
    private fun drawCinemagraph(
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
