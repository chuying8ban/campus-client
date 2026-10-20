package top.ccbase.campus.data.local

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.ccbase.campus.domain.PeriodTable
import top.ccbase.campus.domain.PeriodUnit

/**
 * 作息时间表的「当前生效值」+ 落盘。
 *
 * 为什么要有内存快照：节次钟点被**非挂起**的地方读（周视图左列、今日页时间列、
 * 导入时把节次换算成 time_text），没法每次都去查库。所以库里的值只在启动/保存时读一次，
 * 之后全 App 共用这份快照。
 *
 * 用 Compose 的 `mutableStateOf` 而不是普通 `var`：用户在「作息时间表」里改完，
 * 周视图左列的时间要**当场**跟着变。普通变量改了不会触发重组，界面会一直显示旧时间。
 */
object PeriodTableStore {
    const val KEY = "period_table_v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    var table by mutableStateOf(PeriodTable())
        private set

    private var cachedFor: PeriodTable? = null
    private var cache: List<PeriodUnit> = PeriodTable().units()

    /** 当前生效的时间轴（缓存跟着 [table] 的实例走） */
    fun units(): List<PeriodUnit> {
        val t = table
        if (t !== cachedFor) {
            cachedFor = t
            cache = t.units()
        }
        return cache
    }

    fun unit(index: Int): PeriodUnit? = units().firstOrNull { it.index == index }

    fun span(from: Int, to: Int): Pair<String, String>? {
        val list = units()
        val a = list.firstOrNull { it.index == from } ?: return null
        val b = list.firstOrNull { it.index == to } ?: a
        return a.start to (if (b.index >= a.index) b.end else a.end)
    }

    /** 库里存的原始串 → 时间表；空/坏数据一律退回默认（宁可显示官方作息，也别让课表空白） */
    fun decode(raw: String?): PeriodTable {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return PeriodTable()
        return runCatching { json.decodeFromString<PeriodTable>(t) }.getOrDefault(PeriodTable())
    }

    suspend fun load(db: CampusDb) {
        table = decode(db.dao().metaGet(KEY))
    }

    /** 保存 + 立刻生效。只写 meta 不改课表本身 —— 已有的时段不会因为改作息被挪走。 */
    suspend fun save(db: CampusDb, next: PeriodTable) {
        val t = next.normalized()
        db.dao().putMeta(listOf(Meta(KEY, json.encodeToString(t))))
        table = t
    }

    /** 测试用：把内存快照拨回默认，避免用例之间互相污染 */
    fun resetForTest() {
        table = PeriodTable()
        cachedFor = null
        cache = PeriodTable().units()
    }

    /** 测试用：直接拨表（不落库） */
    fun setForTest(t: PeriodTable) {
        table = t
    }
}
