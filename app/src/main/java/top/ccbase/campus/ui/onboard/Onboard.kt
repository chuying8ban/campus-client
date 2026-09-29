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
        PrimaryButton("用学号登录，读我的课表") { onStart() }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "中国石油大学（北京）克拉玛依校区学生自用工具 · 非学校官方产品",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
        )
    }
}

// ---------------------------------------------------------------- 2. 登录

@Composable
fun LoginScreen(
    ctx: Context,
    api: CampusApi,
    db: CampusDb,
    onDone: () -> Unit,
    /** 服务端要花时间生成时才走这条路（带上令牌）。老用户/已生成好不会触发。 */
    onWait: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    var sid by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showPw by remember { mutableStateOf(false) }
    // 刚敲下的那个字符短暂可见（0.9 秒后自己隐去）—— 企业登录框的常见做法：
    // 既能核对刚输入的内容，又不会把整个密码留在屏幕上。
    // 每次输入都会重启计时（LaunchedEffect 的 key 是 pw）。
    var revealLast by remember { mutableStateOf(false) }
    LaunchedEffect(pw) {
        if (pw.isNotEmpty()) {
            revealLast = true
            delay(900)
            revealLast = false
        }
    }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy || sid.isBlank() || pw.isBlank()) return
        busy = true; err = null
        scope.launch {
            when (val r = api.login(sid.trim(), pw)) {
                is ApiResult.Ok -> {
                    TokenStore.save(ctx = ctx, token = r.value.token,
                        expiresAt = r.value.expiresAt, user = r.value.user)
                    // 登录成功之后，服务端要**用他自己的账号**去教务读课表、排计划，
                    // 这要几秒到几十秒。先问一句状态：
                    //   · 还在生成 → 去等待页（把"正在发生什么"讲清楚，而不是让他看着
                    //     一张别人的/空的课表）
                    //   · 老用户、或者已经生成好了 → 照旧直接进去（这些人一秒都不用多等）
                    val ob = api.onboard(r.value.token)
                    if (ob is ApiResult.Ok && !OnboardLogic.settled(ob.value)) {
                        pw = ""      // 密码用完立刻从内存里丢掉，不留在界面状态里
                        onWait(r.value.token)
                        busy = false
                        return@launch
                    }
                    when (val p = api.plan(r.value.token)) {
                        is ApiResult.Ok -> withContext(Dispatchers.IO) {
                            PlanApplier.apply(db, p.value)
                        }
                        // 登录成功了但课表没拿到（比如服务端刚重启）：
                        // 绝不能把人卡在这一屏 —— 让他先进去，课表稍后会补上
                        is ApiResult.Err -> Unit
                    }
                    pw = ""      // 密码用完立刻从内存里丢掉，不留在界面状态里
                    onDone()
                }
                is ApiResult.Err -> err = r.message
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 36.dp).verticalScroll(rememberScrollState()),
    ) {
        StepDots(0)
        Text("连接教务系统", fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
             color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "用你的学号和教务系统密码登录一次。密码只用来登录教务系统验证身份\n"
                + "输入时不显示明文；密码 AES-GCM 加密保存在服务器上，仅用于代你登录教务，"
                + "在「我的」→「安全与隐私」看细则，或随时一键删除。",
            fontSize = 13.sp, lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = sid, onValueChange = { sid = it },
            label = { Text("学号", fontSize = 13.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = pw, onValueChange = { pw = it },
            label = { Text("教务系统密码", fontSize = 13.sp) },
            singleLine = true,
            // 眼睛开关：密码框默认挡着，但必须能自己看一眼 ——
            // 输错密码又看不见自己输了什么，是登录失败最主要的来源之一
            visualTransformation = when {
                showPw -> VisualTransformation.None            // 眼睛开关：一直明文
                revealLast -> LastCharVisible()                // 刚敲的字符闪一下
                else -> PasswordVisualTransformation()
            },
            trailingIcon = {
                Text(
                    if (showPw) "隐藏" else "显示",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.clickable { showPw = !showPw }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        err?.let {
            Spacer(Modifier.height(14.dp))
            Text(StudentError.screenText(it), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(28.dp))
        if (busy) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("正在登录教务系统读取课表…", fontSize = 12.sp,
                         color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                }
            }
        } else {
            PrimaryButton("登录并读取课表", enabled = sid.isNotBlank() && pw.isNotBlank()) { submit() }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextLink("返回") { onBack() }
            }
        }
    }
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
            "不对的话，先在「我的」里退出登录再来一次。课表每天会自动更新。",
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

private enum class Step { Welcome, Login, Waiting, Timetable, Modules }

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
    var waitToken by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val api = remember { CampusApi() }

    // 上次没等到生成完就退出去了？重进时直接回到等待页接着看进度。
    // 界面上那句"退出 App 也没关系，回来还能看到进度"必须由这里兑现
    // —— 否则就是骗人：答应了不看护着，比不答应更糟。
    LaunchedEffect(Unit) {
        val t = TokenStore.token(ctx) ?: return@LaunchedEffect
        val ob = api.onboard(t)
        if (ob is ApiResult.Ok && !OnboardLogic.settled(ob.value)) {
            waitToken = t
            step = Step.Waiting
        }
    }

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
            onWait = { t -> waitToken = t; step = Step.Waiting },
            onBack = { step = Step.Welcome },
        )
        Step.Waiting -> WaitingScreen(
            api = api,
            db = db,
            token = waitToken,
            // 生成好了进去看的是**他自己的**课表；失败时点"先跳过"进去看的是模板
            onDone = { step = Step.Timetable },
            onSkip = { step = Step.Timetable },
        )
        Step.Timetable -> TimetableScreen(db = db, onNext = { step = Step.Modules })
        Step.Modules -> ModulePickerScreen(tpl = tpl, db = db, onDone = { finish() })
    }
}


/**
 * 只把**最后一个字符**露出来，其余打点。
 *
 * 企业登录框的常见做法：刚敲下的那个字符短暂可见（约 0.9 秒），
 * 之后自己隐去 —— 既能核对刚输入的内容，又不会把整个密码留在屏幕上。
 * 长度不变，所以 OffsetMapping 用 Identity 即可（光标位置不会错位）。
 */
private class LastCharVisible : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        if (s.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(
            AnnotatedString("\u2022".repeat(s.length - 1) + s.last()),
            OffsetMapping.Identity,
        )
    }
}
