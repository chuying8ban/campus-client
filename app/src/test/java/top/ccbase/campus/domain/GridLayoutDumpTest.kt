package top.ccbase.campus.domain

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.data.local.Slot

/**
 * 把**真 Kotlin 排版逻辑**在真实数据上跑出来的网格 dump 成 JSON，
 * 供渲染预览（python 只负责画，不做任何逻辑，避免"预览和真机不一样"）。
 *
 * 顺带钉住 1.49 的语义：行 = 单个节次；课块从起始节次起画、中间行不重复起画。
 */
class GridLayoutDumpTest {

    private val J = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun str(o: JsonObject, k: String): String? =
        (o[k] as? JsonPrimitive)?.takeIf { it.content != "null" }?.content

    private fun int(o: JsonObject, k: String): Int? = str(o, k)?.toDoubleOrNull()?.toInt()

    @Test
    fun `dump 真实课表的网格布局`() {
        val raw = File("src/test/resources/live_plan_sample_1.json").readText()
        val root = J.parseToJsonElement(raw).jsonObject
        val plan = (root["plan"] as? JsonObject) ?: root
        val slotArr = (plan["slots"] as? JsonArray) ?: JsonArray(emptyList())

        val slots = slotArr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val wd = int(o, "weekday") ?: return@mapNotNull null
            val ps = int(o, "p_start") ?: return@mapNotNull null
            Slot(
                id = int(o, "id") ?: 0,
                course_id = int(o, "course_id") ?: 0,
                weekday = wd,
                p_start = ps,
                p_end = int(o, "p_end") ?: ps,
                room = str(o, "room"),
                time_text = str(o, "time_text"),
                weeks = str(o, "weeks"),
                week_from = int(o, "week_from"),
                week_to = int(o, "week_to"),
            )
        }
        assertTrue("夹具里要有 slots", slots.size >= 15)
        println("=== 夹具 slots: ${slots.size} 段 ===")

        val courses = ((plan["courses"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            (int(o, "id") ?: 0) to (str(o, "name") ?: "?")
        }.toMap()

        // 选一周渲染：优先挑"同一门课有两套安排"的那周（第 5 周，周五 1-2 节 = I区212）
        val week = 5
        val shown = slotsInWeek(slots, week)
        val units = unitRange(shown)
        assertEquals("行轴从第 1 节起", 1, units.first)
        assertEquals(
            "行轴 = 这一周用到的最大节次（后面不补空行）",
            shown.maxOf { it.p_end ?: 0 }, units.last,
        )

        val days = (1..7).filter { d -> shown.any { it.weekday == d } }

        // 每一行：左边节次+该节钟点；每天：从这一节起的课，或空白
        val rows = units.map { u ->
            val cells = days.associateWith { d ->
                val start = slotsStartingAt(shown, d, u)
                if (start.isEmpty()) null else {
                    val span = start.maxOf { spanUnits(it) }
                    JsonObject(
                        mapOf(
                            "span" to JsonPrimitive(span),
                            "items" to JsonArray(start.map { s ->
                                JsonObject(
                                    mapOf(
                                        "course" to JsonPrimitive(courses[s.course_id] ?: "?"),
                                        "room" to JsonPrimitive(s.room ?: ""),
                                        "from" to JsonPrimitive(s.p_start ?: u),
                                        "to" to JsonPrimitive(s.p_end ?: u),
                                    )
                                )
                            }),
                        )
                    )
                }
            }.filterValues { it != null }.mapValues { it.value!! }
            JsonObject(
                mapOf(
                    "unit" to JsonPrimitive(u),
                    "time" to JsonPrimitive(unitTimeLabel(u)),
                    "cells" to JsonObject(cells.mapKeys { it.key.toString() }),
                )
            )
        }

        val out = JsonObject(
            mapOf(
                "week" to JsonPrimitive(week),
                "days" to JsonArray(days.map { JsonPrimitive(it) }),
                "rows" to JsonArray(rows),
            )
        )
        File("/tmp/grid_layout.json").writeText(out.toString())
        println("=== 已写 /tmp/grid_layout.json（第 $week 周，${units.count()} 行 × ${days.size} 天）===")

        // 控制台也打一张字符表，方便我在日志里直接看
        val head = StringBuilder("        ")
        days.forEach { head.append("周${"一二三四五六日"[it - 1]}".padEnd(14)) }
        println(head)
        units.forEach { u ->
            val line = StringBuilder("$u  ${unitTimeLabel(u)}".padEnd(8))
            days.forEach { d ->
                val start = slotsStartingAt(shown, d, u)
                val txt = if (start.isEmpty()) "" else start.joinToString("+") { s ->
                    "${courses[s.course_id]}(${s.p_start}-${s.p_end})"
                }
                line.append(txt.take(13).padEnd(14))
            }
            println(line)
        }
    }
}
