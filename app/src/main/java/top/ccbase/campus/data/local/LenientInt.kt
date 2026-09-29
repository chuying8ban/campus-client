package top.ccbase.campus.data.local

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 宽容的整数解析：**服务端把数字字段写成空字符串/字符串时，不要整份数据都解析不出来**。
 *
 * 为什么要它（2026-09-17 真出事）：
 *   服务端生成计划时把 `courses.target` 写成了 `""`（本该是数字或 null）。
 *   kotlinx 遇到类型不符会直接抛 `Expected numeric literal at path: $.courses[0].target`，
 *   于是 **整份 /plan 响应**解析失败 —— 用户屏幕上就只有一行「更新失败：计划数据无法解析」，
 *   课表、任务、资源**全都没了**，而他什么都没做错。
 *
 * 取舍（这一条是有意为之）：
 *   · 客户端**兜住**脏值（""/"85"/null 都当数字/null 处理）——宁可少一个「目标分」，
 *     也不能让整份计划崩掉；一个可选字段的脏值不该有这么大的杀伤力。
 *   · 服务端那头用**契约测试**盯着，别让它再写脏（见 test_plan_contract.py）。
 *   两边分工：客户端负责韧性，服务端负责契约。
 */
object LenientInt : KSerializer<Int?> {
    // 用 Int 的官方序列化器的可空版本 —— 比自己拼一个 descriptor 靠谱（后者要小心 deprecated 的 .nullable）
    override val descriptor: SerialDescriptor = Int.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): Int? {
        // 只有 JSON 解码器才谈得上"宽容"；别的情况下老实走原生解码
        val jd = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return when (val e = jd.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> if (e.isString) e.content.trim().toIntOrNull()   // "" → null
                               else e.content.trim().toIntOrNull()
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}
