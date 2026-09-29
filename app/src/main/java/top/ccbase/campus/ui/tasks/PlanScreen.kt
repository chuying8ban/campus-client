package top.ccbase.campus.ui.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.remote.RemoteSync
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.StudentError
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.PlanCounts
import top.ccbase.campus.net.PlanRes
import top.ccbase.campus.net.PlanSuggest
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.util.Links

/**
 * 「AI 规划学习计划」。
 *
 * ## 这一页的规矩（对应服务端三条接口）
 * 1. **先看后加**：`/plan/suggest` 只出建议、不写库 —— 所以"生成—看看—算了"是零副作用的，
 *    用户不会因为点了两下就多出一堆任务。要落库必须再点一次「加入任务清单」，
 *    而且**只加勾选的那几条**。
 * 2. **每条都要说清为什么**：服务端专门算了 `why` 一起回来，界面上就必须逐条写出来。
 *    只给一个任务名，用户没法判断该不该信它。`why` 为空时照实说"没给理由"，不自己编一句。
 * 3. **这次建议是怎么来的要照实讲**：来源可能是模型、可能是缓存、也可能是模型挂了之后的
 *    保守兜底（服务端 source ∈ ai/cache/fallback）。一律标成"AI 推荐"就是骗人。
 * 4. **加入之后本机要跟着变**：服务端落库只改服务端；本地任务清单还停在旧样子。
 *    所以加完立刻 `RemoteSync.sync` 拉一次（和「手机自己抓课表」同一条路）。
 *    刷新失败也**不谎报成功**：如实说"服务器上有了、本机没刷新到"，并给一个重试入口。
 * 5. **弹层一律用普通 Composable 盖一层**，不用 Dialog —— Robolectric 看不见 Dialog 窗口，
 *    测试里就成了"点了没反应"，这类坑本项目踩过。
 * 6. **真正有用的东西要在挑的时候就看得见**：每条建议服务端都挂了视频/课程外链（`resources`），
 *    必须**在建议卡片里**就列出来、点一下直接开 —— 等"加入清单"之后回学习页里翻就太晚了
 *    （用户原话：同学用时「大多数是没有用的笔记，却很少有有用的视频课程」）。
 *    老服务端/老缓存没有这个字段时整段不出现，不给"无资源"这类占位。
 *
 * 文案不许吹：页头只说明"按你的课表和偏好推、不保证效果"，不承诺任何分数或效果。
 */
@Composable
fun PlanScreen(db: CampusDb, api: CampusApi, onClose: () -> Unit, onGoTasks: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 四项喜好（不存盘：每次进来重新填，免得"上次乱填的"一直黏着）----
    var focus by remember { mutableStateOf(emptySet<String>()) }
    var hours by remember { mutableStateOf(PlanLogic.HOURS[1]) }
    var style by remember { mutableStateOf(emptySet<String>()) }

    // ---- 新增分组。全都允许"没填"（空集 / 0 / 空串）：没填就不发，服务端那边等于没这项 ----
    var goals by remember { mutableStateOf(emptySet<String>()) }
    var periods by remember { mutableStateOf(emptySet<String>()) }
    var days by remember { mutableStateOf(0) }
    var pace by remember { mutableStateOf("") }
    var avoid by remember { mutableStateOf("") }

    /**
     * 逐门课自评：**只装用户真的点过的课**（没点 = 不参与）。
     *
     * 默认不选而不是默认 3 档：默认 3 会把十几门课的"中间档"当成用户的意思发给模型，
     * 那是噪音 —— 用户要的是「只填在意的」。用 LinkedHashMap 保持课表顺序，
     * 请求体才不会因为哈希顺序飘。
     */
    var scores by remember { mutableStateOf(linkedMapOf<String, Int>()) }

    /** 本地课表的课名：逐门课这一组**跟着课表走**，绝不写死课程名 */
    var courses by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(Unit) {
        courses = try {
            withContext(Dispatchers.IO) { db.dao().courses().first() }
                .let { rows -> PlanLogic.subjectNames(rows.map { it.name }) }
        } catch (e: Exception) {
            // 课表读不出来不该让整页打不开：这一组给空态说明，别的照常填
            emptyList()
        }
    }

    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf<String?>(null) }

    var suggest by remember { mutableStateOf<PlanSuggest?>(null) }
    /** 勾选的是**下标**（与服务端原样条目一一对应），不是标题 —— 标题可能重复 */
    var picked by remember { mutableStateOf(emptySet<Int>()) }
    var refreshFailed by remember { mutableStateOf(false) }
    var askUndo by remember { mutableStateOf(false) }

    /**
     * 这一批「真的进清单了」的建议下标。
     *
     * 用下标而不是标题：同一个标题连着两次建议都可能出现，按标题打标会误伤。
     * 它同时驱动三件事 —— 逐条的「已加入 ✓」、按钮换样子、跳去学习清单的入口。
     */
    var applied by remember { mutableStateOf(emptySet<Int>()) }

    suspend fun localCounts(): Pair<Int, Int> {
        val tasks = withContext(Dispatchers.IO) { db.dao().tasks().first() }
        return tasks.size to tasks.count { it.phase == PlanLogic.PHASE_AI }
    }

    fun refreshLocal(token: String, after: String) {
        scope.launch {
            step = PlanLogic.REFRESHING
            try {
                when (val r = RemoteSync.sync(ctx, db, api, token)) {
                    is ApiResult.Ok -> {
                        val (total, ai) = localCounts()
                        refreshFailed = false
                        ok = "${after}本机清单现在共 $total 项，其中「AI 规划」$ai 项，" +
                            "切到「学习」页就能看到。"
                    }
                    is ApiResult.Err -> {
                        // 服务端那边已经成了，本机没跟上 —— 这句话必须说清楚是"哪一半"没成
                        refreshFailed = true
                        ok = "${after}但本机清单没刷新成功。${StudentError.TEXT}\n" +
                            "服务器上已经有了，手机上还是旧的 —— 点「${PlanLogic.RETRY_REFRESH}」再拉一次。"
                    }
                }
            } catch (e: Exception) {
                refreshFailed = true
                ok = "${after}但本机清单没刷新成功。${StudentError.tech(e)}\n" +
                    "服务器上已经有了，手机上还是旧的 —— 点「${PlanLogic.RETRY_REFRESH}」再拉一次。"
            } finally {
                step = ""
                busy = false
            }
        }
    }

    fun generate() {
        if (busy) return
        val token = TokenStore.token(ctx)
        if (token.isNullOrBlank()) {
            err = "这台设备还没有登录 App，先退出重登一次"
            return
        }
        busy = true; err = null; ok = null; step = PlanLogic.GENERATING
        applied = emptySet()          // 重新生成 = 上一批的「已加入」标记作废
        scope.launch {
            try {
                // 组装入参交给纯函数（PlanFormLogicTest 直接打那一层）：
                // 没填的分组是 null，编码器 explicitNulls=false 会把它们整个省掉。
                val prefs = PlanLogic.prefs(
                    focus = focus, hours = hours, style = style,
                    goals = goals, periods = periods, daysPerWeek = days,
                    scores = scores, pace = pace, avoid = avoid,
                )
                when (val r = api.planSuggest(token, prefs)) {
                    is ApiResult.Err -> err = r.message
                    is ApiResult.Ok -> {
                        suggest = r.value
                        // 默认全勾上：服务端一次只给 4~8 条，用户多半是想全要的，
                        // 想排除哪条再取消勾 —— 反过来（默认全不勾）会让"点加入"变成空操作
                        picked = r.value.items.indices.toSet()
                        if (r.value.items.isEmpty()) err = PlanLogic.NO_ITEMS
                    }
                }
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PlanScreen", "生成建议失败", e)
                err = StudentError.tech(e)
            } finally {
                step = ""
                busy = false
            }
        }
    }

    fun apply() {
        val s = suggest ?: return
        if (busy) return
        // 先记下这一批的下标：加完之后要逐条打「已加入 ✓」，还要给出跳去清单的入口
        val chosenIdx = picked.sorted()
        val chosen = chosenIdx.mapNotNull { s.raw.getOrNull(it) }
        if (chosen.isEmpty()) {
            err = "先勾选至少一条建议"
            return
        }
        val token = TokenStore.token(ctx)
        if (token.isNullOrBlank()) {
            err = "这台设备还没有登录 App，先退出重登一次"
            return
        }
        busy = true; err = null; ok = null; step = PlanLogic.APPLYING
        scope.launch {
            try {
                when (val r = api.planApply(token, chosen)) {
                    is ApiResult.Err -> err = r.message
                    is ApiResult.Ok -> {
                        // 服务端成了 → 界面上必须**看得见**：逐条打标、按钮换样子、给跳转入口
                        applied = chosenIdx.toSet()
                        // 已经加进去的从勾选里拿掉：留在勾选里看着像"还没加"，而且会重复加
                        picked = emptySet()
                        refreshLocal(
                            token,
                            "已加入 ${r.value.tasks} 条任务（${r.value.steps} 个步骤）。",
                        )
                    }
                }
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PlanScreen", "加入任务清单失败", e)
                err = StudentError.tech(e)
            } finally {
                step = ""
                busy = false
            }
        }
    }

    fun undo() {
        if (busy) return
        askUndo = false
        val token = TokenStore.token(ctx)
        if (token.isNullOrBlank()) {
            err = "这台设备还没有登录 App，先退出重登一次"
            return
        }
        busy = true; err = null; ok = null; step = "正在撤销…"
        scope.launch {
            try {
                when (val r = api.planUndo(token)) {
                    is ApiResult.Err -> err = r.message
                    is ApiResult.Ok -> {
                        applied = emptySet()      // 撤销了就别再挂着「已加入」
                        refreshLocal(
                            token,
                            "已撤销：删掉 ${r.value.tasks} 条任务（${r.value.steps} 个步骤）。",
                        )
                    }
                }
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PlanScreen", "撤销失败", e)
                err = StudentError.tech(e)
            } finally {
                step = ""
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 30.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    PlanLogic.BACK, color = C.txt2, fontSize = 13.sp,
                    modifier = Modifier.clickable { onClose() }.padding(vertical = 6.dp),
                )
                Spacer(Modifier.width(16.dp))
                // 撤销放**顶部明处**：用户要能一眼看见"后悔药"在哪，而不是去翻设置
                Text(
                    PlanLogic.UNDO, fontSize = 13.sp,
                    color = if (busy) C.txt3 else C.amber,
                    modifier = Modifier
                        .clickable(enabled = !busy) { askUndo = true }
                        .padding(vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                PlanLogic.TITLE, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                PlanLogic.NOTE, fontSize = 12.sp, lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(10.dp))
            // 先把"这些选项去哪儿了"说清（原样进给模型的要求），再逐项写"选了会怎样"
            Text(
                PlanLogic.PREFS_NOTE, fontSize = 12.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(22.dp))

            Group(PlanLogic.SECTION_GOALS, PlanLogic.GOALS_HINT) {
                Chips(PlanLogic.GOALS, { it in goals }) { v ->
                    goals = if (v in goals) goals - v else goals + v
                }
            }
            Group(PlanLogic.SECTION_FOCUS, PlanLogic.FOCUS_HINT) {
                Chips(PlanLogic.FOCUS, { it in focus }) { v ->
                    focus = if (v in focus) focus - v else focus + v
                }
            }
            Group(PlanLogic.SECTION_HOURS, PlanLogic.HOURS_HINT) {
                Chips(PlanLogic.HOURS, { it == hours }, PlanLogic::hoursLabel) { v -> hours = v }
            }
            Group(PlanLogic.SECTION_WHEN, PlanLogic.WHEN_HINT) {
                Chips(PlanLogic.PERIODS, { it in periods }) { v ->
                    periods = if (v in periods) periods - v else periods + v
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    PlanLogic.DAYS_HINT, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f),
                )
                Spacer(Modifier.height(6.dp))
                Chips(PlanLogic.DAYS, { it == days }, PlanLogic::daysLabel) { v ->
                    // 再点一下当前档位 = 撤回"没填"，而不是变成"每周 0 天"
                    days = if (days == v) 0 else v
                }
            }
            Group(PlanLogic.SECTION_SUBJECTS, PlanLogic.SUBJECTS_HINT) {
                if (courses.isEmpty()) {
                    // 空态要说清"为什么空、怎么才有"，不能把这一组藏起来 ——
                    // 藏了他就永远不知道有这项（用户明确反感藏起来的入口）
                    Text(PlanLogic.COURSES_EMPTY, fontSize = 11.5.sp, color = C.txt3)
                } else {
                    Text(
                        PlanLogic.SUBJECT_HINT, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f),
                    )
                    Spacer(Modifier.height(6.dp))
                    courses.forEach { name ->
                        ScoreRow(name, scores[name]) { picked ->
                            val next = LinkedHashMap(scores)
                            if (picked == null) next.remove(name) else next[name] = picked
                            scores = next
                        }
                    }
                }
            }
            Group(PlanLogic.SECTION_STYLE, PlanLogic.STYLE_HINT) {
                Chips(PlanLogic.STYLE, { it in style }) { v ->
                    style = if (v in style) style - v else style + v
                }
            }
            Group(PlanLogic.SECTION_PACE, PlanLogic.PACE_HINT) {
                Chips(PlanLogic.PACES, { it == pace }) { v -> pace = if (pace == v) "" else v }
            }
            Group(PlanLogic.SECTION_AVOID, PlanLogic.AVOID_HINT) {
                OutlinedTextField(
                    value = avoid,
                    onValueChange = { avoid = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = PlanLogic.AVOID_FIELD_DESC },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    placeholder = {
                        Text("例如：周日别排、别给我排英语听力", fontSize = 12.sp, color = C.txt3)
                    },
                )
            }

            // 即时预览：把当前的选择翻译成「会怎样推荐」，填一项就跟着变 ——
            // 用户要的是"知道自己的选择对推荐到底有什么影响"，一组组看说明还是散的。
            // 一项都没填 → null → 整块不显示（不摆一坨空话）。
            PlanLogic.effectSummary(
                focus = focus.toList(), hours = hours, style = style.toList(),
                goals = goals.toList(), periods = periods.toList(), daysPerWeek = days,
                scored = scores.entries.map { it.key to it.value }, pace = pace, avoid = avoid,
            )?.let { line ->
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(C.card, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                        .semantics { contentDescription = PlanLogic.EFFECT_DESC },
                ) {
                    Text(line, fontSize = 12.sp, lineHeight = 18.sp, color = C.txt2)
                }
            }

            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(if (busy) C.card else C.violet, RoundedCornerShape(12.dp))
                    .clickable(enabled = !busy) { generate() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy && step.isNotBlank()) step else PlanLogic.GENERATE,
                    color = if (busy) C.txt2 else C.bg,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
            }

            Hint(ok, C.green)
            Hint(err?.let { StudentError.screenText(it) }, C.red)

            suggest?.let { s ->
                Spacer(Modifier.height(16.dp))
                PlanLogic.sourceNote(s.source, s.reason).takeIf { it.isNotBlank() }?.let {
                    Hint(it, C.amber)
                }
                Text(
                    PlanLogic.pickedCount(picked.size, s.items.size),
                    fontSize = 12.sp, color = C.txt2,
                )
                Spacer(Modifier.height(6.dp))
                PlanLogic.visibleItems(s.items).forEach { (i, item) ->
                    ItemRow(
                        title = item.title,
                        why = item.why,
                        track = item.track,
                        priority = item.priority,
                        deliverable = item.deliverable,
                        steps = item.steps,
                        resources = item.resources,
                        on = i in picked,
                        added = i in applied,
                    ) {
                        picked = if (i in picked) picked - i else picked + i
                    }
                }
                Spacer(Modifier.height(6.dp))
                val canApply = !busy && picked.isNotEmpty()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(if (canApply) C.green else C.card, RoundedCornerShape(12.dp))
                        .clickable(enabled = canApply) { apply() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        // 三态分开：正在加 / 已经加进清单了（N 条）/ 还没加 —— 用户要的「交互感」
                        PlanLogic.applyLabel(step, applied.size),
                        color = if (canApply) C.bg else C.txt3,
                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                }
                // 加完之后给一条能走的路：光弹一句话，用户并不知道到底成没成
                AnimatedVisibility(visible = applied.isNotEmpty(), enter = fadeIn()) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(C.cardHi, RoundedCornerShape(12.dp))
                                .clickable { onGoTasks() }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(PlanLogic.GO_TASKS, fontSize = 13.sp, color = C.green)
                        }
                    }
                }
                if (refreshFailed) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        PlanLogic.RETRY_REFRESH, fontSize = 13.sp, color = C.cyan,
                        modifier = Modifier
                            .clickable(enabled = !busy) {
                                TokenStore.token(ctx)?.let { refreshLocal(it, "") }
                            }
                            .padding(vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    PlanLogic.UNDO_BODY, fontSize = 11.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                )
            }
            Spacer(Modifier.height(40.dp))
        }

        if (askUndo) {
            // 危险操作二次确认：普通 Composable 盖一层（不用 Dialog，见文件头第 5 条）
            Box(
                Modifier.fillMaxSize().background(C.bg.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
                    Text(PlanLogic.UNDO_ASK, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(10.dp))
                    Text(PlanLogic.UNDO_BODY, fontSize = 12.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.weight(1f).background(C.cardHi, RoundedCornerShape(12.dp))
                                .clickable { askUndo = false }.padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(PlanLogic.CANCEL, color = C.txt2, fontSize = 14.sp) }
                        Box(
                            Modifier.weight(1f).background(C.amber, RoundedCornerShape(12.dp))
                                .clickable { undo() }.padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(PlanLogic.UNDO_YES, color = C.bg, fontSize = 14.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, hint: String, content: @Composable () -> Unit) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
    Spacer(Modifier.height(3.dp))
    Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f))
    Spacer(Modifier.height(8.dp))
    content()
    Spacer(Modifier.height(18.dp))
}

/**
 * 一排可选片。选中态靠**颜色 + ✓**双重表达：只靠颜色的话，深色底上灰度差得少，
 * 分不清选没选（本项目的 chips 上一次就因为对比度问题被用户截图吐槽过）。
 */
@Composable
private fun <T> Chips(
    options: List<T>,
    isOn: (T) -> Boolean,
    label: (T) -> String = { it.toString() },
    onTap: (T) -> Unit,
) {
    options.chunked(3).forEach { rowItems ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowItems.forEach { o ->
                val on = isOn(o)
                Row(
                    Modifier
                        .background(if (on) C.violet.copy(alpha = 0.16f) else C.card,
                                    RoundedCornerShape(20.dp))
                        .border(1.dp, if (on) C.violet else C.line, RoundedCornerShape(20.dp))
                        .clickable { onTap(o) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (on) {
                        Text("✓ ", fontSize = 11.sp, color = C.violet)
                    }
                    Text(label(o), fontSize = 12.5.sp, color = if (on) C.violet else C.txt2)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 一门课一行：课名 + 1~5 五个档位（1 想加强 … 5 已掌握）。
 *
 * 为什么不是"给个总的自评档位"：用户原话「这个基础自评指代不清，应该给每个课程分开自评」——
 * 一门课一个档位才有意义，"入门/会用/熟练"这种笼统档位说不清是哪门课。
 *
 * 再点一次当前档位 = 撤回（回到"不参与"）。默认**不选**：默认给个中间档，
 * 等于替用户表态，十几门课的中间档会变成噪音塞给模型。
 */
@Composable
private fun ScoreRow(course: String, score: Int?, onPick: (Int?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(course, fontSize = 12.5.sp, color = C.txt2, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlanLogic.SUBJECT_SCORES.forEach { s ->
                val on = s == score
                Box(
                    Modifier
                        .size(28.dp)
                        .background(
                            if (on) C.violet.copy(alpha = 0.16f) else C.card,
                            RoundedCornerShape(8.dp),
                        )
                        .border(1.dp, if (on) C.violet else C.line, RoundedCornerShape(8.dp))
                        .clickable { onPick(if (on) null else s) }
                        .semantics { contentDescription = PlanLogic.scoreDesc(course, s) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$s", fontSize = 12.sp, color = if (on) C.violet else C.txt2)
                }
            }
        }
    }
}

/** 一条建议。**必须带上 why**：用户得知道它凭什么推荐这件事。 */
@Composable
private fun ItemRow(
    title: String,
    why: String,
    track: String,
    priority: Int,
    deliverable: String,
    steps: List<String>,
    resources: List<PlanRes> = emptyList(),
    on: Boolean,
    added: Boolean = false,
    onToggle: () -> Unit,
) {
    Column(
        // 已经进清单的那几条点不动了：再点一次什么也不会发生，不如直接不给点
        Modifier.fillMaxWidth().clickable(enabled = !added) { onToggle() }.padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(16.dp)
                    .background(if (added || on) C.green else C.card, RoundedCornerShape(4.dp))
                    .border(1.dp, if (added || on) C.green else C.lineHi, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (added) Text("✓", fontSize = 10.sp, color = C.bg)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground,
                 modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            if (added) {
                // 逐条打标 + 一点缩放：这一下就是用户说的「交互感」——看得见它进了清单
                AnimatedVisibility(visible = true, enter = scaleIn(initialScale = 0.7f) + fadeIn()) {
                    Text(
                        PlanLogic.APPLIED_BADGE, fontSize = 11.sp,
                        color = C.green, fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                Text(if (on) PlanLogic.PICKED else PlanLogic.UNPICKED,
                     fontSize = 11.sp, color = if (on) C.green else C.txt3)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            PlanLogic.WHY_PREFIX + why.ifBlank { PlanLogic.WHY_EMPTY },
            fontSize = 12.sp, lineHeight = 19.sp, color = C.cyan,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // 轻重档位照实写：服务端 priority 1/2/3 = 高/中/低
            "方向：$track · 轻重：" + when (priority) { 1 -> "高"; 3 -> "低"; else -> "中" },
            fontSize = 11.sp, color = C.txt3,
        )
        if (steps.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text("步骤：" + steps.joinToString(" / "), fontSize = 11.sp, lineHeight = 18.sp,
                 color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
        }
        if (deliverable.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text("产出物：$deliverable", fontSize = 11.sp, color = C.txt3)
        }
        // 资源行放在**步骤下面**：用户是先看完"要做什么"，才决定要不要现在点开那个视频的。
        // 没有资源时这一段整段不出现 —— 宁可什么都不显示，也不写"暂无资源"这种占位。
        if (resources.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            ResLines(resources)
        }
    }
}

/**
 * 一条建议挂着的资源（服务端最多 3 条）：一行一条，**不套卡片、不加底色块**。
 *
 * 为什么不做成按钮或卡片：这里只是"顺路看一眼有没有值得现在看的视频"的地方，
 * 多一层底、多一条边框反而让整页发虚 —— 用户明确反感堆砌出来的廉价感。
 *
 * 点一下直接交给 [Links.open]（Custom Tabs）：B 站那种页面在 App 内嵌 WebView 里是残废的，
 * 少绕一步用户才会真的去看；打不开的情况 Links 自己会提示，也不会崩。
 */
@Composable
private fun ResLines(resources: List<PlanRes>) {
    val ctx = LocalContext.current
    // 没有链接的行直接不画：点了也打不开，画出来只会让人以为界面坏了
    resources.filter { it.url.isNotBlank() }.forEach { r ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { Links.open(ctx, r.url) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 前缀说清这是什么（视频 / 课程 / …），认不出的 kind 写「链接」而不是露英文内部值
            Text(PlanLogic.resLabel(r.kind), fontSize = 10.5.sp, color = C.cyan)
            Spacer(Modifier.width(9.dp))
            Text(
                r.title,
                fontSize = 13.5.sp, color = C.txt,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(PlanLogic.RES_OPEN, fontSize = 10.5.sp, color = C.cyan)
        }
    }
}

@Composable
private fun Hint(text: String?, color: androidx.compose.ui.graphics.Color) {
    text?.takeIf { it.isNotBlank() } ?: return
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.fillMaxWidth()
            .background(C.cardHi, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text, fontSize = 12.5.sp, lineHeight = 20.sp, color = color)
    }
}
