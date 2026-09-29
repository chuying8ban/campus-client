package top.ccbase.campus.ui.tasks

import androidx.compose.ui.graphics.Color
import top.ccbase.campus.domain.trackIndex
import top.ccbase.campus.ui.theme.C

/**
 * 任务分类的配色。
 *
 * 顺序必须和 `domain/TaskTrack.TRACK_ORDER` 对齐（正课/课程/自学/课外/考试/手续），
 * 用到序号找不到颜色时退回灰色 —— 也就是「其他」那一类。
 * 这些色和主题是同一套（紫/青/琥珀/绿/品红/红），深浅主题下都能读。
 */
private val TRACK_PALETTE = listOf(C.violet, C.cyan, C.amber, C.green, C.magenta, C.red)

fun trackColor(track: String?): Color = TRACK_PALETTE.getOrElse(trackIndex(track)) { C.txt3 }
