package top.ccbase.campus.update

import java.io.File

/**
 * 「谁在用更新包目录」—— 静默下载与手动下载共用同一个目录，
 * 未完成所以必须有人盯着，不然两份下载会互相踩（2026-10-19 用户：
 * 「静默更新和用户手动更新似乎会冲突，导致用户更新不起来」）。
 *
 * ## 冲突的真实形状（不是猜的，对着代码逐条查过）
 * 两边都调 [Updater.download]，都往同一个 `filesDir/update/` 里写：
 *
 * 1. **同一个半包文件**：`campus-<版本>.apk.part` + `.part.json`。
 *    手动下载（Uploader/DataSpace 之外那条 `startDownload`）根本没有「有人在用就跳过」的检查，
 *    静默那条也只有 `kick()` 入口挡了一下 —— 手动点「立即更新」时，静默那条可能**正在下**。
 *    两个 `FileOutputStream(part, append)` 同时往同一文件追加 ⇒ 字节交错 ⇒ 长度对得上、
 *    sha256 对不上 ⇒ 引擎把半包清掉重下 ⇒ 另一条刚好还在写，写进一个已删除的文件，
 *    于是下出来大小不对/校验不过，**两条一起失败，用户看到的就是「更新不起来」**。
 * 2. **半包被对家清掉**：`Updater.download` 开头 `clearOthers()`、`Engine.confirmedOffset()`
 *    发现半包不是本版本的会直接 `part.delete()`。对家重启一次 App 再下一次，就能把
 *    正在续传的那条半包删掉。
 * 3. **下好的包被删**：`dest` 同名（同版本号！）—— 手动那条下完 `dest.delete()` 再改名，
 *    静默那条「已就绪」指的是同一个文件 ⇒ 就绪标记还在、文件却没了/正在被覆盖。
 * 4. **两套进度互相盖**：静默下载**不发** [ProgressSink]（只报完成），于是手动的进度浮条
 *    看不到另一份在跑，两条进度条一起刷同一块界面。
 *
 * ## 修法：一个进程内唯一的「更新包目录租约」
 * - 同一时刻只允许**一条**下载或安装持有租约（[LeaseKind]）；
 * - 静默那条拿不到就**安静退让**（下次进 App 再来，不打扰用户）；
 * - 手动那条拿不到就**自动转成「等对方下完就用它」**（[awaitReady]），而不是傻等或重新下；
 * - 释放必须走 [Lease.release]（幂等），所以调用点一律 `try/finally`。
 *
 * 纯 JVM、无 Android API —— 可以直接单测（见 `UpdateLeaseTest`）。
 */
enum class LeaseKind(val label: String) {
    /** 静默后台下载：拿不到租约就安静退让 */
    SILENT("静默下载"),
    /** 用户手动点的下载：拿不到就等对方下完，不重复下 */
    MANUAL("手动下载"),
    /** 拉起系统安装器（装的时候别让另一条去改同一个 apk） */
    INSTALL("安装"),
}

/**
 * 租约记录。用 `@JvmField` 的普通对象 + [LockGuard] 包住，避免依赖任何 Android API。
 */
object UpdateLease {

    /** 当前持有者的说明（null = 空闲）。出错排查时直接打到日志里。 */
    @Volatile
    var holder: String? = null
        private set

    private val lock = Any()

    /** 尝试拿租约；成功返回 [Lease]，失败返回 null（调用方各自决定退让还是等待）。 */
    fun tryAcquire(kind: LeaseKind, what: String = ""): Lease? = synchronized(lock) {
        if (holder != null) return null
        val label = if (what.isBlank()) kind.label else "${kind.label} · $what"
        holder = label
        Lease(label)
    }

    /** 只查不拿（界面用：现在有没有别的更新在跑，要不要把「立即更新」按钮改成「正在后台下载」）。 */
    fun isBusy(): Boolean = holder != null

    private fun releaseInner(lease: Lease?) {
        synchronized(lock) {
            if (lease == null) return
            if (holder == lease.label) holder = null
        }
    }

    /** 租约本体。**release 幂等**，重复释放不会把别人的租约清掉。 */
    class Lease internal constructor(internal val label: String) {
        private var released = false

        val active: Boolean get() = !released

        fun release() {
            if (released) return
            released = true
            releaseInner(this)
        }
    }
}

/**
 * 「等对方下完」的轮询上限：静默那条在校园网里可能要下一两分钟，
 * 15 分钟足够；超时就告诉用户「后台那份还没下好，你可以直接自己下」——
 * **不许无限等**（用户点完按钮没反应等于按钮坏了）。
 */
const val AWAIT_READY_MS = 15 * 60 * 1000L
const val AWAIT_STEP_MS = 300L

/**
 * 手动更新遇到「静默那条正在跑」时该怎么做。
 *
 * @param ready 对方是否已经下好（已下好的包在 `ready` 里）
 * @param busy  租约是否仍被占着
 * @param step  已经等了多少毫秒
 * @return [Await.Next] 继续等、[Await.UseReady] 直接用对方下好的包、[Await.GiveUp] 放弃等待
 */
sealed class Await {
    data object Next : Await()
    data object UseReady : Await()
    data class GiveUp(val why: String) : Await()

    companion object {
        fun decide(ready: Boolean, busy: Boolean, waitedMs: Long, limitMs: Long = AWAIT_READY_MS): Await = when {
            ready -> UseReady
            !busy -> GiveUp("后台下载已经结束但没下好")
            waitedMs >= limitMs -> GiveUp("等了 ${limitMs / 60_000} 分钟后台还在下，网络可能很慢")
            else -> Next
        }
    }
}

/**
 * 同一版本「谁已经下好了」的判定。
 *
 * 手动下载结束后要把结果交给静默那条（免得它 30 分钟后再下一次同样的 7MB），
 * 反过来说静默那条下好了、用户又点了手动，也不该再下 —— 判据必须是
 * **文件真的在磁盘上 + versionCode 对得上**，而不是只看 SharedPreferences 的标记
 * （标记在、文件可能已经没了：清缓存、对家删包、改名失败都会造成这种「假就绪」）。
 */
object ReadyCheck {
    fun isReady(store: File, code: Int, currentCode: Int): Boolean =
        code > currentCode && store.isFile && store.length() > 0
}
