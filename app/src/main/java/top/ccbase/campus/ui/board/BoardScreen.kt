package top.ccbase.campus.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.ChecklistItem
import top.ccbase.campus.data.local.Milestone
import top.ccbase.campus.domain.BoardStats
import top.ccbase.campus.domain.boardStats
import top.ccbase.campus.domain.orderedChecklist
import top.ccbase.campus.domain.orderedMilestones
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits
import java.time.LocalDate

/**
 * 看板页：总览 + 四年时间线（里程碑）+ 检查清单。
 *
 * 里程碑用"左侧竖线 + 圆点"的时间线呈现，而不是一堆卡片 ——
 * 它的本质是**时间顺序**，卡片会把这条线打散。
 */
@Composable
fun BoardScreen(db: CampusDb) {
    val ctx = LocalContext.current
    var stats by remember { mutableStateOf<BoardStats?>(null) }
    var milestones by remember { mutableStateOf<List<Milestone>>(emptyList()) }
    var checklist by remember { mutableStateOf<List<ChecklistItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        (ctx.applicationContext as? CampusApplication)?.seedJob?.join()
        val days = db.dao().recentActiveDays(400).toSet()
        stats = boardStats(
            // visibleTasks()：从清单里删掉的不该再算进"总任务数 / 进度"
            tasks = db.dao().visibleTasks().first(),
            steps = db.dao().allSteps().first(),
            resources = db.dao().visibleResources().first(),
            activeDays = days,
            today = LocalDate.now(),
        )
        milestones = orderedMilestones(db.dao().milestones().first())
        checklist = orderedChecklist(db.dao().checklist().first())
    }

    val s = stats ?: run {
        Box(Modifier.fillMaxSize().padding(22.dp)) { Text("正在统计…", color = C.txt3, fontSize = 13.sp) }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 30.dp, bottom = 28.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("看板", color = C.magenta, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("进度、时间线、以及每学期该对一遍的清单。", color = C.txt3, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            }
        }

        // ---------------- 总览 ----------------
        item {
            Column(Modifier.padding(top = 22.dp)) {
                Row {
                    Stat("连续", if (s.streak > 0) "${s.streak} 天" else "0 天", if (s.streak > 0) C.cyan else C.txt2)
                    Stat("步骤", "${s.stepsDone}/${s.stepsTotal}", C.green)
                    Stat("本学期任务", "${s.activeTasks}", C.txt)
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    Stat("已完成任务", "${s.tasksFinished} 项", if (s.tasksFinished > 0) C.green else C.txt2)
                    Stat("资源", "${s.resourceTotal} 个", C.txt)
                    Stat("全部任务", "${s.taskTotal} 项", C.txt2)
                }
            }
        }

        // ---------------- 里程碑（四年时间线） ----------------
        item {
            Column(Modifier.padding(top = 30.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("里程碑", color = C.txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${milestones.size} 个 · 四年时间线", color = C.txt3, fontSize = 11.sp)
                }
            }
        }
        items(milestones, key = { "m-${it.id}" }) { m ->
            Row(Modifier.fillMaxWidth()) {
                // 时间线的竖线与节点
                Column(Modifier.width(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(top = 5.dp).size(7.dp).clip(RoundedCornerShape(4.dp)).background(C.violet.copy(alpha = 0.85f)))
                    Box(Modifier.width(1.dp).weight(1f).background(C.line))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(bottom = 20.dp)) {
                    Text(m.when_text ?: "", color = C.txt, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Line("学业", m.academic)
                    Line("附加", m.extra)
                    Line("检查点", m.checkpoint)
                }
            }
        }

        // ---------------- 检查清单 ----------------
        item {
            Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("检查清单", color = C.txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${checklist.size} 条 · 每学期对一遍", color = C.txt3, fontSize = 11.sp)
                }
            }
        }
        items(checklist, key = { "c-${it.id}" }) { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .size(13.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(C.card)
                        .then(Modifier),
                )
                Spacer(Modifier.width(12.dp))
                Text(c.item ?: "", color = C.txt2, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            Text(
                "清单是给自己复查用的，App 不打勾 —— 这几条要对着教务系统和成绩单看。",
                color = C.txt3, fontSize = 11.sp, lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.padding(end = 26.dp)) {
        Text(value, color = color, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, style = Digits)
        Spacer(Modifier.height(2.dp))
        Text(label, color = C.txt3, fontSize = 10.5.sp)
    }
}

@Composable
private fun Line(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(bottom = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = C.txt3, fontSize = 10.5.sp, modifier = Modifier.width(44.dp))
        Text(value, color = C.txt2, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}
