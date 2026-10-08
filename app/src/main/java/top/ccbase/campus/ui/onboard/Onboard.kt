package top.ccbase.campus.ui.onboard

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.plan.Modules
import top.ccbase.campus.data.plan.Templates
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.StudentError

/**
 * 首次进入的四屏。
 *
 * 设计立场：**这个 App 不是给作者用的，是给不认识作者的同学用的。**
 * 所以每一屏只问一件事、每件事都写清"为什么"、每一步都能退出去。
 * 尤其是登录屏：用户第一反应是"我凭什么把教务密码给你"——
 * 必须当场回答（只用于登录验证、加密保存、随时可删），而不是藏在小字里。
 */

private val STEP_LABELS = listOf("连教务", "认课表", "挑想学的")

@Composable
internal fun StepDots(current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        STEP_LABELS.forEachIndexed { i, label ->
            val on = i <= current
            Text(
                "${i + 1} $label",
                fontSize = 12.sp,
                color = if (on) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f),
            )
            if (i < STEP_LABELS.size - 1) {
                Box(Modifier.width(18.dp).height(1.dp).background(
                    MaterialTheme.colorScheme.onBackground.copy(alpha = if (on) 0.35f else 0.12f)))
            }
        }
    }
}

/** 主按钮：不要两根等宽大横条，次要动作一律用文字链 */
@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .border(1.dp, bg.copy(alpha = if (enabled) 0.55f else 0.2f), RoundedCornerShape(10.dp))
            .background(bg.copy(alpha = if (enabled) 0.14f else 0.05f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = bg.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        modifier = Modifier.clickable { onClick() }.padding(vertical = 14.dp),
    )
}

// ---------------------------------------------------------------- 1. 欢迎

@Composable
fun WelcomeScreen(onStart: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("校园", fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
             color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))
        Text(
            "把课表、作业、自习和该学的技能放在一个地方。\n课表从你自己的教务系统读，不共享、不公开。",
            fontSize = 14.sp, lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
        )
        Spacer(Modifier.height(44.dp))
        PrimaryButton("去官方教务登录，导入我的课表") { onStart() }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextLink("先离线进入（课表以后在「我的」导入）") { onSkip() }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "中国石油大学（北京）克拉玛依校区学生自用工具 · 非学校官方产品",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
        )
    }
}

// ---------------------------------------------------------------- 2. 登录（本地优先，官方 WebView）

@Composable
fun LoginScreen(
    ctx: Context,
    api: CampusApi,
    db: CampusDb,
    onDone: () -> Unit,
    /** 本地导入流程不再走服务端等待页；保留签名兼容旧调用点。 */
    onWait: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    top.ccbase.campus.school.SchoolImportFlow(
        db = db,
        api = api,
        onDone = onDone,
        onCancel = onBack,
    )
}

// ---------------------------------------------------------------- 3. 认课表

@Composable
fun TimetableScreen(db: CampusDb, onNext: () -> Unit) {
    var courses by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var slots by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 必须切到 IO：主线程读 Room 会抛异常（界面会永远停在"正在读取…"）
        val r = withContext(Dispatchers.IO) {
            db.dao().courses().first().map { it.id to it.name } to db.dao().slots().first().size
        }
        courses = r.first
        slots = r.second
        loaded = true
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 36.dp)) {
        StepDots(1)
        Text("这是你的课表吗？", fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
             color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            if (loaded) "读到 ${courses.size} 门课 · $slots 个上课时段" else "正在读取…",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(24.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(courses) { (_, name) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(2.dp).height(16.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(12.dp))
                    Text(name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
                }
            }
        }

        Text(
            "不对的话，请在「我的」里重新导入。课表有变化时也需重新导入。",
            fontSize = 12.sp, lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(if (courses.isEmpty()) "没读到课表，先继续" else "对的，继续") { onNext() }
    }
}

// ---------------------------------------------------------------- 4. 挑想学的

@Composable
fun ModulePickerScreen(tpl: Templates, db: CampusDb, onDone: () -> Unit) {
    val picked = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 36.dp)) {
        StepDots(2)
        Text("想学点什么？", fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
             color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "课表里的课已经自动配好了。下面这些是课外的技能包，挑你想要的加进去，\n一个都不选也行 —— 以后随时能加。",
            fontSize = 13.sp, lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(20.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(tpl.modules) { m ->
                val on = m.id in picked
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { if (on) picked.remove(m.id) else picked.add(m.id) }
                        .padding(vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(14.dp)
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (on) 0.9f else 0.35f),
                                        RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (on) 0.9f else 0f),
                                            RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(m.name, fontSize = 15.sp,
                             color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (on) 1f else 0.8f))
                    }
                    m.desc?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("　　$it", fontSize = 12.sp, lineHeight = 19.sp,
                             color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (picked.isEmpty()) "还没挑，也可以就这样开始" else "已挑 ${picked.size} 个",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (picked.isEmpty()) "就这样开始" else "加上这些，开始", enabled = !busy) {
            busy = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    if (picked.isNotEmpty()) Modules.apply(db, tpl, picked.toList())
                    PlanApplier.markOnboarded(db)
                }
                busy = false
                onDone()
            }
        }
    }
}

// ---------------------------------------------------------------- 门闸

private enum class Step { Welcome, Login, Timetable, Modules }

/** 首屏读取期间的样子 —— 不能是空白页，否则用户以为 App 坏了 */
@Composable
fun BootScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(14.dp))
            Text("正在准备…", fontSize = 12.sp,
                 color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
        }
    }
}

/**
 * 首次进入的四屏引导。
 *
 * 「跳过」只用在**内层**引导步骤：不想连教务、不想挑模块，都可以跳过。
 * 但**登录本身不能跳过** —— 未登录时只显示登录页，不允许预览内容。
 */
@Composable
fun OnboardingFlow(
    ctx: Context,
    db: CampusDb,
    tpl: Templates,
    onFinish: () -> Unit,
) {
    var step by remember { mutableStateOf(Step.Welcome) }
    val scope = rememberCoroutineScope()
    val api = remember { CampusApi() }

    fun finish() {
        scope.launch {
            withContext(Dispatchers.IO) { PlanApplier.markOnboarded(db) }
            onFinish()
        }
    }

    when (step) {
        Step.Welcome -> WelcomeScreen(
            onStart = { step = Step.Login },
            onSkip = { finish() },
        )
        Step.Login -> LoginScreen(
            ctx = ctx,
            api = api,
            db = db,
            onDone = { step = Step.Timetable },
            onWait = {},
            onBack = { step = Step.Welcome },
        )
        Step.Timetable -> TimetableScreen(db = db, onNext = { step = Step.Modules })
        Step.Modules -> ModulePickerScreen(tpl = tpl, db = db, onDone = { finish() })
    }
}
