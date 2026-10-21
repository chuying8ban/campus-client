package top.ccbase.campus.school

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.EamsLocalImport
import top.ccbase.campus.data.remote.RemoteSync
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.domain.activitiesFrom
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.ui.theme.overlaySurface
import top.ccbase.campus.ui.theme.C
import androidx.compose.material3.MaterialTheme
import top.ccbase.campus.net.SchoolImportPolicy

/**
 * 「官方教务登录 → 本地导入 → 可选云端同步」的共享流程。
 *
 * 默认本地：从 WebView 拿到 print-data JSON 后只清洗并导入本机，不发任何云端请求。
 * 云同步只在用户显式点「同意云端同步」之后发生，且已有合法令牌就用它；没有才走访客。
 */
@Composable
fun SchoolImportFlow(
    db: CampusDb,
    api: CampusApi,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // 正式版只支持中国石油大学（北京）克拉玛依校区：固定克校，不再有学校选择/外校登录测试。
    val school = SchoolRegistry.cupk
    // 旧版本写进过 school_choice 的外校标识（aust/ahut/neau/other）一律作废，避免带着它走进正式流程。
    remember { runCatching { ctx.getSharedPreferences("school_choice",Context.MODE_PRIVATE).edit().remove("id").apply() } }
    var attemptMessage by remember { mutableStateOf<String?>(null) }
    val receiveTimetable: (String)->Unit = {raw->scope.launch {
        try {
            val rows=kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonArray
            require(rows.all {e->val o=e as kotlinx.serialization.json.JsonObject;listOf("startTime","endTime").all{k->(o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.matches(Regex("\\d{2}:\\d{2}"))==true}}) { "已识别课程，但缺少学校精确作息时间；尚未保存，原课表未改变" }
            rows.forEach {e->val o=e as kotlinx.serialization.json.JsonObject;val start=java.time.LocalTime.parse((o.getValue("startTime") as kotlinx.serialization.json.JsonPrimitive).content);val end=java.time.LocalTime.parse((o.getValue("endTime") as kotlinx.serialization.json.JsonPrimitive).content);require(start<end){"学校时间不合法，原课表未改变"}}
            val result=withContext(Dispatchers.IO){EamsLocalImport.import(db,raw,true)}
            check(result.imported){"没有可保存课程，原课表未改变"}
            withContext(Dispatchers.Main) {
                top.ccbase.campus.alarm.DailyTimetableCheck.markUpdated(ctx)
                top.ccbase.campus.widget.TodayWidgetProvider.refresh(ctx)
            }
            attemptMessage="已保存 ${result.counts["courses"] ?: 0} 门课。请核对周次、时间和地点。"
        } catch(e:Exception){attemptMessage=e.message ?: "读取失败，原课表未改变"}
    }}
    if(attemptMessage!=null)AlertDialog(onDismissRequest={attemptMessage=null},title={Text("课表读取结果")},text={Text(attemptMessage!!)},confirmButton={TextButton(onClick={attemptMessage=null}){Text("知道了")}})
    var stage by remember { mutableStateOf(Stage.Intro) }
    var err by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<EamsLocalImport.ImportResult?>(null) }
    var cleanedActivities by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var cloudMessage by remember { mutableStateOf<String?>(null) }
    var cloudSynced by remember { mutableStateOf(false) }
    // 2026-10-21 用户口径：课表导好了但没同步云端就点「完成」时，要先问一句。
    var askCloudOnDone by remember { mutableStateOf(false) }
    /** 用户在确认框里选了「同步到云端」→ 同步成功后再真正收尾（否则他会以为没导完）。 */
    var finishAfterSync by remember { mutableStateOf(false) }
    var showCloudTerms by remember { mutableStateOf(false) }
    // 默认开启：用户口径是「登录过一次，以后进内置浏览器就该自动登录」。
    // 只保留**会话**（Cookie），不保存密码；从没选过的人（新装/老版本升级上来）默认按开启处理。
    var rememberLogin by remember {
        mutableStateOf(SchoolSession.remembered(ctx) || !SchoolSession.asked(ctx))
    }
    var confirmRemember by remember { mutableStateOf(false) }
    // 「记住账号密码」：可选，默认关。开了以后学校会话过期也能自动填表登录（见 CredentialVault）。
    var saveCred by remember { mutableStateOf(CredentialVault.has(ctx)) }
    var credDialog by remember { mutableStateOf(false) }
    var credUser by remember { mutableStateOf("") }
    var credPass by remember { mutableStateOf("") }
    var credErr by remember { mutableStateOf<String?>(null) }
    var credToast by remember { mutableStateOf<String?>(null) }
    var forgetNotice by remember { mutableStateOf(false) }
    var keepManual by remember { mutableStateOf(true) }

    androidx.activity.compose.BackHandler { if (!busy) onCancel() }

    fun handleRaw(raw: String) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val cleaned = SchoolRegistry.adapter(school).normalizedActivities(raw)
                    EamsLocalImport.import(db, cleaned, keepManual).let {
                        if (!it.imported) throw IllegalStateException("课表里没有可导入的课程")
                        it to cleaned
                    }
                }
                imported = result.first
                cleanedActivities = result.second
                cloudMessage = null
                // 真的导进来了才算"今天已更新"：顶部横幅与桌面小组件都靠这一下翻状态
                top.ccbase.campus.alarm.DailyTimetableCheck.markUpdated(ctx)
                top.ccbase.campus.widget.TodayWidgetProvider.refresh(ctx)
                stage = Stage.Summary
            } catch (e: Exception) {
                err = e.message ?: "本地导入失败"
                stage = Stage.Intro
            }
        }
    }

    fun consentCloud() {
        if (busy || cloudSynced || cleanedActivities.isBlank()) return
        busy = true
        cloudMessage = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { SchoolCloudSync.uploadConsented(ctx, db, api, cleanedActivities) }
            cloudSynced = r is ApiResult.Ok
            cloudMessage = when (r) {
                is ApiResult.Ok -> r.value
                is ApiResult.Err -> "云端同步没完成：${r.message}"
            }
            busy = false
            if (cloudSynced && finishAfterSync) {
                finishAfterSync = false
                onDone()
            } else if (!cloudSynced) {
                // 没成就不替用户收尾：留在这一页，他还能再看一眼说明或重试
                finishAfterSync = false
            }
        }
    }

    if (confirmRemember) AlertDialog(onDismissRequest={confirmRemember=false},title={Text("在本机记住学校登录？")},text={Text("只保留这台手机的学校登录会话，不保存密码，不上传 Cookie。共用手机不建议开启。学校可随时让会话过期；卸载或清除数据后无法恢复。不会后台频繁访问学校。")},confirmButton={TextButton(onClick={SchoolSession.setRemembered(ctx,true,school);rememberLogin=true;confirmRemember=false}){Text("同意并开启")}},dismissButton={TextButton(onClick={confirmRemember=false}){Text("取消")}})
    // 存/删之后的回音：一句话就够了，别弹第二个对话框
    LaunchedEffect(credToast) {
        val t = credToast ?: return@LaunchedEffect
        android.widget.Toast.makeText(ctx, t, android.widget.Toast.LENGTH_SHORT).show()
        credToast = null
    }
    if (credDialog) AlertDialog(
        onDismissRequest = { credDialog = false; saveCred = CredentialVault.has(ctx) },
        title = { Text("记住账号密码") },
        text = {
            Column {
                Text(
                    "只存在这台手机，用系统密钥库加密，不上传、不参与云同步。" +
                        "共用手机不建议开启；关闭开关即删除。",
                    fontSize = 12.sp, color = C.txt2, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = credUser, onValueChange = { credUser = it },
                    label = { Text("学号 / 用户名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = credPass, onValueChange = { credPass = it },
                    label = { Text("密码") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                credErr?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = C.red, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (credUser.isBlank() || credPass.isEmpty()) {
                    credErr = "账号和密码都要填"
                } else if (CredentialVault.save(ctx, credUser.trim(), credPass)) {
                    saveCred = true
                    credDialog = false
                    credPass = ""
                    credToast = "已保存在本机，下次自动登录"
                } else {
                    credErr = "没存上，稍后再试一次"
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = { credDialog = false; saveCred = CredentialVault.has(ctx) }) { Text("取消") }
        },
    )
    if (forgetNotice) AlertDialog(onDismissRequest={forgetNotice=false},title={Text("已忘记学校登录")},text={Text("本机学校会话已清除，原课表和云端账号未改变。")},confirmButton={TextButton(onClick={forgetNotice=false}){Text("知道了")}})
    when (stage) {
        Stage.Intro -> Column(
            Modifier.fillMaxSize().background(C.bg).clickable(enabled = true, onClick = {}).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 30.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("← 返回", fontSize = 13.sp, color = C.txt3,
                    modifier = Modifier.clickable { onCancel() }.padding(vertical = 6.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("导入官方教务课表", color = C.txt, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                "课表默认只导入到你自己的手机里，不会发给任何服务器。\n"
                    + "登录走学校的融合门户统一身份认证（${SchoolCookieRules.PORTAL_HOST}），"
                    + "App 不读取、不保存你的教务密码。",
                fontSize = 13.sp, lineHeight = 21.sp, color = C.txt2,
            )
            err?.let {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().background(Color(0x1FE5484D), RoundedCornerShape(10.dp)).padding(12.dp),
                ) { Text(it, fontSize = 12.sp, color = Color(0xFFC4454A)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = keepManual, onCheckedChange = { keepManual = it })
                Text("保留本机手动课表\n取消勾选：使用官方课程时段替换；早晚自习保留", color = C.txt2, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked=rememberLogin,onCheckedChange={if(it)confirmRemember=true else {SchoolSession.setRemembered(ctx,false,school);rememberLogin=false;forgetNotice=true}})
                Text("登录一次，以后进入自动登录\n只在本机保留登录会话，默认不保存密码",color=C.txt2,fontSize=13.sp)
            }
            if(rememberLogin) {
                Text("有效会话会复用，过期需重新登录；不保证永久在线。",color=C.txt2,fontSize=12.sp)
                TextButton(onClick={SchoolSession.forget(ctx,school);rememberLogin=false;forgetNotice=true}){Text("忘记登录")}
            }
            // 会话一定会过期（学校那边定的），想"再也不输密码"就得本机留一份凭据 —— 可选，默认关。
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = saveCred,
                    onCheckedChange = { on ->
                        if (on) { credUser = ""; credPass = ""; credErr = null; credDialog = true }
                        else { CredentialVault.clear(ctx); saveCred = false; credToast = "已删掉本机保存的账号密码" }
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text("账号密码也记住（自动登录）", color = C.txt2, fontSize = 13.sp)
                    Text(
                        if (saveCred) "已加密保存在本机；登录页会自动填好并提交"
                        else "不勾选：只保留登录会话，学校那边会话过期就要重新输",
                        color = C.txt3, fontSize = 11.sp, lineHeight = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.fillMaxWidth().background(C.violet, RoundedCornerShape(10.dp))
                    .clickable {
                        // 进官方页之前把"记住登录"落定：WebView 那边要靠它决定
                        // 结束后是保留会话还是清掉，以及要不要把会话改写成持久 cookie。
                        SchoolSession.markAsked(ctx, school)
                        SchoolSession.setRemembered(ctx, rememberLogin, school)
                        stage = Stage.Web
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("打开融合门户登录", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp) }
            Spacer(Modifier.height(16.dp))
            Text(
                "联网后可从「课表 → 更新课表」重新导入；本机内容可离线查看。",
                fontSize = 12.sp, lineHeight = 19.sp, color = C.txt3,
            )
            Spacer(Modifier.height(30.dp))
        }

        Stage.Web -> SchoolWebLogin(
            profile = school,
            onTimetableJson = ::handleRaw,
            onWebError = {
                err = it
                stage = Stage.Intro
            },
            onCancel = onCancel,
        )

        Stage.Summary -> Column(
            Modifier.fillMaxSize().background(C.bg).clickable(enabled = true, onClick = {}).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 30.dp),
        ) {
            Text(if (cloudSynced) "已同步到云端" else "已导入本机", color = C.txt, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            val i = imported
            Text(
                "读到 ${i?.counts?.get("courses") ?: 0} 门课 · ${i?.counts?.get("slots") ?: 0} 个上课时段。" +
                    "已有收藏和活动记录没有被改动。",
                fontSize = 13.sp, lineHeight = 21.sp, color = C.txt2,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().background(C.violet, RoundedCornerShape(10.dp))
                    .clickable { if (cloudSynced) onDone() else askCloudOnDone = true }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("完成", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp) }
            Spacer(Modifier.height(12.dp))
            Text(
                "可选：阅读并同意条款后才上传课表用于云端同步。已有账号会用现有账号；没有账号会新建一个随机访客账号，" +
                    "访客账号约 30 天过期，卸载/重装后无法找回（本机不会把它当学校学号信任）。",
                fontSize = 12.sp, lineHeight = 19.sp, color = C.txt2,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().background(C.card, RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy && !cloudSynced && !top.ccbase.campus.BuildConfig.API_BASE.contains("example.invalid")) { showCloudTerms = true }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("正在同步…", fontSize = 13.sp, color = C.txt2)
                    }
                } else {
                    Text(if (cloudSynced) "已同步，无需重复上传" else if (top.ccbase.campus.BuildConfig.API_BASE.contains("example.invalid")) "此测试包未连接云端（仅本机导入）" else "同意并同步到云端（可选）", fontSize = 14.sp, color = C.violet)
                }
            }
            cloudMessage?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, fontSize = 12.sp, lineHeight = 19.sp, color = C.violet)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (askCloudOnDone) FinishAskCloudDialog(
        onLocalOnly = { askCloudOnDone = false; onDone() },
        onSyncCloud = { askCloudOnDone = false; finishAfterSync = true; showCloudTerms = true },
        onCancel = { askCloudOnDone = false },
    )
    if (showCloudTerms) CloudConsentDialog(
        onCancel = { showCloudTerms = false },
        onAgree = { showCloudTerms = false; consentCloud() },
    )
}

/**
 * 「课表导好了，但还没同步云端就点了完成」时的确认框。
 *
 * 用户口径（2026-10-21）：「如果用户未同步到云端就点击完成，我希望 app 会出现一个确认框，
 * 询问用户是否不同步到云端，给用户两个选项，一个是不同步，一个是同步到云端，
 * 点哪个就按哪个方式导课表」。
 *
 * 关键点：**两个选项都是"完成"**，不是"取消/去同步"。选了同步也不是把人扔到设置页，
 * 而是走完同意 → 上传 → 成功后自动收尾（见 finishAfterSync）。
 */
@Composable
internal fun FinishAskCloudDialog(
    onLocalOnly: () -> Unit,
    onSyncCloud: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("还没同步到云端") },
        text = {
            Text(
                "课表已经存到这台手机了。要不要也同步到云端？\n" +
                    "同步后换手机、重装 App 都能找回来；不同步则只在这台手机上可用。",
                fontSize = 13.sp, lineHeight = 20.sp,
            )
        },
        confirmButton = { TextButton(onClick = onSyncCloud) { Text("同步到云端") } },
        dismissButton = { TextButton(onClick = onLocalOnly) { Text("不同步，仅本机用") } },
    )
}

@Composable
internal fun CloudConsentDialog(onCancel: () -> Unit, onAgree: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("云端同步说明与同意") },
        text = { Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
            Text(ImportPrivacy.TERMS)
        } },
        confirmButton = { TextButton(onClick = onAgree) { Text("同意并同步") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("不同意，仅本机使用") } },
    )
}

private enum class Stage { Intro, Web, Summary }

/**
 * 显式同意后的云端同步。只上传白名单后的 activities，绝不把学号/密码/ Cookie 发过去。
 */
object SchoolCloudSync {
    data class Consent(val token: String, val guest: Boolean, val recovery: String)

    suspend fun uploadConsented(
        ctx: Context,
        db: CampusDb,
        api: CampusApi,
        activitiesJson: String,
    ): ApiResult<String> {
        val consent = obtainToken(ctx, api)
        if (consent is ApiResult.Err) return consent
        val ok = (consent as ApiResult.Ok).value

        var token = ok.token
        var upload = api.uploadActivities(token, activitiesJson)
        if (upload is ApiResult.Err && upload.code == 401 && !ok.guest) {
            // 已有令牌失效：**绝不**自动换成随机访客再传。
            // 那会让用户以为"还是原来那个账号"，其实数据落在另一个陌生身份下、卸载即丢。
            // 这里如实报错，让用户自己决定重新登录，或明说"新建访客号"再重来一次。
            return ApiResult.Err(
                401,
                "原来的登录已过期，课表没有上传。请重新登录后再同步；" +
                    "如果直接新建随机访客账号，那是一个新身份、不是原账号，卸载后无法找回。",
            )
        }
        if (upload is ApiResult.Err) return upload

        val refreshed = RemoteSync.sync(ctx, db, api, token, allowLocalOverride = true)
        val guestNote = if (ok.guest) "（随机访客账号，非学校学号，卸载后无法找回）" else ""
        val suffix = if (ok.recovery.isNotBlank()) "\n${ok.recovery}" else ""
        return when (refreshed) {
            is ApiResult.Ok -> ApiResult.Ok(
                "已同步到云端：本机现在是 ${refreshed.value.counts["courses"] ?: 0} 门课 · " +
                    "${refreshed.value.counts["slots"] ?: 0} 个上课时段$guestNote$suffix",
            )
            is ApiResult.Err -> ApiResult.Err(refreshed.code, refreshed.message)
        }
    }

    private suspend fun obtainToken(ctx: Context, api: CampusApi): ApiResult<Consent> {
        val existing = withContext(Dispatchers.IO) { TokenStore.token(ctx) }
        if (!existing.isNullOrBlank()) {
            if (TokenStore.expired(ctx)) return ApiResult.Err(401,
                "原云端账号已过期，课表仍保存在本机；本次没有上传，也不会自动创建新账号。")
            return ApiResult.Ok(Consent(existing, false, ""))
        }
        return when (val g = api.guest()) {
            is ApiResult.Err -> g
            is ApiResult.Ok -> {
                withContext(Dispatchers.IO) {
                    TokenStore.save(ctx, g.value.token, g.value.expiresAt, g.value.user)
                }
                ApiResult.Ok(Consent(g.value.token, true, g.value.recovery))
            }
        }
    }
}
