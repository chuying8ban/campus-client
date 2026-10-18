package top.ccbase.campus.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlin.math.max

/**
 * 桌面小组件的**动态主题**：把一段动画预渲染成 N 帧，交给 AdapterViewFlipper 轮播。
 *
 * 为什么是"帧轮播"而不是"真动画"：RemoteViews 只允许有限几种 View，自定义 View / 
 * AnimationDrawable / 视频都不在里面；能自己动起来的只有 `AdapterViewFlipper`
 * （翻页器，`setFlipInterval` 控制间隔）。所以这里按固定时间点渲染 24 帧、每 90ms 翻一张，
 * 一个循环 2.16 秒 —— 对"极光/落雪/星点"这类慢动作足够像动画，且**不需要 App 常驻**。
 *
 * 内存：帧按格子实际尺寸的 60% 渲染（放大一点点不会糊，因为这些画面本来就是模糊的光），
 * 每张几十 KB，24 帧一个主题约 1.5MB，主题换掉时 LRU 自然淘汰。
 */
class WidgetFrameService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Frames(applicationContext, intent)
}

/** 帧缓存：同一主题 + 同一尺寸只渲染一次。进程被杀就重来，没关系。 */
private object FrameCache {
    private val cache = LruCache<String, List<Bitmap>>(4)

    fun get(ctx: Context, kind: DynPainter.Kind, accent: Int, w: Int, h: Int): List<Bitmap> {
        val key = "${kind.id}|$accent|$w|$h"
        cache.get(key)?.let { return it }
        val list = render(ctx, kind, accent, w, h)
        cache.put(key, list)
        return list
    }

    /** 24 帧，每帧间隔 0.09s —— 与 layout 里的 flipInterval 对齐。 */
    private fun render(ctx: Context, kind: DynPainter.Kind, accent: Int, w: Int, h: Int): List<Bitmap> {
        val fw = max(120, (w * 0.6f).toInt())
        val fh = max(80, (h * 0.6f).toInt())
        val out = ArrayList<Bitmap>(FRAMES)
        for (i in 0 until FRAMES) {
            val bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            DynPainter.frame(c, kind, i * STEP_SEC, fw.toFloat(), fh.toFloat(), accent)
            out += bmp
        }
        return out
    }

    const val FRAMES = 24
    const val STEP_SEC = 0.09f
}

private class Frames(
    private val ctx: Context,
    intent: Intent,
) : RemoteViewsService.RemoteViewsFactory {

    private val kind = DynPainter.Kind.of(intent.getStringExtra("kind") ?: "aurora") ?: DynPainter.Kind.AURORA
    private val accent = intent.getIntExtra("accent", 0xFFA06BFF.toInt())
    private val w = intent.getIntExtra("w", 900)
    private val h = intent.getIntExtra("h", 400)

    private var frames: List<Bitmap> = emptyList()

    override fun onCreate() {
        frames = FrameCache.get(ctx, kind, accent, w, h)
    }

    override fun onDataSetChanged() {
        frames = FrameCache.get(ctx, kind, accent, w, h)
    }

    override fun onDestroy() {
        // 位图由 FrameCache 持有（别的实例可能还在用），这里不回收
    }

    override fun getCount(): Int = frames.size

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, top.ccbase.campus.R.layout.widget_frame)
        val bmp = frames.getOrNull(position)
        if (bmp != null) rv.setImageViewBitmap(top.ccbase.campus.R.id.frame_img, bmp)
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}

/** 给 Activity 用的小工具：把"用这个动态主题"落成一个 spec 字符串。 */
object WidgetDyn {
    const val PREFIX = "dynw:"

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
}
