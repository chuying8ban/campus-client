package top.ccbase.campus.data.remote

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import top.ccbase.campus.data.local.RemoteFontStore
import top.ccbase.campus.domain.FontIndex
import top.ccbase.campus.domain.RemoteFont
import top.ccbase.campus.update.DownloadEngine
import top.ccbase.campus.update.DownloadProgress
import top.ccbase.campus.update.DownloadTarget
import top.ccbase.campus.update.RealRangedFetcher

/**
 * 在线字体清单 + 下载安装。
 *
 * ## 为什么清单要「随包一份 + 云端可刷新」
 * - 随包那份（`assets/fonts.json`）保证**没网也能看到字体库长什么样**，不会一进页面就是空白；
 * - 云端那份（`fonts/index.json`）让我们以后加字体**不用发新版**——用户打开字体库就会拿到新清单。
 * 两次都读不到才用随包那份，绝不出现"清单为空 → 字体库看着像坏了"。
 *
 * ## 下载必须走 App 自己的传输层
 * 2026-09-17 真机教训（见 `Updater`）：二进制自己开 `URL.openConnection()` 会绕开「无 SNI」那条路，
 * 于是接口全绿、只有下载 `Connection reset`。这里同样用 [DownloadEngine] + [RealRangedFetcher]：
 * 断点续传、停滞重试、大小与 sha256 校验都是现成的，字体和安装包没有区别。
 */
object FontCatalog {

    private const val TAG = "FontCatalog"
    const val BASE = "https://dl.ccbase.top/fonts/"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 最近一次加载到的清单（Compose 状态，界面直接读） */
    val list = androidx.compose.runtime.mutableStateOf<List<RemoteFont>>(emptyList())

    /** 清单来自哪儿：assets / cloud / 本机缓存 —— 出问题时界面能说清「为什么少了几款」 */
    val source = androidx.compose.runtime.mutableStateOf("assets")

    private fun cacheFile(ctx: Context) = File(ctx.applicationContext.filesDir, "fonts_index.json")

    private fun readAssets(ctx: Context): List<RemoteFont> = runCatching {
        val text = ctx.assets.open("fonts.json").bufferedReader().use { it.readText() }
        json.decodeFromString<FontIndex>(text).fonts
    }.getOrDefault(emptyList())

    /** 启动时同步调用：先用能拿到的最好的一份，别让界面等网络 */
    fun loadCached(ctx: Context) {
        val cached = runCatching {
            cacheFile(ctx).takeIf { it.isFile }?.readText()?.let { json.decodeFromString<FontIndex>(it).fonts }
        }.getOrNull()
        if (!cached.isNullOrEmpty()) {
            list.value = cached
            source.value = "cache"
        } else {
            list.value = readAssets(ctx)
            source.value = "assets"
        }
    }

    /**
     * 拉云端清单。成功就写本机缓存并替换内存清单。
     * @return 成功与否（失败**不报错弹窗**：字体库离线可用，只是看不到新加的那几款）
     */
    suspend fun refresh(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val url = BASE + "index.json"
            val reply = top.ccbase.campus.net.Net.dial.call("GET", url, emptyMap(), null)
            if (reply.code != 200) return@runCatching false
            val idx = json.decodeFromString<FontIndex>(reply.text)
            if (idx.fonts.isEmpty()) return@runCatching false
            cacheFile(ctx).writeText(reply.text)
            withContext(Dispatchers.Main) {
                list.value = idx.fonts
                source.value = "cloud"
            }
            true
        }.getOrElse {
            Log.w(TAG, "字体清单刷新失败（离线照用随包那份）", it)
            false
        }
    }

    fun byId(id: String): RemoteFont? = list.value.firstOrNull { it.id == id }

    /** 按分组归类，保持清单顺序（同一家的字排在一起） */
    fun grouped(): List<Pair<String, List<RemoteFont>>> =
        list.value.groupBy { it.group }.map { (k, v) -> k to v }

    /**
     * 下载并安装一款字体。
     *
     * 落盘顺序：先下到 `fonts/<id>.part` → 校验大小 + sha256 → 改名成 `<id>.ttf`。
     * **校验不过就删掉半包**（字体文件坏了不可能"修好"，留着只会一直失败），
     * 然后 [RemoteFontStore.refresh] 让界面立刻知道它已可用。
     */
    suspend fun install(
        ctx: Context,
        rf: RemoteFont,
        /** 测试可换假链路：真实链路在 Robolectric 里连不了外网，但校验/改名/清理那几段必须被测到 */
        fetcher: top.ccbase.campus.update.RangedFetcher = RealRangedFetcher(),
        onProgress: (DownloadProgress) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = RemoteFontStore.dirForWrite(ctx)
            val dest = File(dir, rf.localName)
            val part = File(dir, rf.localName + ".part")
            // 本机已有同名文件但校验不过（上一版残留/手工拷进来的），先清掉再下
            if (dest.exists() && dest.length() != rf.bytes) dest.delete()

            val engine = DownloadEngine(fetcher)
            val target = DownloadTarget(
                url = BASE + rf.file,
                sha256 = rf.sha256,
                size = rf.bytes,
            )
            val r = engine.run(target, part, dest, onProgress)
            if (r.isFailure) {
                runCatching { part.delete() }
                return@runCatching Result.failure(r.exceptionOrNull() ?: IllegalStateException("字体下载失败"))
            }
            RemoteFontStore.refresh(ctx)
            Result.success(dest)
        }.getOrElse { Result.failure(it) }
    }
}
