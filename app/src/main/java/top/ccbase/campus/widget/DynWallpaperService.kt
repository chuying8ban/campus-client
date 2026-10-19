package top.ccbase.campus.widget

import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * 真·60 帧的动态主题 = **动态壁纸**（2026-10-19 用户：「小部件的主题依旧没有做到流畅播放，
 * 我希望能够做到 60 帧播放」）。
 *
 * 为什么不是把小组件刷新调快：
 * 小组件每一帧都要把一张位图通过 binder 交给**桌面进程**，再由桌面重绘。这条路上
 *  · 位图有 1MB 的 binder 上限（我们已经在 90k 像素上压过一轮，再大 MIUI 直接丢图）；
 *  · 桌面自己会节流，`updateAppWidget` 高频调用会被降频甚至丢弃；
 *  · 每次推送都是跨进程拷贝 + 整块重绘，电池和内存都扛不住。
 * 所以 `RemoteViews` 在框架上**不存在** 60fps 这条路 —— 之前 4 帧 + 30 分钟一次，
 * 看着就是"几乎不动"。
 *
 * 能真 60 帧的地方只有一处：**自己拿 Surface 画**。动态壁纸就是这条路：
 *  · 同一个 [DynPainter]，同一套主题（小组件里选的那支，含速度/浓淡）；
 *  · 自己起一个绘制线程，按 ~16ms 的节拍锁 Surface 画一帧；
 *  · 桌面滑动、开 App 都在它上面，视觉上就是"整屏在呼吸"的动态壁纸。
 *
 * 设置入口：主题页的「设为动态壁纸」→ 系统壁纸选择器里确认（必须用户自己点确认，
 * 应用不能静默换壁纸，这是 Android 的硬规矩）。
 */
class DynWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DynEngine()

    private inner class DynEngine : Engine() {

        private var holder: SurfaceHolder? = null
        private var thread: HandlerThread? = null
        private var handler: Handler? = null
        private var visible = false
        private var startedAt = 0L
        private var speed = 1f

        private val tick = object : Runnable {
            override fun run() {
                if (!visible) return
                drawOnce()
                handler?.postDelayed(this, FRAME_MS)
            }
        }

        override fun onSurfaceCreated(h: SurfaceHolder) {
            super.onSurfaceCreated(h)
            holder = h
            readTheme()
            startedAt = System.nanoTime()
        }

        override fun onSurfaceDestroyed(h: SurfaceHolder) {
            visible = false
            stopLoop()
            holder = null
            super.onSurfaceDestroyed(h)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                readTheme()
                startLoop()
            } else {
                stopLoop()
            }
        }

        override fun onSurfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {
            super.onSurfaceChanged(h, format, w, hh)
            // 尺寸变了立刻补一帧，避免旋转/折叠后短暂黑屏
            if (visible) drawOnce()
        }

        /** 每次可见时重读一次主题：用户在 App 里换了主题，回到桌面就是新的。 */
        private fun readTheme() {
            speed = runCatching { WidgetThemes.speed(this@DynWallpaperService) }.getOrDefault(1f)
        }

        private fun startLoop() {
            if (handler != null) return
            val t = HandlerThread("dyn-wallpaper").also { it.start() }
            thread = t
            handler = Handler(t.looper).also {
                it.postDelayed(tick, FRAME_MS)
            }
        }

        private fun stopLoop() {
            handler?.removeCallbacksAndMessages(null)
            handler = null
            thread?.quitSafely()
            thread = null
        }

        private fun drawOnce() {
            val h = holder ?: return
            var canvas: Canvas? = null
            try {
                canvas = h.lockCanvas()
                if (canvas == null) return
                drawFrame(canvas, h.surfaceFrame.width().toFloat(), h.surfaceFrame.height().toFloat())
            } catch (_: Throwable) {
                // 桌面切换、锁屏时会短暂拿不到 surface —— 下一帧再试，不崩
            } finally {
                if (canvas != null) runCatching { h.unlockCanvasAndPost(canvas) }
            }
        }

        private fun drawFrame(c: Canvas, w: Float, h: Float) {
            val ctx = this@DynWallpaperService
            val spec = runCatching { WidgetThemes.spec(ctx) }.getOrDefault("")
            val t = ((System.nanoTime() - startedAt) / 1_000_000_000.0).toFloat() / speed.coerceAtLeast(0.2f)

            // ① 纯动态主题（粒子 / 缓动）—— 直接画
            val dyn = runCatching { WidgetDyn.parse(spec) }.getOrNull()
            if (dyn != null) {
                DynPainter.frame(c, dyn.first, t, w, h, dyn.second)
                return
            }
            // ② 用户自己的图 / 图库素材：画图 + 轻微流动（电影感），复用同一支画笔
            val effect = runCatching { WidgetDyn.parseImage(spec) }.getOrNull()
            if (effect != null) {
                val src = runCatching {
                    android.graphics.BitmapFactory.decodeFile(WidgetThemes.userImageFile(ctx).absolutePath)
                }.getOrNull()
                if (src != null) {
                    WidgetFrames.drawCinemagraph(c, src, effect, t, w, h, 0xFFFFFFFF.toInt())
                    return
                }
            }
            // ③ 什么动态都没有（纯色/静态图主题）：给一层极缓的呼吸底，至少不是死图
            DynPainter.frame(c, DynPainter.Kind.DUSK, t * 0.6f, w, h, 0xFF7C6CF0.toInt())
        }
    }

    private companion object {
        /** 60fps = 16.6ms；给 16 留一点余量，免得每帧都排在下一个 vsync 之后变成 30fps。 */
        const val FRAME_MS = 16L
    }
}
