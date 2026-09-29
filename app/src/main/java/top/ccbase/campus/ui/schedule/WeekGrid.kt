package top.ccbase.campus.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.domain.classDays
import top.ccbase.campus.domain.nameOf
import top.ccbase.campus.domain.slotsInWeek
import top.ccbase.campus.domain.slotsStartingAt
import top.ccbase.campus.domain.spanUnits
import top.ccbase.campus.domain.teacherOf
import top.ccbase.campus.domain.unitRange
import top.ccbase.campus.domain.unitTimeLabel
import top.ccbase.campus.ui.theme.C

/**
 * 教务系统那种周课表。
 *
 * 行 = **单个节次**（左列只写节号 + 该节的钟点，不写「1-2 节」这种合并标签 ——
 * 用户明确要求「就一节一节地来」）；列 = 有课的星期；一门课占它跨的那几行。
 * 一格多门课（换机房、单双周）会叠着画，各自带教室。
 *
 * 为什么列要跳过没课的星期：七列平分宽度时，课名会被压成四行还读不全；
 * 只画有课的列，课块宽约四成，全名 + 教室 + 教师都放得下。
 */
@Composable
fun WeekGrid(
    slots: List<Slot>,
    courses: List<Course>,
    week: Int,
    todayWd: Int,
    onPickCourse: (Course) -> Unit,
) {
    val shown = remember(slots, week) { slotsInWeek(slots, week) }
    val units = remember(slots) { unitRange(slots) }
    val days = remember(slots) { classDays(slots) }
    val palette = listOf(C.violet, C.cyan, C.amber, C.green, C.magenta, C.red)

    val rowH = 46.dp
    val leftW = 62.dp
    // ⚠️ 同上：C.line 自带 alpha（0x13FFFFFF = 7.5% 白），copy(alpha = .5f) 会把它抹成
    // "50% 的白"，整张表会浮出一片亮灰格线。要更明显就用 C.lineHi。
    val line = C.line

    Column(Modifier.fillMaxWidth()) {

        // ---------------- 表头：星期 ----------------
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(leftW))
            days.forEach { d ->
                val isToday = d == todayWd
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        // ⚠️ 空档格必须是 C.card 原值（4% 白）。曾经写成 copy(alpha = .45f)，
                        // 那会抹成"45% 的白" → 整片空格变成浅灰砖块（用户截图里那块灰就是它）。
                        .background(if (isToday) C.violet.copy(alpha = 0.18f) else C.card)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "周" + "一二三四五六日"[d - 1],
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isToday) C.violet else C.txt2,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        // ---------------- 表体：左列一节一行，各日各一列 ----------------
        Row(Modifier.fillMaxWidth()) {

            // 左列：只写节号 + 该节钟点（没有「1-2 节」这种合并标签）
            Column(Modifier.width(leftW)) {
                units.forEach { u ->
                    Box(
                        Modifier
                            .height(rowH)
                            .fillMaxWidth()
                            .border(0.5.dp, line)
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "$u",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = C.txt2,
                            )
                            val t = unitTimeLabel(u)
                            if (t.isNotEmpty()) {
                                Text(
                                    t,
                                    fontSize = 8.sp,
                                    color = C.txt3,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                )
                            }
                        }
                    }
                }
            }

            // 每一天一列：从第 1 节往上走，遇到"从这一节开始"的课就画一块并跳过它占的行
            days.forEach { d ->
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 1.dp)
                ) {
                    var u = units.first
                    while (u <= units.last) {
                        val here = slotsStartingAt(shown, d, u)
                        if (here.isEmpty()) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .height(rowH)
                                    .border(0.5.dp, line)
                            )
                            u += 1
                        } else {
                            val span = here.maxOf { spanUnits(it) }
                            Block(
                                slots = here,
                                courses = courses,
                                height = rowH * span,
                                palette = palette,
                                onPickCourse = onPickCourse,
                            )
                            u += span
                        }
                    }
                }
            }
        }
    }
}

/** 一块课：跨几节就有几节高。一格两条（换机房/单双周）就上下各写一条。 */
@Composable
private fun Block(
    slots: List<Slot>,
    courses: List<Course>,
    height: androidx.compose.ui.unit.Dp,
    palette: List<Color>,
    onPickCourse: (Course) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(1.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            slots.forEach { s ->
                val c = courses.firstOrNull { it.id == s.course_id }
                val color = palette[Math.floorMod(s.course_id ?: 0, palette.size)]
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(color.copy(alpha = 0.20f))
                        .border(0.5.dp, color.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .clickable(enabled = c != null) { c?.let(onPickCourse) }
                        .padding(horizontal = 3.dp, vertical = 2.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            nameOf(courses, s),
                            fontSize = 9.5.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = C.txt,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        s.room?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                fontSize = 8.sp,
                                lineHeight = 9.5.sp,
                                color = C.txt2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        teacherOf(courses, s)?.let {
                            Text(
                                it,
                                fontSize = 8.sp,
                                lineHeight = 9.5.sp,
                                color = C.txt3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
                if (slots.size > 1) Spacer(Modifier.height(1.dp))
            }
        }
    }
}
