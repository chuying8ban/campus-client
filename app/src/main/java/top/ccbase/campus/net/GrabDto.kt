package top.ccbase.campus.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 抢课（/api/v2/grab/… 那组接口）的数据形状 —— 字段名对齐服务端真实列名，别自己发明。
 *
 * 服务端这些接口对**非作者一律 403**：App 侧把入口藏起来只是防误用，
 * 真正的边界在服务端 can_grab。所以这里 403 必须翻成人话，不能显示成"网络异常"。
 */

/**
 * 「长得像布尔的字段」的宽容解析器 —— **这个类是一场真事故的产物**。
 *
 * `/api/v2/grab/status` 里 targets 是 `SELECT * FROM targets` 出来的**数据库整行**，
 * SQLite 没有布尔类型，`auto_submit INTEGER DEFAULT 0` 弹出来就是 `0` / `1`。
 * 而 App 侧声明成 `Boolean` → kotlinx.serialization 抛
 * "Expected boolean literal"，整份响应解析失败 ——
 * 界面上就是那句「监控状态无法解析」，且 HTTP 状态码是 200，光看状态码永远发现不了。
 *
 * 所以这里同时吃三种形状：`true/false`、`0/1`、`"0"/"1"/"true"/"false"`。
 * 服务端将来把列改成原生布尔也不用再改 App —— 这个字段服务端已经改过一次形状了。
 */
object BoolishSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("campus.Boolish", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)

    override fun deserialize(decoder: Decoder): Boolean {
        val jd = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val el = jd.decodeJsonElement()
        val p = el as? JsonPrimitive ?: return false
        // 数字 0/1 走这条；"true"/"1" 这类字符串走下面那条
        p.booleanOrNull?.let { return it }
        if (p.isString) return p.content.trim().lowercase() in setOf("1", "true", "yes", "on")
        return p.content.trim().let { it == "1" || it.equals("true", true) }
    }
}

/** 可选课程清单里的一行（catalog 表 + probe 的实测结论） */
@Serializable
data class GrabLesson(
    val lesson_id: Int = 0,
    val turn_id: Int = 0,
    val course: String = "",
    val code: String = "",
    val prop: String = "",
    val credits: Double = 0.0,
    val teacher: String = "",
    /** 服务端把「周次 星期 节次 教室」全塞在这一列里，用 `;` 分隔多个时段 */
    val place: String = "",
    val limit_cnt: Int = 0,
    /** 上课班级，如「自动化24-[1-4]班」—— 抢课时要看清楚是给谁开的 */
    val cls: String = "",
    val used: Int = 0,
    val free: Int = 0,
    /** 1 = 我已经选了这门 */
    val mine: Int = 0,
    /** 与我的课表冲突的条数，0 = 不冲突 */
    val cn: Int = 0,
    /** 学校预检结论：1 放行、0 不放行、null 还没测 */
    val probe_ok: Int? = null,
    /**
     * 预检不放行时学校的原话。**必须可空**：
     * 实测 flt=all 里有 58 条 probe_reason 是 SQL NULL（没预检过的课），
     * 声明成非空字符串会让整个清单解析失败（而这正是"全部/有余位"两个档位打不开的原因）。
     */
    val probe_reason: String? = null,
    /**
     * 与我的课表冲突的明细（服务端 `conflict` 那一列的数组形态）：
     * 数组里每一项就是撞上的那门课。`cn` 是它的条数。
     */
    val clash: List<GrabClash> = emptyList(),
) {
    /** 能否现在就抢：有余位 + 没选过 + 预检放行 + 不冲突。四件同时成立才算。 */
    val grabbable: Boolean get() = free > 0 && mine == 0 && probe_ok == 1 && cn == 0
}

/** 一条时间冲突：撞上的那门课 + 它占的时间地点 */
@Serializable
data class GrabClash(
    val course: String = "",
    /** 1=周一 … 7=周日 */
    val weekday: Int = 0,
    val time_text: String = "",
    val room: String? = null,
)

@Serializable
data class GrabStats(
    val total: Int = 0,
    val free: Int = 0,
    val mine: Int = 0,
    val probed: Int = 0,
    val ok: Int = 0,
    val conf: Int = 0,
    val ok_clean: Int = 0,
    val updated: String = "",
)

@Serializable
data class GrabCatalog(
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 60,
    val stats: GrabStats = GrabStats(),
    val lessons: List<GrabLesson> = emptyList(),
)

/** 已加入监控的教学班 */
@Serializable
data class GrabTarget(
    val id: Int = 0,
    val turn_id: Int = 0,
    val lesson_id: Int = 0,
    val course: String = "",
    val teacher: String = "",
    val limit_cnt: Int = 0,
    /** 1 = 参与轮询；0 = 暂停 */
    val enabled: Int = 1,
    /**
     * 服务端 targets 表里的 auto_submit（SQLite INTEGER 0/1，所以必须宽容解析）。
     * App 侧**只显示**它，不乱猜：真正开启的开关在服务端，客户端显示的永远是服务端说的那个值。
     */
    @Serializable(with = BoolishSerializer::class)
    val auto_submit: Boolean = false,
    /** 1 = 已经自动提交抢到了 */
    val grabbed: Int = 0,
    val created_at: String = "",
    /** 冲突的人话描述；空串 = 不冲突 */
    val clash_text: String = "",
)

/** 服务端 logs 表的一行 —— 轮询到底干过什么（有没有真的提交过），全在这里 */
@Serializable
data class GrabLog(
    val id: Int = 0,
    val ts: String = "",
    /** info / warn / alert / error */
    val level: String = "info",
    val msg: String = "",
)

@Serializable
data class GrabStatus(
    val configured: Boolean = false,
    /** 服务端监控线程的总开关（cfg.monitor_on） */
    val monitor_on: Boolean = false,
    /** 服务端全局自动提交开关（cfg.auto_submit） */
    @Serializable(with = BoolishSerializer::class)
    val auto_submit: Boolean = false,
    /** 轮询间隔（秒） */
    val interval: Int = 20,
    val logged_in: Boolean = false,
    val last_check: String = "",
    val last_error: String = "",
    val targets: List<GrabTarget> = emptyList(),
    /** 最近 20 条动作日志 —— 「方便使用」的关键：不信在跑，就什么都不信 */
    val logs: List<GrabLog> = emptyList(),
    val catalog: GrabStats = GrabStats(),
) {
    /** 任一门课开了自动提交（含全局开关）—— 状态指示只认这个，不认界面上的临时动作。 */
    val autoEnabled: Boolean get() = auto_submit || targets.any { it.auto_submit }

    /** 真的在盯东西吗：开关打开 **并且** 至少有一个目标。只看 monitor_on 会说反话。 */
    val watching: Boolean get() = configured && monitor_on && targets.isNotEmpty()
}

/** 抢课总开关的回读值（服务端回什么就是什么，界面只认它） */
@Serializable
data class GrabCfgOut(
    val ok: Boolean = false,
    val monitor_on: Boolean = false,
    val auto_submit: Boolean = false,
    val interval: Int = 20,
)

@Serializable
data class GrabAddResult(
    val ok: Boolean = false,
    val clash_text: String = "",
    /**
     * 服务端对 App 提交的 auto_submit **永远回 false**：/api/v2/grab/target 里那一列是写死的。
     * 所以客户端拿到 true 才算开启成功，false 就必须照实说"没开成"，不能假装开了。
     */
    @Serializable(with = BoolishSerializer::class)
    val auto_submit: Boolean = false,
)
