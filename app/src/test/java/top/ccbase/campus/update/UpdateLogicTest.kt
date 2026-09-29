package top.ccbase.campus.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动更新的纯逻辑测试。
 *
 * 自动更新最怕的不是"没更新成功"，而是**更新错了**：装上一个来源不明的包，
 * 或者版本判断写反了导致所有人一直弹框。所以这里的用例全在盯这两件事：
 *   - 什么样的 manifest 才允许被信任（校验和/版本号缺一不可）
 *   - 什么时候该弹、什么时候闭嘴（限流 + 「以后再说」）
 */
class UpdateLogicTest {

    private val goodSha = "a".repeat(64)

    private fun json(
        code: Int = 2, name: String = "1.23-T1", url: String = "updates/campus-1.23-T1.apk",
        sha: String = goodSha, size: Long = 1234, notes: String = """["修好了假报错","更新框说人话"]""",
        force: Boolean = false, minCode: Int = 0,
    ) = """
        {"version_code":$code,"version_name":"$name","url":"$url","sha256":"$sha","size":$size,
         "notes":$notes,"force":$force,"min_version_code":$minCode}
    """.trimIndent()

    // ---------------------------------------------------------------- 线上真实清单

    /**
     * 用**线上真拉下来的** manifest 当夹具。
     *
     * 单测里手写的 JSON 永远会"很配合"地符合代码的预期；真实清单是另一回事
     * （字段名、相对路径、校验和格式、notes 里带中文标点）。
     * 这份夹具就是这次线上发布的那份，改动它会同步暴露契约漂移。
     */
    @Test
    fun `线上真清单能被解析并判定为有更新`() {
        val text = javaClass.classLoader!!.getResourceAsStream("update_manifest_real.json")!!
            .readBytes().decodeToString()
        val m = UpdateLogic.parse(text)
        assertTrue("线上清单解析不了 —— App 会安静地以为「已是最新」", m != null)
        m!!
        assertTrue("版本号要真的比老版本大", m.versionCode > 1)
        // 版本名/URL 不写死：它们每发一版就变，写死就得让发布脚本去"改写测试文件"——
        // 那个改写一旦改漏或改错对象就是假红（1.69 在闸门连红两轮就为这个）。
        // 从夹具原文里抠出来比：钉住的仍是"解析器把清单字段原样读出来了"。
        val wantName = Regex("\"version_name\"\\s*:\\s*\"([^\"]+)\"").find(text)!!.groupValues[1]
        assertEquals(wantName, m.versionName)
        assertEquals("校验和必须是 64 位小写十六进制", 64, m.sha256.length)
        assertTrue("大小要合理（几 MB 的安装包）", m.size > 1_000_000)
        assertTrue("更新说明不能是空的，用户要知道自己在装什么", m.notes.isNotEmpty())

        // 路径解析：清单里写的是站内绝对路径，得落在 /updates/ 下
        val resolved = UpdateLogic.resolveUrl("https://api.example.com", m.url)
        val wantUrl = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(text)!!.groupValues[1]
        assertEquals("https://api.example.com" + wantUrl, resolved)
        assertTrue("APK 必须在 /updates/ 通道里，不能跑到站点根目录", resolved.contains("/updates/"))

        // 老版本（versionCode 1）看到它 → 可选更新；装好之后就不再弹
        assertTrue(UpdateLogic.decide(1, m) is UpdateLogic.Decision.Optional)
        assertTrue(UpdateLogic.decide(m.versionCode, m) is UpdateLogic.Decision.UpToDate)
    }

    // ------------------------------------------------- 更新前先要权限（下载之前）

    @Test
    fun `更新前先要权限_没许可就别开始下载`() {
        assertEquals(
            "许可是开着的，正常下载",
            UpdateLogic.UpdateGate.Download, UpdateLogic.gateBeforeDownload(canInstall = true),
        )
        assertEquals(
            "许可没开必须先要许可 —— 不能下完 7MB 才卡在安装那一步",
            UpdateLogic.UpdateGate.AskInstallPermission, UpdateLogic.gateBeforeDownload(canInstall = false),
        )
    }

    @Test
    fun `安装许可这几句话要说清开关名_点哪儿_点完会怎样`() {
        val steps = UpdateLogic.installPermissionSteps()
        assertTrue("必须点名系统里那个开关", steps.any { it.contains("安装未知应用") })
        assertTrue("必须说清点哪里（去允许）", steps.any { it.contains("去允许") })
        assertTrue("必须说清授权后会怎样，否则用户以为回来还要再点一遍", steps.any { it.contains("自动继续") })
        assertTrue("话不能是空的", steps.all { it.length > 12 })
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun `正常清单要能解析出全部字段`() {
        val m = UpdateLogic.parse(json())!!
        assertEquals(2, m.versionCode)
        assertEquals("1.23-T1", m.versionName)  // 跟 json() 的默认值一致；合成夹具，别跟着发布版本号改
        assertEquals(goodSha, m.sha256)
        assertEquals(1234L, m.size)
        assertEquals(2, m.notes.size)
    }

    @Test
    fun `服务端加字段不该让 App 崩`() {
        val extra = """{"version_code":2,"version_name":"1.23-T1","url":"a.apk",
            "sha256":"$goodSha","channel":"beta","published_at":"2026-09-17"}"""
        assertEquals(2, UpdateLogic.parse(extra)!!.versionCode)
    }

    @Test
    fun `少了校验和的清单一律不认`() {
        // 没有 sha256 就没法验证下下来的包，宁可这次不更新
        assertNull(UpdateLogic.parse("""{"version_code":2,"version_name":"1.23-T1","url":"a.apk"}"""))
    }

    @Test
    fun `校验和长度不对也不认`() {
        assertNull(UpdateLogic.parse(json(sha = "abc123")))
    }

    @Test
    fun `版本号为零或地址为空都不认`() {
        assertNull(UpdateLogic.parse(json(code = 0)))
        assertNull(UpdateLogic.parse(json(url = "")))
        assertNull(UpdateLogic.parse(json(name = "")))
    }

    @Test
    fun `不是 JSON 就别抛异常 当没查到`() {
        assertNull(UpdateLogic.parse("<html>404 Not Found</html>"))
        assertNull(UpdateLogic.parse(""))
    }

    // ---------------------------------------------------------------- 判断

    @Test
    fun `同版本或更低版本算最新`() {
        val m = UpdateLogic.parse(json(code = 2))!!
        assertTrue(UpdateLogic.decide(2, m) is UpdateLogic.Decision.UpToDate)
        assertTrue(UpdateLogic.decide(3, m) is UpdateLogic.Decision.UpToDate)
    }

    @Test
    fun `高一个版本是可选更新`() {
        val d = UpdateLogic.decide(1, UpdateLogic.parse(json(code = 2))!!)
        assertTrue(d is UpdateLogic.Decision.Optional)
    }

    @Test
    fun `服务端标 force 就是强制更新`() {
        val d = UpdateLogic.decide(1, UpdateLogic.parse(json(force = true))!!)
        assertTrue(d is UpdateLogic.Decision.Forced)
        assertTrue((d as UpdateLogic.Decision.Forced).why.isNotBlank())
    }

    @Test
    fun `低于最低支持版本也强制更新`() {
        // 老版本接口不兼容时用这招：不给「以后再说」的退路
        val d = UpdateLogic.decide(1, UpdateLogic.parse(json(minCode = 3))!!)
        assertTrue(d is UpdateLogic.Decision.Forced)
        assertTrue((d as UpdateLogic.Decision.Forced).why.contains("v3"))
    }

    @Test
    fun `说过以后再说 的同一个版本不再打扰`() {
        val m = UpdateLogic.parse(json(code = 2))!!
        assertTrue(UpdateLogic.decide(1, m, skippedCode = 2) is UpdateLogic.Decision.UpToDate)
    }

    @Test
    fun `以后再说 不能吃掉强制更新`() {
        val m = UpdateLogic.parse(json(code = 2, minCode = 2))!!
        assertTrue(UpdateLogic.decide(1, m, skippedCode = 2) is UpdateLogic.Decision.Forced)
    }

    @Test
    fun `以后再说 过后的新版本还要提示`() {
        val m2 = UpdateLogic.parse(json(code = 2))!!
        val m3 = UpdateLogic.parse(json(code = 3))!!
        assertTrue(UpdateLogic.decide(1, m2, skippedCode = 2) is UpdateLogic.Decision.UpToDate)
        assertTrue(UpdateLogic.decide(1, m3, skippedCode = 2) is UpdateLogic.Decision.Optional)
    }

    // ---------------------------------------------------------------- 限流

    @Test
    fun `六小时内不重复查 手动可以强制查`() {
        val now = 1_000_000_000L
        assertFalse(UpdateLogic.shouldCheckNow(now - 60_000, now))
        assertTrue(UpdateLogic.shouldCheckNow(now - 60_000, now, force = true))
        assertTrue(UpdateLogic.shouldCheckNow(now - UpdateLogic.CHECK_GAP_MS, now))
        assertTrue("从没查过就要查", UpdateLogic.shouldCheckNow(0, now))
    }

    // ---------------------------------------------------------------- 地址

    @Test
    fun `相对地址按 manifest 所在域解析`() {
        assertEquals(
            "https://api.example.com/updates/campus-1.23-T1.apk",
            UpdateLogic.resolveUrl("https://api.example.com", "updates/campus-1.23-T1.apk"),
        )
        assertEquals(
            "https://api.example.com/updates/campus-1.23-T1.apk",
            UpdateLogic.resolveUrl("https://api.example.com/", "/updates/campus-1.23-T1.apk"),
        )
    }

    @Test
    fun `绝对地址原样用 支持放别的 CDN`() {
        val u = "https://cdn.example.com/campus.apk"
        assertEquals(u, UpdateLogic.resolveUrl("https://api.example.com", u))
    }

    @Test
    fun `清单地址跟着 App 的服务器走`() {
        assertEquals("https://api.example.com/updates/manifest.json",
            UpdateLogic.manifestUrl("https://api.example.com"))
        assertEquals("https://x.trycloudflare.com/updates/manifest.json",
            UpdateLogic.manifestUrl("https://x.trycloudflare.com/"))
    }

    // ---------------------------------------------------------------- 校验和

    @Test
    fun `sha256 算的是大家公认的那个值`() {
        // 定值用例：算错了整个校验环节就是摆设
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateLogic.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `校验和比对要忽略大小写和空白`() {
        assertTrue(UpdateLogic.shaMatches(" ABC123 ", "abc123"))
        assertFalse(UpdateLogic.shaMatches("abc123", "abc124"))
    }

    @Test
    fun `文件摘要和内存摘要结果一致`() {
        val f = java.io.File.createTempFile("upd", ".bin")
        try {
            f.writeBytes(ByteArray(200_000) { (it % 251).toByte() })
            assertEquals(UpdateLogic.sha256Hex(f.readBytes()), UpdateLogic.sha256HexOf(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `没开安装权限时要告诉用户去哪儿开`() {
        assertNull(UpdateLogic.installPermissionHint(true))
        val hint = UpdateLogic.installPermissionHint(false)!!
        assertTrue("要指明方向，不能只说「请开启权限」", hint.contains("安装未知应用"))
    }

    @Test
    fun `大小显示别显示成一行数字`() {
        assertTrue(UpdateLogic.humanSize(7_222_304).endsWith("MB"))
        assertTrue(UpdateLogic.humanSize(512_000).endsWith("KB"))
    }

    // ------------------------------------------------ 进入 App 的自动检查（2026-09-17 新增）

    @Test
    fun `进 App 就该知道有没有新版本_不能被6小时节流挡住`() {
        val now = 1_700_000_000_000L
        // 刚查过 10 分钟的，按旧规则会被挡住（6 小时节流）→ 用户就永远不知道有新版
        assertTrue("进 App 的检查被节流挡住了", UpdateLogic.shouldCheckOnEntry(now - 10 * 60_000, now))
        assertTrue(UpdateLogic.shouldCheckOnEntry(0, now))
        // 但连续开关 App（几秒内）不该重复打请求
        assertFalse(UpdateLogic.shouldCheckOnEntry(now - 5_000, now))
    }

    @Test
    fun `一个版本只提醒一次_点过以后再说就不再打扰`() {
        val m = UpdateManifest(versionCode = 19, versionName = "1.39-T1")
        assertTrue("有新版本就该提醒", UpdateLogic.shouldNotify(m, 18, skippedCode = 0, notifiedCode = 0))
        assertFalse("同一个版本已经提醒过了", UpdateLogic.shouldNotify(m, 18, 0, 19))
        assertFalse("用户点了以后再说，不该再弹", UpdateLogic.shouldNotify(m, 18, 19, 0))
        assertFalse("已经是最新了", UpdateLogic.shouldNotify(m, 19, 0, 0))
    }

    @Test
    fun `我的页那行提示_版本追平后自己消失`() {
        val h = UpdateLogic.availableHint(18, "1.38-T1", 19, "1.39-T1")
        assertTrue("没说清新版本号：$h", h != null && h.contains("1.39-T1"))
        assertNull("版本追平了还显示有新版本", UpdateLogic.availableHint(19, "1.39-T1", 19, "1.39-T1"))
        assertNull(UpdateLogic.availableHint(19, "1.39-T1", 0, null))
    }

    /** 同上，钉在纯函数上：manual = 忽略「以后再说」这笔记忆 */
    @Test
    fun `decide 手动模式忽略 skipped`() {
        val m = UpdateManifest(versionCode = 23, versionName = "1.44-T1", url = "/updates/x.apk")
        assertTrue(
            "自动模式：点过「以后再说」就安静",
            UpdateLogic.decide(22, m, skippedCode = 23, manual = false) is UpdateLogic.Decision.UpToDate,
        )
        val d = UpdateLogic.decide(22, m, skippedCode = 23, manual = true)
        assertTrue("手动模式：必须报有新版本", d is UpdateLogic.Decision.Optional)
    }
}
