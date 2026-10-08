package top.ccbase.campus.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.StudentError
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.data.remote.RemoteSync
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits

/**
 * 刷新条 —— 放在需要"从服务器重新取一次"的页面顶部。
 *
 * 三条设计底线：
 *  ① 说出来在做什么：常态显示上次更新时间，点下去显示"正在刷新…"，**不是**转个圈圈了事
 *  ② 成功要带真实数字（N 门课 / N 项任务），失败要说卡在哪 —— 见 RefreshLogic
 *  ③ 刷新中不可再点（防止连点打出一串请求）
 *
 * [sync] 可注入：测试要能在不联网的情况下证明"点一下真的会做事、并把结果说出来"。
 */
@Composable
fun RefreshBar(
    db: CampusDb,
    modifier: Modifier = Modifier,
    sync: (suspend () -> ApiResult<PlanApplier.Result>)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var at by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var loggedIn by remember { mutableStateOf(TokenStore.token(ctx) != null) }

    suspend fun reloadAt() {
        at = withContext(Dispatchers.IO) {
            runCatching { db.dao().metaGet(PlanApplier.K_AT) }.getOrNull()
        }
    }

    LaunchedEffect(Unit) { reloadAt() }

    // 没登录就不提供刷新（点了也只会失败，不如不给假入口）
    if (!loggedIn) return

    val run: suspend () -> ApiResult<PlanApplier.Result> = sync ?: {
        val t = TokenStore.token(ctx)
        if (t == null) ApiResult.Err(401, StudentError.SESSION) else RemoteSync.sync(ctx, db, CampusApi(), t)
    }

    fun fire() {
        if (busy) return
        busy = true; done = null; failed = null
        scope.launch {
            when (val r = run()) {
                is ApiResult.Ok -> {
                    val c = r.value.counts
                    done = RefreshLogic.doneText(
                        courses = c["courses"] ?: 0,
                        tasks = c["tasks"] ?: 0,
                        at = null,
                    )
                }
                is ApiResult.Err -> failed = RefreshLogic.failText(r.message)
            }
            reloadAt()
            busy = false
        }
    }

    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(C.card)
                .clickable(enabled = !busy) { fire() }
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    busy -> "正在刷新…"
                    failed != null -> failed!!
                    done != null -> done!!
                    else -> RefreshLogic.summary(at)
                },
                color = when {
                    busy -> C.txt3
                    failed != null -> C.amber
                    done != null -> C.green
                    else -> C.txt3
                },
                fontSize = 11.sp,
                style = Digits,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(9.dp))
            // 「刷新」以前只是一串青色小字，看着像说明文字、不像能点的按钮
            // （用户反馈「上面的按钮看不清」）。改成实心药丸按钮：有底色、有边界、字够亮。
            Text(
                when {
                    busy -> "…"
                    failed != null -> "重试"
                    else -> "刷新"
                },
                color = if (busy) C.txt3 else C.bg,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (busy) C.line else C.cyan)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        if (failed != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                // 说清这次刷的是哪来的数据，避免"以为刷了教务系统"
                "刷新只取已保存的云端内容；教务课表变化需重新导入",
                color = C.txt3, fontSize = 10.sp,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}
