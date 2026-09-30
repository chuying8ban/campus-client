package top.ccbase.campus.ui.me

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import top.ccbase.campus.BuildConfig
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Meta
import top.ccbase.campus.data.plan.TplLoader
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.data.remote.RemoteSync
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.StudentError
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.alarm.Rescheduler
import top.ccbase.campus.ui.settings.NudgeStrip
import top.ccbase.campus.update.UpdatePrefs

/**
 * 「我的」—— 账户、密码、关于。
 *
 * 这一页存在的唯一理由是**兑现登录页说过的话**：
 * "密码加密保存，在「我的」里随时可以删"。
 * 页面上每一个开关都必须真的有效，否则不如不写那句话。
 *
 * 危险操作（退出登录、删除服务器上的密码）一律二次确认：
 * 密码删掉之后服务器就没法每天替用户读课表了，这个后果要当面说清。
 */

/**
 * 后台管理页地址（作者专用，只读）。
 *
 * 为什么写成常量：测试要能断言**发出去的就是这个真地址**。
 * 对外链接一律走自有域名（用户明确不要 trycloudflare 那种临时隧道）。
 */
/** 分组标题：一根细上边线 + 小字，不用填充卡片（跟全局观感一致） */
@Composable
private fun SectionTitle(text: String, first: Boolean = false) {
    if (!first) Spacer(Modifier.height(30.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)))
    Spacer(Modifier.height(12.dp))
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
         color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
    Spacer(Modifier.height(6.dp))
}

/**
 * 「更新检查」那行后面的时刻（毫秒 → 本地 `MM-dd HH:mm`）。
 *
 * 显示用本地时区；**判据不在这里**（什么时候该查由 `UpdateLogic` 的节流决定，与显示无关）。
 */
private fun fmtCheckedAt(ms: Long): String =
    if (ms <= 0) "" else "（" + java.time.Instant.ofEpochMilli(ms)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) + "）"

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
             modifier = Modifier.width(96.dp))
        Text(v, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
    }
}

@Composable
private fun Action(
    text: String,
    hint: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }.padding(vertical = 13.dp)
    ) {
        Text(
            text, fontSize = 14.sp,
            color = if (danger) MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 0.9f else 0.35f)
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.88f else 0.3f),
        )
        hint?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, fontSize = 11.sp, lineHeight = 17.sp,
                 color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
        }
    }
}

@Composable
fun MeScreen(
    ctx: Context, db: CampusDb, api: CampusApi, version: String,
    onLogin: () -> Unit, onOpenBoard: () -> Unit = {},
    /** 「检查更新」的结果行：正在检查 / 已是最新 / 失败原因。null = 不显示 */
    updateHint: String? = null,
    /** 点「检查更新」。默认空实现，方便单测单独渲染本页 */
    onCheckUpdate: () -> Unit = {},
    /** 点「网络自检」：连不上服务器时用来定位断在哪一层 */
    onOpenDiag: () -> Unit = {},
    onOpenSafety: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    /** 点「手机自己抓课表」：教务密码不出手机的抓取路径 */
    onOpenCrawl: () -> Unit = {},
    /** 点「授权与白名单」：缺哪个权限、缺了会怎样、App 内直接去要 */
    onOpenPermissions: () -> Unit = {},
    /** 点「后台管理」：切成 App 内嵌的后台页（只有作者看得到；服务端还会再闸一次 403） */
    onOpenAdmin: () -> Unit = {},
    /** 给 App 提建议 —— **每个用户都有**这个入口（不像后台那节只给作者） */
    onOpenFeedback: () -> Unit = {},
    /** 点「监控」：进监控页。按服务端给的 can_grab 显隐；服务端还会再闸一次 */
    onOpenGrab: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(TokenStore.token(ctx)) }
    var name by remember { mutableStateOf(TokenStore.name(ctx) ?: "") }
    var sid by remember { mutableStateOf(TokenStore.studentId(ctx) ?: "") }
    var source by remember { mutableStateOf<String?>(null) }
    var syncedAt by remember { mutableStateOf<String?>(null) }
    // 「课表自检」（App 打开/回前台时自己核对）的结果与时刻 —— 不许静默，摆在这一页
    var autoCheck by remember { mutableStateOf<String?>(null) }
    var autoCheckAt by remember { mutableStateOf<String?>(null) }
    // 「更新检查」（进 App / 回前台时自动查版本）的结果与时刻 —— 同样不许静默：
    // 服务端清单 404、作者通道的随机段被轮换，表现都是"已是最新"，不留痕就只能靠猜。
    var updCheck by remember { mutableStateOf<String?>(null) }
    var updCheckAt by remember { mutableStateOf(0L) }
    // 服务端支不支持「重读教务课表」：yes/no/还不知道 —— 决定按钮副标题怎么说实话
    var resync by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    // 这一条消息要不要给「重新登录」按钮
    var needRelogin by remember { mutableStateOf(false) }
    var isAuthor by remember { mutableStateOf(TokenStore.isAuthor(ctx)) }
    var askLogout by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<String?>(null) }
    // 入口上直接写「还差几项」：缺权限的表现是静默失效（闹钟不响、更新装不上），
    // 不摆在明处就没人会去点它。页面内部还会每 1.5 秒自己重读。
    val missingPerms = top.ccbase.campus.alarm.SilenceDiag
        .permissionItems(top.ccbase.campus.alarm.DiagLog.snapshot(ctx))
        .filter { !it.ok }

    suspend fun reload() = withContext(Dispatchers.IO) {
        source = PlanApplier.source(db)
        syncedAt = db.dao().metaGet(PlanApplier.K_AT)
        autoCheck = db.dao().metaGet(PlanApplier.K_CHECK_RES)
        autoCheckAt = db.dao().metaGet(PlanApplier.K_CHECK_AT)
        updCheck = UpdatePrefs.lastResult(ctx)
        updCheckAt = UpdatePrefs.lastResultAt(ctx)
        resync = db.dao().metaGet(PlanApplier.K_RESYNC)
        token = TokenStore.token(ctx)
        name = TokenStore.name(ctx) ?: ""
        sid = TokenStore.studentId(ctx) ?: ""
    }
    LaunchedEffect(Unit) { reload() }

    /**
     * 补一次「作者标记」。
     *
     * 为什么不能只靠登录返回：已经在用的账号是**这个版本之前**登录的，本地没存过这个字段，
     * 不补的话他得重新登录一次才看得到入口。失败就沿用本地的值，不影响这一页其他内容。
     */
    LaunchedEffect(Unit) {
        val t = TokenStore.token(ctx) ?: return@LaunchedEffect
        (api.me(t) as? ApiResult.Ok)?.let { r ->
            TokenStore.saveAuthor(ctx, r.value.isAuthor)
            isAuthor = r.value.isAuthor
        }
    }

    // 看过就藏起来：不留在屏幕上给别人（或下一个拿起手机的人）看
    LaunchedEffect(revealed) {
        if (revealed != null) { delay(30_000); revealed = null }
    }

    /** 退回「从服务器同步」：能做的是这个，就只说这个（`honest` = 顺带说清为什么） */
    suspend fun fallbackSync(t: String, honest: Boolean = false) {
        try {
            when (val s = withTimeout(SyncLogic.STEP_TIMEOUT_MS) {
                RemoteSync.sync(ctx, db, api, t)
            }) {
                is ApiResult.Ok -> {
                    val body = SyncLogic.syncedText(s.value.counts)
                    msg = if (honest) "${SyncLogic.fallbackText(s.value.counts)}" else "$msg\n$body"
                    Rescheduler.request(ctx, reason = "sync-ok")
                }
                is ApiResult.Err -> {
                    val f = SyncLogic.fail(s.code, s.message)
                    if (f.needsRelogin) needRelogin = true
                    msg = "更新失败：${f.text}" + if (f.retryable) "（可以再点一次）" else ""
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MeScreen", "从服务器同步失败", e)
            msg = StudentError.tech(e) + "（已记录，可再试一次）"
        }
    }

    /**
     * 「立即更新课表」。
     *
     * 这个按钮以前是**假的**：只把服务端缓存再拉一遍（`RemoteSync.sync`），
     * 完全不读教务，副标题却写着"从教务系统重新读一次"。用户看到课表没变（体育课还是不在），
     * 而我们只会说"已更新" —— 那是最糟的一种骗人：它让人不再怀疑数据。
     *
     * 现在的顺序：
     *   ① 先让**服务端**按 EAMS 重读（`POST /timetable/sync`，服务端存着教务账号）；
     *   ② 读完再拉一次计划落地，闹钟跟着重排；
     *   ③ 服务端还不支持这条路由（404/405）→ 退回「从服务器同步」，并**如实说明**；
     *   ④ 401 → 明说登录过期，并给「重新登录」入口。
     * 每一步都有超时：不许再出现"转圈转到用户放弃"。
     */
    fun doSync() {
        if (busy) return
        val t = token ?: run { needRelogin = true; msg = "这台设备还没登录 App，先重新登录一次"; return }
        busy = true; msg = null; needRelogin = false
        scope.launch {
            try {
                when (val r = withTimeout(SyncLogic.STEP_TIMEOUT_MS) { api.timetableSync(t) }) {
                    is ApiResult.Ok -> {
                        // 服务器真的去教务读过了 → 把新计划拉下来（课表换了，提醒也必须重排）
                        if (r.value.noCreds.isNotEmpty() || !r.value.ok) {
                            msg = "${SyncLogic.serverSyncText(r.value)} —— 已改为从服务器同步。\n" +
                                "想让服务器能代读，在下面「密码」那一节里保存一次教务密码。"
                            fallbackSync(t)
                        } else {
                            val head = SyncLogic.serverSyncText(r.value)
                            when (val s = withTimeout(SyncLogic.STEP_TIMEOUT_MS) {
                                RemoteSync.sync(ctx, db, api, t)
                            }) {
                                is ApiResult.Ok -> {
                                    msg = "$head\n${SyncLogic.syncedText(s.value.counts)}"
                                    Rescheduler.request(ctx, reason = "sync-ok")
                                }
                                is ApiResult.Err -> msg = "$head\n但本机数据没刷上。${StudentError.TEXT}"
                            }
                        }
                    }
                    is ApiResult.Err -> {
                        val f = SyncLogic.fail(r.code, r.message)
                        if (r.code == 404 || r.code == 405) {
                            // 老服务端：能力没有就没有，**不许装作读过了**
                            withContext(Dispatchers.IO) {
                                db.dao().putMeta(listOf(
                                    Meta(PlanApplier.K_RESYNC, SyncLogic.RESYNC_NO)))
                            }
                            fallbackSync(t, honest = true)
                        } else if (f.needsRelogin) {
                            needRelogin = true
                            msg = f.text
                        } else {
                            msg = "更新失败：${f.text}" + if (f.retryable) "（可以再点一次）" else ""
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                android.util.Log.e("MeScreen", "更新课表超时", e)
                msg = SyncLogic.timeoutText("更新课表")
            } catch (e: Exception) {
                // **同步失败绝不能让 App 崩**：
                // 2026-09-17 同学点「立即更新课表」直接闪退 —— 服务端计划里残留的
                // 学习步骤引用了已删除的任务，落库时 FOREIGN KEY 失败，
                // 异常从 scope.launch 里抛出去 → 进程死。
                // 这种错是**数据**的问题，用户能做的只有"再试一次/等我们修"，
                // 所以这里兜住并如实报出来（日志里留堆栈，界面上给一句话）。
                android.util.Log.e("MeScreen", "更新课表失败", e)
                msg = StudentError.tech(e) + "（已记录，可再试一次）"
            }
            reload()
            busy = false
        }
    }

    /** 重登：清掉死令牌再进登录页（留着它只会一直撞 401） */
    fun relogin() {
        TokenStore.clear(ctx)
        onLogin()
    }

    fun doLogout() {
        val t = token
        scope.launch {
            busy = true
            // 先把令牌作废，再清本地 —— 顺序反了的话，服务器上会留下一个活着的会话
            if (t != null) runCatching { api.logout(t) }
            withContext(Dispatchers.IO) { PlanApplier.releaseToSeed(db) }
            TokenStore.clear(ctx)
            reload()
            busy = false
            msg = "已退出登录。课表和打卡记录都留在手机里，不会丢。"
        }
    }

    fun doReveal() {
        val t = token ?: return
        scope.launch {
            busy = true
            when (val r = api.credentials(t)) {
                is ApiResult.Ok -> { revealed = r.value.password; msg = null }
                is ApiResult.Err -> msg = if (r.code == 404) "服务器上没有保存密码。" else r.message
            }
            busy = false
        }
    }

    fun doDeleteCred() {
        val t = token ?: return
        scope.launch {
            busy = true
            when (val r = api.deleteCredentials(t)) {
                is ApiResult.Ok -> msg = "已删除服务器上保存的教务密码。\n课表不再每天自动更新，其他功能不受影响。"
                is ApiResult.Err -> msg = r.message
            }
            reload()
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 22.dp)) {
        Text("我的", fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
             color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Text("中国石油大学（北京）克拉玛依校区学生自用工具 · 非学校官方产品", fontSize = 11.sp,
             color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f))

        SectionTitle("账户")
        if (token == null) {
            Text("还没登录。登录后课表每天从你的教务系统自动更新。", fontSize = 13.sp, lineHeight = 21.sp,
                 color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().height(46.dp)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy) { onLogin() },
                contentAlignment = Alignment.Center,
            ) { Text("用学号登录", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
        } else {
            KV("学号", sid)
            KV("姓名", name.ifBlank { "—" })
            KV("课表来源", if (source == "remote") "你的教务系统" else "还没同步到你的课表")
            KV("上次更新", syncedAt?.take(16)?.replace("T", " ") ?: "还没更新过")
            // 「App 自己核对课表」的结果也摆出来：用户原话就是"不要用户自己发现错误"，
            // 那这件事有没有做成、上次是什么时候做的，界面就得看得见。
            if (!autoCheck.isNullOrBlank()) {
                KV("课表自检", autoCheck + (autoCheckAt?.take(16)?.replace("T", " ")?.let { "（$it）" } ?: ""))
            }
            // 「App 自己查更新」的结果同样摆出来：作者通道的随机段被轮换、服务端清单 404，
            // 在界面上的表现都是"已是最新" —— 没有这一行，失效就无从发现（1.85 那轮踩过）。
            if (!updCheck.isNullOrBlank()) {
                KV("更新检查", updCheck + fmtCheckedAt(updCheckAt))
            }
            Spacer(Modifier.height(10.dp))
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("处理中…", fontSize = 12.sp,
                         color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                }
            } else {
                // 副标题按服务端能力说实话：能重读教务就说重读，不能就说是"从服务器同步"
                Action("立即更新课表", SyncLogic.syncSubtitle(resync)) { doSync() }
                // 让服务器代读，还是手机自己去读，用户自己选 —— 后者密码不出手机
                Action("手机自己抓课表", "用我的教务账号读一次，密码不出手机",
                       onClick = { onOpenCrawl() })
            }
            // 登录过期时给一条明路：光说"失败"没用，用户只会反复点
            if (needRelogin && !busy) {
                Action("重新登录", "登录已过期 —— 重新登录后课表才会继续更新", onClick = { relogin() })
            }
            Action("退出登录", "课表和打卡记录留在手机里；仅停止自动更新") { askLogout = true }
        }

        SectionTitle("提醒")
        NudgeStrip()

        SectionTitle("看板")
        Action("打卡热力 / 里程碑", "连续天数、完成度、阶段进度", onClick = { onOpenBoard() })

        // 「后台」这一节整份只进**作者包**。两层闸各管一件事：
        //  · AUTHOR_BUILD 是编译期闸 —— 公开包里它恒为 false，整段是死代码
        //    （开 R8 会被整段摘掉，连"后台管理"这几个字都不进 dex）；
        //  · isAuthor 是运行期闸 —— 作者包里登着别人的账号时也看不到。
        // 注意 isAuthor 只是界面开关：真闸在服务端（非作者 403），改本地也拿不到数据。
        if (BuildConfig.AUTHOR_BUILD && isAuthor) {
            SectionTitle("后台")
            Action("后台管理", "用户数量、用量、系统状态；只有作者能进", enabled = !busy) {
                // 走 App 内嵌，不再丢给系统浏览器：丢浏览器会弹「是否允许打开 XX 浏览器」，
                // 点「拒绝」时 Android 不抛异常 → 一声不吭；进去还得再登一次。
                // 内嵌页见 ui/admin/AdminWebScreen.kt
                onOpenAdmin()
            }
            // 监控页顶部有免责说明；这一格按服务端给的 can_grab 显隐，
            // 底部栏那一格也是同一个判据。真闸仍在服务端（can_grab / 非作者 403）。
            if (TokenStore.canGrab(ctx)) {
                Action("监控", "盯可选课程余量；只提醒，不代抢", enabled = !busy) {
                    onOpenGrab()
                }
            }
        }

        SectionTitle("密码")
        Text(
            "教务系统密码由你本人提供，加密后保存在服务器上、不以明文存放，只用于每天自动登录读课表。\n" +
            "服务端提供查看能力，但那个入口只对作者本人开放；你随时可以删掉它。",
            fontSize = 12.sp, lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
        // ⚠️ 这一处认 **is_author**，不再和监控共用 can_grab 那个闸（2026-09-29 拆闸）：
        // 监控是公开功能、按 can_grab 显隐；而密码是别人自己的东西，
        // 回传一次就多一个泄露面 —— 凭据回显永远只有作者能看见。
        if (token != null && TokenStore.isAuthor(ctx)) {
            Action("查看服务器上保存的密码", "只有你能用这个入口，30 秒后自动隐藏",
                   enabled = !busy) { doReveal() }
            revealed?.let { pw ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        pw,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text("隐藏", fontSize = 12.sp,
                         color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                         modifier = Modifier.clickable { revealed = null }.padding(6.dp))
                }
            }
        }
        if (token != null) {
            Action("删除服务器上保存的密码", "删掉后不再自动更新课表，其他功能不受影响",
                   danger = true, enabled = !busy) { askDelete = true }
        } else {
            Text("（登录后才会保存）", fontSize = 12.sp,
                 color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))
        }

        SectionTitle("关于")
        KV("版本", version)
        // 更新入口放明处（用户明确不喜欢"只能改地址才能进"的隐藏设计）
        Action(
            text = "检查更新",
            hint = updateHint ?: "当前版本 $version · 发现新版本时会提示",
            onClick = onCheckUpdate,
        )
        KV("数据来源", "你本人的教务系统")
        // 提建议：放在「关于」里、**所有用户都能看到**（用户 2026-09-21：每个用户都可以提）
        Action(
            text = "给 App 提建议",
            hint = "想加什么功能、哪里不好用、哪里看不懂 —— 直接写给我",
            onClick = onOpenFeedback,
        )
        // 自检入口也放明处：连不上服务器时，用户能自己跑一次，不用等我猜
        Action(
            text = "网络自检",
            hint = "连不上服务器时用它：会显示解析到的 IP、443 通不通、接口能不能过",
            onClick = onOpenDiag,
        )
        // 安全声明放明处：用户第一反应是「我凭什么把教务密码给你」——不能只藏在登录页一句小字里
        Action(
            text = "安全与隐私",
            hint = "你的教务密码怎么加密、谁能看到、怎么一键删除 —— 逐条写清楚",
            onClick = onOpenSafety,
        )
        Action(
            text = "外观",
            hint = "深色 / 浅色 / 跟随系统 · 背景图（内置渐变或自己上传，只存本机）",
            onClick = onOpenAppearance,
        )
        Action(
            text = "授权与白名单",
            hint = if (missingPerms.isEmpty()) {
                "全部已授权 ✓ 课前静音、闹钟提醒、App 更新都能正常工作"
            } else {
                "还差 ${missingPerms.size} 项（${missingPerms.joinToString("、") { it.name }}）" +
                    " —— 缺了会静默失效，点这里一项项去开"
            },
            onClick = onOpenPermissions,
        )
        // 监控入口不在这一页的正文里：底部栏那一格按 can_grab 显隐，
        // 作者包里「后台」那一节还有一个。这一页曾经有一行 KV("监控功能", …) ——
        // 那是把功能当卖点宣布，跟"它按 can_grab 开放"对不上，已删除。
        Spacer(Modifier.height(14.dp))
        Text(
            "这是一个学生自己做的工具，不是学校官方产品。\n" +
            "课表、成绩、考试安排以教务系统为准；本工具只做展示与提醒，不对数据准确性负责。",
            fontSize = 11.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f),
        )

        msg?.let {
            Spacer(Modifier.height(22.dp))
            Text(it, fontSize = 12.sp, lineHeight = 19.sp,
                 color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f))
        }
        Spacer(Modifier.height(40.dp))
    }

    if (askLogout) {
        AlertDialog(
            onDismissRequest = { askLogout = false },
            title = { Text("退出登录？", fontSize = 16.sp) },
            text = { Text("退出后不再自动更新课表。已经下载到手机上的课表、任务和打卡记录都会保留。", fontSize = 13.sp) },
            confirmButton = { TextButton({ askLogout = false; doLogout() }) { Text("退出登录") } },
            dismissButton = { TextButton({ askLogout = false }) { Text("取消") } },
        )
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("删除服务器上的密码？", fontSize = 16.sp) },
            text = {
                Text("删掉之后，服务器就不能再替你每天读课表了 —— 课表会停在上次更新的版本，" +
                     "新增或调整的课不会自动出现。想恢复的话，退出登录再登一次即可。", fontSize = 13.sp)
            },
            confirmButton = { TextButton({ askDelete = false; doDeleteCred() }) { Text("删除") } },
            dismissButton = { TextButton({ askDelete = false }) { Text("取消") } },
        )
    }
}
