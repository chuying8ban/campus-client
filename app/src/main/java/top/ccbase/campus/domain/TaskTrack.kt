package top.ccbase.campus.domain

import top.ccbase.campus.data.local.Task

/**
 * 任务的分类轴 —— 服务端建任务时写进 `track` 的那个值。
 *
 * 真实数据里出现过 6 种（uid=1 实测：课程 22 / 自学 18 / 考试 11 / 课外 6 / 手续 5 / 正课 3）。
 * 顺序是固定的：**同一类在任何页面都同一个颜色**，靠的就是这个稳定序号，不靠列表顺序。
 * 没见过的分类一律归到「其他」，排在最后 —— 服务端以后加新类，界面不会崩，只是先共用灰色。
 */
val TRACK_ORDER = listOf("正课", "课程", "自学", "课外", "考试", "手续")

fun trackLabel(track: String?): String =
    track?.trim()?.takeIf { it.isNotEmpty() } ?: "其他"

/** 稳定序号 → 稳定颜色。未知分类 = TRACK_ORDER.size（配色表最后一位=灰）。 */
fun trackIndex(track: String?): Int {
    val t = trackLabel(track)
    val i = TRACK_ORDER.indexOf(t)
    return if (i >= 0) i else TRACK_ORDER.size
}

/** 筛选项只列**数据里真出现过的**分类（按固定顺序），末尾按需补「其他」。 */
fun trackFilters(tasks: List<Task>): List<String> = trackFiltersOf(tasks.map { it.track })

/** 同上，直接给一串 track 值（列表行只带 track） */
fun trackFiltersOf(tracks: List<String?>): List<String> {
    val present = TRACK_ORDER.filter { k -> tracks.any { trackLabel(it) == k } }
    val other = tracks.any { trackLabel(it) !in TRACK_ORDER }
    return present + if (other) listOf("其他") else emptyList()
}

/** filter == null 表示「全部」 */
fun matchesTrack(task: Task, filter: String?): Boolean = matchesTrack(task.track, filter)

/**
 * 同上，直接给 track 值（列表行的 TaskRow 只带着 track）。
 *
 * 「其他」是**桶**而不是某个真实 track 值：不在固定顺序里的一律算它
 * （原先写成 `label == "其他"`，于是点「其他」什么都筛不出来 —— 只有 track 为空的才中 ✗）。
 */
fun matchesTrack(track: String?, filter: String?): Boolean {
    if (filter == null) return true
    val label = trackLabel(track)
    return if (filter == "其他") label !in TRACK_ORDER else label == filter
}
