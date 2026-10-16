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
                Text("历史活动记录，不再生成督促任务。", color = C.txt3, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            }
        }

        item { Row(Modifier.padding(top=20.dp)) { Stat("历史连续活跃", "${s.streak} 天", C.cyan) } }

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
