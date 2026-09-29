package top.ccbase.campus.ui.onboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.OnboardState

/**
 * 第一次登录之后的等待页。
 *
 * 为什么必须有这一页：同学登录完之后，服务端要拿**他自己的**账号去登录教务系统、
 * 把课表读回来、再按他的课排任务 —— 这中间要几秒到几十秒。没有这一页，他就是
 * 盯着一个"登录成功了但课表是别人的/是空的"的界面，然后一次次退出重进。
 *
 * 三条设计立场：
 *  1. **进度只信服务端**（`OnboardState`），客户端不推算百分比。
 *  2. **可以退出**。后台在跑，退出不影响；回来还能接着看。所以这页要明说这句话，
 *     并且 `OnboardingFlow` 里要真的兑现（重进能回到这一屏）。
 *  3. **失败要说人话、给两条路**：重试，或者"先用模板进去看看"—— 绝不把人堵死在这一屏。
 *
 * 渲染（`WaitingBody`）和轮询（`WaitingScreen`）是分开的：无状态的那半可以直接渲染断言，
 * 不必和计时的协程较劲。
 */
object OnboardLogic {

    /** 服务端还在干活。 */
    fun busy(s: OnboardState): Boolean = s.state == "pending" || s.state == "running"

    /** 生成好了（或这个人根本不需要生成，比如老用户）。**none 必须算 settled** ——
     *  否则老用户一登录就被堵在等待页上等一件永远不会发生的事。 */
    fun settled(s: OnboardState): Boolean = s.state == "done" || s.state == "none"

    fun headline(s: OnboardState): String = when (s.state) {
        "none", "pending" -> "正在排队，马上开始"
        "running" -> s.stepLabel.ifBlank { "正在生成你的规划" }
        "done" -> "好了，进去看看"
        "failed" -> "这一步没成功"
        else -> "正在生成你的规划"
    }

    /** 一句话说明"正在发生什么"。用同学自己的账号这件事要讲清楚。 */
    fun explain(s: OnboardState): String = when (s.state) {
        "failed" -> s.error.ifBlank { "服务端没有给出原因，可以直接重试" }
        "done" -> "已经按你自己的课表排好了"
        else -> "用的是你自己的学号和教务密码：登录教务系统 → 读你的课表 → 生成你的规划"
    }

    /** 慢了才提示"不太正常"（正常 1~2 分钟；超过 90 秒就该给个出口了）。 */
    fun showSlowHint(secs: Int): Boolean = secs >= 90

    /** 失败：重试是主按钮，"先跳过"是次要出口 —— 不能把人堵死在这。 */
    fun showSkip(s: OnboardState): Boolean = s.state == "failed"

    /** 步骤行（文案 / 状态）。服务端没给步骤就不显示 —— 宁可空着也不画假进度。 */
    fun steps(s: OnboardState): List<Pair<String, String>> =
        s.steps.map { it.label to it.state }

    /** 轮询间隔：前 1 分钟 1.5 秒一次（同学正盯着看，得跟手），之后放宽到 5 秒
     *  （等了几分钟还密集地问就是白烧电量和服务器）。 */
    fun pollMs(secs: Int): Long = if (secs < 60) 1500 else 5000

    fun percentText(s: OnboardState): String = "${s.percent}%"
}

@Composable
private fun StepRow(label: String, state: String) {
    val on = state == "done"
    val doing = state == "doing"
    val fail = state == "fail"
    val tint = when {
        fail -> MaterialTheme.colorScheme.error
        on || doing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(7.dp)
                .background(tint.copy(alpha = if (on || doing || fail) 0.9f else 0.5f),
                            RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(
                alpha = if (on || doing || fail) 0.9f else 0.42f),
            fontWeight = if (doing) FontWeight.Medium else FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        Text(
            when {
                fail -> "没成功"
                on -> "完成"
                doing -> "进行中"
                else -> "等待"
            },
            fontSize = 12.sp, color = tint,
        )
    }
}

/** 无状态的那一半：给什么状态就画什么。 */
@Composable
fun WaitingBody(
    st: OnboardState,
    secs: Int = 0,
    retrying: Boolean = false,
    onRetry: () -> Unit = {},
    onSkip: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 36.dp)) {
        StepDots(1)
        Text(
            OnboardLogic.headline(st),
            fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            OnboardLogic.explain(st),
            fontSize = 13.sp, lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(
                alpha = if (st.state == "failed") 0.85f else 0.6f),
        )
        Spacer(Modifier.height(24.dp))

        if (OnboardLogic.steps(st).isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                OnboardLogic.steps(st).forEach { (label, s) -> StepRow(label, s) }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已完成 ${OnboardLogic.percentText(st)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "已等 $secs 秒",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        // 「可以退出」这句话必须写在明面上：不写，同学就会守在这里不动，
        // 或者以为退出就白跑了（其实是服务端在跑，跟他退不退没关系）。
        Box(
            Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                            RoundedCornerShape(10.dp))
                .padding(14.dp),
        ) {
            Text(
                "这期间可以退出 App，服务端会继续跑；\n回来重新打开就能看到进度。第一次大约 1~2 分钟。",
                fontSize = 12.sp, lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }

        Spacer(Modifier.weight(1f))

        if (OnboardLogic.showSkip(st)) {
            PrimaryButton("重试", enabled = !retrying, onClick = onRetry)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextLink("先跳过，用模板进去看看", onSkip)
            }
        } else {
            Text(
                if (OnboardLogic.showSlowHint(secs)) "比平时慢了一点，再等一会儿；一直不动可以退出去重进"
                else "请稍等…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            )
            if (OnboardLogic.showSlowHint(secs)) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextLink("先跳过，用模板进去看看", onSkip)
                }
            }
        }
    }
}

/**
 * 轮询服务端状态；生成好了就把规划拉下来落到本地，再 onDone。
 *
 * 离开这一屏（协程被取消 = 用户退出了）**不影响**服务端 —— 它本来就在后台跑。
 * 判失败后停止轮询：这一屏有「重试」，点了会把轮询重新拉起来（LaunchedEffect 的 key 里
 * 有 retrying）。一直空转只会白烧电量，还让同学以为进度条自己在动。
 */
@Composable
fun WaitingScreen(
    api: CampusApi,
    db: CampusDb,
    token: String,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    var st by remember { mutableStateOf(OnboardState(stepLabel = "正在连接服务器")) }
    var secs by remember { mutableStateOf(0) }
    var retrying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** 拉一次规划落到本地。**失败也不堵人**（进去还能下拉刷新）。 */
    suspend fun pullPlan() {
        val p = api.plan(token)
        if (p is ApiResult.Ok) withContext(Dispatchers.IO) { PlanApplier.apply(db, p.value) }
    }

    LaunchedEffect(token, retrying) {
        while (true) {
            when (val r = api.onboard(token)) {
                is ApiResult.Ok -> {
                    st = r.value
                    if (OnboardLogic.settled(r.value)) {
                        pullPlan()
                        onDone()
                        break
                    }
                }
                // 网络抖动不算失败：下一轮接着问。但要把原因显示出来 ——
                // 只转圈的话，同学不知道是自己断网了。
                is ApiResult.Err ->
                    st = st.copy(state = if (st.state == "none") "pending" else st.state,
                                 error = r.message)
            }
            if (st.state == "failed") break
            delay(OnboardLogic.pollMs(secs))
            secs += 2
        }
    }

    WaitingBody(
        st = st, secs = secs, retrying = retrying,
        onRetry = {
            retrying = true
            scope.launch {
                api.onboardRestart(token, force = true)
                retrying = false
            }
        },
        onSkip = {
            // "先跳过"也要把（模板）规划拉到本地再进去，不然进去是一张空课表
            scope.launch {
                pullPlan()
                onSkip()
            }
        },
    )
}
