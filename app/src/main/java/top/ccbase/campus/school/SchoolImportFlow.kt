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
    var stage by remember { mutableStateOf(Stage.Intro) }
    var err by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<EamsLocalImport.ImportResult?>(null) }
    var cleanedActivities by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var cloudMessage by remember { mutableStateOf<String?>(null) }
    var cloudSynced by remember { mutableStateOf(false) }
    var showCloudTerms by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler { if (!busy) onCancel() }

    fun handleRaw(raw: String) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val acts = activitiesFrom(raw)
                        ?: throw IllegalStateException("课表页里没有 activities")
                    val cleaned = SchoolImportPolicy.activityAllowlist(acts.toString())
                    EamsLocalImport.import(db, cleaned).let {
                        if (!it.imported) throw IllegalStateException("课表里没有可导入的课程")
                        it to cleaned
                    }
                }
                imported = result.first
                cleanedActivities = result.second
                cloudMessage = null
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
        }
    }

    when (stage) {
        Stage.Intro -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 30.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("← 返回", fontSize = 13.sp, color = C.txt3,
                    modifier = Modifier.clickable { onCancel() }.padding(vertical = 6.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("导入官方教务课表", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                "课表默认只导入到你自己的手机里，不会发给任何服务器。\n"
                    + "登录发生在学校官方页面 https://eams.cupk.edu.cn；App 不读取、不保存你的教务密码。",
                fontSize = 13.sp, lineHeight = 21.sp, color = C.txt2,
            )
            err?.let {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().background(Color(0x1FE5484D), RoundedCornerShape(10.dp)).padding(12.dp),
                ) { Text(it, fontSize = 12.sp, color = Color(0xFFC4454A)) }
            }
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.fillMaxWidth().background(C.violet, RoundedCornerShape(10.dp))
                    .clickable { stage = Stage.Web }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("打开官方教务登录", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp) }
            Spacer(Modifier.height(16.dp))
            Text(
                "没有校园网也能先离线进入；之后可以随时在「我的」里导入。",
                fontSize = 12.sp, lineHeight = 19.sp, color = C.txt3,
            )
            Spacer(Modifier.height(30.dp))
        }

        Stage.Web -> SchoolWebLogin(
            onTimetableJson = ::handleRaw,
            onWebError = {
                err = it
                stage = Stage.Intro
            },
            onCancel = onCancel,
        )

        Stage.Summary -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 30.dp),
        ) {
            Text(if (cloudSynced) "已同步到云端" else "已导入本机", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            val i = imported
            Text(
                "读到 ${i?.counts?.get("courses") ?: 0} 门课 · ${i?.counts?.get("slots") ?: 0} 个上课时段。" +
                    "你的任务、打卡和专注记录没有被改动。",
                fontSize = 13.sp, lineHeight = 21.sp, color = C.txt2,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().background(C.violet, RoundedCornerShape(10.dp))
                    .clickable { onDone() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (cloudSynced) "完成" else "完成，仅保存在本机", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp) }
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
    if (showCloudTerms) CloudConsentDialog(
        onCancel = { showCloudTerms = false },
        onAgree = { showCloudTerms = false; consentCloud() },
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
                    "${refreshed.value.counts["tasks"] ?: 0} 项任务$guestNote$suffix",
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
