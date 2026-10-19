package top.ccbase.campus.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 壁纸级动态主题 —— "换源"（2026-10-19 用户："小控件的动态主题都太阴间了…实在不行就给小控件的主题换源"）。
 *
 * 为什么是换源，而不是继续调参数：
 *   之前那 72 款（12 种动效 × 6 个色系）是我们用 `DynPainter` **自己画的粒子场**，
 *   底色必然是深色（粒子要亮才看得见），所以整体观感就是"阴间"——参数怎么调都是黑底紫光。
 *   动态壁纸之所以好看，是因为**内容本身是一张好图**，动只是锦上添花。
 *
 * 现在的做法：主题 = 一张在线横版插画（[ArtLibrary] 走自己的服务器搜 safebooru，不直连境外），
 * 再套上已有的"微动"画笔（Ken Burns 推近 + 轻微粒子）。图按主题 id 缓存在 cache/wall/ 下，
 * 下载一次以后离线可用；换主题只换 id。
 */
object WallDyn {

    const val PREFIX = "dynwall:"

    data class Item(
        val id: String,
        val label: String,
        val hint: String,
        val query: String,
        val effect: WidgetFrames.ImgEffect,
    )

    /**
     * 6 个方向，全部选**明亮/白天**的题材 —— 这正是"不阴间"的关键。
     * query 交给服务端搜索，服务端会按 orient=wide 返回横版。
     */
    val items: List<Item> = listOf(
        Item("sky", "晴空", "蓝天白云", "scenery sky clouds", WidgetFrames.ImgEffect.BOKEH),
        Item("sakura", "樱花", "飘落的樱瓣", "sakura scenery", WidgetFrames.ImgEffect.PETALS),
        Item("sea", "海边", "浪与阳光", "ocean beach scenery", WidgetFrames.ImgEffect.BOKEH),
        Item("flower", "花海", "满屏的花", "flower field scenery", WidgetFrames.ImgEffect.DRIFT),
        Item("forest", "森林", "林间光斑", "forest sunlight scenery", WidgetFrames.ImgEffect.FIREFLIES),
        Item("snow", "雪景", "细雪飘落", "winter snow scenery", WidgetFrames.ImgEffect.SNOW),
    )

    fun parse(spec: String): Item? {
        if (!spec.startsWith(PREFIX)) return null
        val id = spec.removePrefix(PREFIX)
        return items.firstOrNull { it.id == id }
    }

    fun specOf(id: String): String = PREFIX + id

    fun file(ctx: Context, id: String): File {
        val dir = File(ctx.cacheDir, "wall")
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, "$id.png")
    }

    fun ready(ctx: Context, id: String): Boolean = file(ctx, id).let { it.isFile && it.length() > 1024 }

    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "walldyn").apply { isDaemon = true } }
    private val pending: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /**
     * 保证这张图在本地；没有就后台去搜一张横版、下载、落盘，成功后回主线程 [onReady]。
     * 单飞：同一个 id 并发点多次只下一次。
     */
    fun ensure(ctx: Context, id: String, onReady: () -> Unit) {
        val app = ctx.applicationContext
        if (ready(app, id)) { onReady(); return }
        if (!pending.add(id)) return
        io.execute {
            runCatching {
                val item = items.firstOrNull { it.id == id } ?: error("没有这个主题")
                val page = ArtRepo.searchLive(item.query, 1, "wide", 24, animOnly = false, sort = "hot")
                val best = page.items.firstOrNull { it.orient == "wide" } ?: page.items.firstOrNull()
                    ?: error("没搜到合适的图")
                val bmp = ArtRepo.full(best) ?: error("图下载失败")
                // 长边 1600 就够：4 帧 × 3200 宽会直接把进程顶爆（24G 内存是电脑，不是手机）
                val maxW = 1600
                val scaled = if (bmp.width > maxW) {
                    val h = (bmp.height.toLong() * maxW / bmp.width).toInt().coerceAtLeast(1)
                    android.graphics.Bitmap.createScaledBitmap(bmp, maxW, h, true)
                } else bmp
                FileOutputStream(file(app, id)).use { out ->
                    scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                if (scaled !== bmp) bmp.recycle()
            }
            pending.remove(id)
            Handler(Looper.getMainLooper()).post { runCatching { onReady() } }
        }
    }
}
