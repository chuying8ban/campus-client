package top.ccbase.campus.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.remote.RemoteSync
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.domain.activitiesFrom
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.EamsAuthLost
import top.ccbase.campus.net.EamsClient
import top.ccbase.campus.net.EamsLoginFailed
import top.ccbase.campus.net.Net
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.overlaySurface
import top.ccbase.campus.net.StudentError

/**
 * 「用我的教务账号抓课表」—— 把读课表这件事挪到**手机自己做**。
 *
 * 以前是服务端拿着存在服务器上的账号密码去教务读课表（密码在服务器上，
 * 只有 AES-GCM 加密 + 权限收紧兜着）。现在这条路是：
 *
 *   手机登录教务 → 手机抓课表 → **只把课表数据**传给我们服务器重建规划
 *
 * 所以在这条路径上，服务端**全程不碰教务、不经手密码**。
 *
 * 四条必须守住的：
 * 1. **密码不保存**：只在点「开始抓取」这一次用于登录，函数走完就清掉（不写库、不上传）。
 * 2. **教务请求不通随重定向 + 走无 SNI 兜底**（`Net.dial`）：认证失效时教务是 302 跳登录页，
 *    跟了就会把登录页 HTML 当 JSON 解，报一个和真因无关的错。
 *    而部分校园网/移动链路会按域名拦 TLS，`Net.dial` 会记住哪条路走得通。
 * 3. **只传课表数据**：这不是"少传点"的美化说法，是代码事实 —— 上传体里只有 activities。
 * 4. **不许静默卡住**（2026-09-18 补）：上传那条路被服务端 401 拒过（这条接口要求合法
 *    token）。所以现在每一步都有超时、另有一条看门狗兜底，失败一律说清是哪一种
 *    （登录过期就**给重登入口**），成功则把"读到了什么"摆出来。
 *
 * @param onRelogin 重登入口。登录已过期时给用户一个能直接走的路，
 *   而不是让他自己回「我的」页碰运气。
 */
@Composable
fun CrawlScreen(
    db: CampusDb,
    api: CampusApi,
    onClose: () -> Unit,
    onRelogin: () -> Unit = {},
    // 看门狗延时（默认就是生产值）。**测试必须能把它设大**：Compose 测试里时钟会被推进，
    // 看门狗（delay）和抓取流程（真 IO 线程跳）会赛跑 —— 全量跑负载一重就偶发红
    // （2026-09-20 闸门红在「服务端还没有这条接口时要说清楚_不能说成网络故障」，单跑却是绿的）。
    // 时序可注入之后这个比赛就不存在了：测试传一个"永远到不了"的值。
    watchdogMs: Long = SyncLogic.WATCHDOG_MS,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sid by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    // 这一轮的结果要不要给「重新登录」按钮（只有 401 / 没令牌 才给）
    var needRelogin by remember { mutableStateOf(false) }
    // 轮次号：看门狗把界面解绑之后，还在阻塞里的旧协程回来时**不许**再改界面
    var gen by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { sid = TokenStore.studentId(ctx).orEmpty() }
    }

    /**
     * 收尾。只认**当前轮次**：`my != gen` 说明用户已经点了新的一轮（或看门狗已经交卷），
     * 这条回来的老结果直接丢掉 —— 否则会出现"上一条的报错盖住这一条的进度"。
     */
    fun finish(my: Int, text: String?, isErr: Boolean, relogin: Boolean = false) {
        if (my != gen) return
        if (relogin) needRelogin = true
        if (isErr) err = text else ok = text
        busy = false
        step = ""
    }

    /** 重登：把死令牌清掉，再交给登录页 —— 留着它 App 只会一直拿它去撞 401 */
    fun relogin() {
        TokenStore.clear(ctx)
        onRelogin()
    }

    fun run() {
        if (busy || sid.isBlank() || pw.isBlank()) return
        busy = true; ok = null; err = null; needRelogin = false; step = "正在登录教务…"
        val sid0 = sid
        val pw0 = pw
        val my = ++gen
        // 看门狗：教务/网络都是**阻塞式** socket IO，协程取消要等阻塞调用自己返回才生效 ——
        // 光靠 withTimeout 挡不住"永远停在正在登录教务…"（真机踩过：用户截图里那句一直在）。
        // 它到点就把界面解绑、说清停在哪一步，保证这个页面**永远会给出结果**。
        scope.launch {
            delay(watchdogMs)
            if (my == gen && busy) {
                android.util.Log.e("CrawlScreen", "抓课表看门狗触发，停在：$step")
                err = SyncLogic.timeoutText(step)
                busy = false
                step = ""
            }
        }
        scope.launch {
            try {
                // 令牌在**出发前**就检查一次：过期的令牌没必要让用户先白填一遍教务密码
                val token = withContext(Dispatchers.IO) { TokenStore.token(ctx) }
                if (token.isNullOrBlank()) {
                    finish(my, "这台设备还没有登录 App：先重新登录一次再来抓课表", true, relogin = true)
                    return@launch
                }
                if (TokenStore.expired(ctx)) {
                    finish(my, "本机记的登录时间已经过期：${SyncLogic.RELOGIN}", true, relogin = true)
                    return@launch
                }
                // 教务这一整段放在 IO 线程：它是阻塞式 HTTP，跑在主线程会直接抛
                // NetworkOnMainThreadException（真机踩过，界面只显示"网络异常"）
                val acts = withTimeout(SyncLogic.STEP_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        val client = EamsClient(Net.dial)
                        client.login(sid0, pw0)
                        step = "正在读课表…"
                        val raw = client.fetchTimetableJson()
                        activitiesFrom(raw) ?: throw IllegalStateException("课表里没有上课活动")
                    }
                }
                val stats = SyncLogic.stats(acts)
                step = "正在重建我的规划…"
                // **上传前重新读一次令牌**：教务登录可能花掉十几秒，期间令牌可能已经被
                // 吊销/换掉（这条上传要求合法 token）。这一读就是为了不再拿旧值去撞 401。
                val tokNow = withContext(Dispatchers.IO) { TokenStore.token(ctx) } ?: token
                when (val r = withTimeout(SyncLogic.STEP_TIMEOUT_MS) {
                    api.uploadActivities(tokNow, acts.toString())
                }) {
                    is ApiResult.Err -> {
                        val f = SyncLogic.fail(r.code, r.message)
                        android.util.Log.e("CrawlScreen", "上传课表被拒：${r.code} ${r.message}")
                        finish(my, if (f.needsRelogin) f.text else "上传课表失败：${f.text}",
                               true, f.needsRelogin)
                        return@launch
                    }
                    is ApiResult.Ok -> Unit
                }
                step = "正在刷新本机数据…"
                when (val r = withTimeout(SyncLogic.STEP_TIMEOUT_MS) {
                    RemoteSync.sync(ctx, db, api, tokNow)
                }) {
                    // 成功必须把"读到了什么"说清楚，不能只有一句"成功"
                    is ApiResult.Ok -> finish(my, SyncLogic.uploadOkText(stats, r.value.counts), false)
                    is ApiResult.Err -> {
                        val f = SyncLogic.fail(r.code, r.message)
                        finish(my,
                            if (f.needsRelogin) "课表已经交上去了，但本机数据没刷上：${f.text}"
                            else "课表已经交上去了，但刷新本机数据失败：${f.text}",
                            true, f.needsRelogin)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                android.util.Log.e("CrawlScreen", "抓课表超时，停在：$step", e)
                finish(my, SyncLogic.timeoutText(step), true)
            } catch (e: EamsLoginFailed) {
                finish(my, "学号或密码不对（要输教务系统的密码，不是 App 的）", true)
            } catch (e: EamsAuthLost) {
                finish(my, "教务会话断了，再点一次试试", true)
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("CrawlScreen", "抓课表失败", e)
                finish(my, StudentError.tech(e) + "（可以再点一次）", true)
            } finally {
                pw = ""          // 用完即清：这个框里的内容不该在这儿多待一秒
                if (my == gen) { step = ""; busy = false }
            }
        }
    }

    Column(
        // 底色必须自己画：这一页在 CampusApp 里是 Scaffold 的**兄弟节点**（整屏盖住，
        // 连底栏一起盖），不画底色就是一层透明薄膜 —— 下层「我的」页的文字会整片透上来，
        // 两页的字叠在一起（2026-09-18 真机踩过，用户截图）。
        Modifier.fillMaxSize().overlaySurface
            .padding(horizontal = 24.dp, vertical = 30.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("← 返回", color = C.txt2, fontSize = 13.sp,
                 modifier = Modifier.clickable { onClose() }.padding(vertical = 6.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("用我的教务账号抓课表", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
             color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))
        Text(
            "登录一次教务系统，手机自己把课表读下来，再交给服务器排你的学习规划。\n"
                + "密码只在这一次登录里用 —— 不保存、不上传，服务器在这条路上全程不碰教务。",
            fontSize = 13.sp, lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = sid, onValueChange = { sid = it },
            label = { Text("学号", fontSize = 13.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = pw, onValueChange = { pw = it },
            label = { Text("教务系统密码", fontSize = 13.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            // 眼睛开关：密码框默认挡着，但得能自己看一眼 —— 输错了却看不见自己输了什么，
            // 是登录失败最主要的来源（学校那边还有账号锁定，错几次更麻烦）
            visualTransformation = if (showPw) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            trailingIcon = {
                Text(
                    if (showPw) "隐藏" else "显示", fontSize = 12.sp, color = C.cyan,
                    modifier = Modifier.clickable { showPw = !showPw }.padding(8.dp),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .background(if (busy) C.card else C.green, RoundedCornerShape(12.dp))
                .clickable(enabled = !busy && sid.isNotBlank() && pw.isNotBlank()) { run() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    busy -> step.ifBlank { "正在处理…" }
                    sid.isBlank() -> "先填学号"
                    pw.isBlank() -> "再填教务密码"
                    else -> "开始抓取"
                },
                color = if (busy || sid.isBlank() || pw.isBlank()) C.txt2 else C.bg,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }

        err?.let {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(C.red.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .border(1.dp, C.red.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) {
                Column {
                    Text(it, color = C.txt, fontSize = 13.sp, lineHeight = 20.sp)
                    // 登录过期这种错误，光说"失败"没用 —— 用户能做的只有重新登录，
                    // 所以按钮就摆在这里（以前要他自己回「我的」页找入口）。
                    if (needRelogin) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "重新登录",
                            color = C.bg,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(C.violet, RoundedCornerShape(10.dp))
                                .clickable { relogin() }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        ok?.let {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(C.green.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .border(1.dp, C.green.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) {
                Column {
                    Text(it, color = C.txt, fontSize = 13.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("回「课表」看看", color = C.green, fontSize = 13.sp,
                         fontWeight = FontWeight.Medium,
                         modifier = Modifier.clickable { onClose() }.padding(vertical = 4.dp))
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        Text(
            "为什么要多这一步：以前密码要存在服务器上才能替你读课表。\n"
                + "现在课表由你手机自己去读，密码就没必要离开手机了 —— 想继续让服务器代读也可以，"
                + "那条路还在（「我的」里的加密保存 + 一键删除）。",
            fontSize = 12.sp, lineHeight = 19.sp, color = C.txt3,
        )
        Spacer(Modifier.height(20.dp))
    }
}
