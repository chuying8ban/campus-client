package top.ccbase.campus.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「外观」对用户说过的话，代码必须让它们为真 —— 这三条只能扫源码守住：
 *
 * ① **背景图只存本机、不上传**（README 与页内都这么写了）：外观相关文件里
 *    不许出现任何网络调用。写成承诺却留着上传路径，就是"明文不离开手机"那类假声明。
 * ② **不申请任何存储权限**：选图走系统照片选择器（`PickVisualMedia`），
 *    所以清单里存储类权限必须**保持 0 条** —— 这条路上"不申请"才是正确答案。
 * ③ **设了背景图要真的看得见**：页面不许自己再画一层不透明的整页底（`C.bg`），
 *    否则就是把 Theme 里的背景层整个盖住，表现为"换了背景图但哪一页都没变"。
 * ④ **整屏浮层不许用"页面底"**：浮层存在的意义是盖住下面那页，所以它的底必须**恒不透明**；
 *    用 `pageSurface` 就变成"设过背景时底一个像素都不画"，下层那页的字会整篇透上来
 *    （2026-09-30 用户截图抓到的外观面板 bug，同一类的还有反馈页与后台页）。
 *    这条规则按 **CampusApp 实际挂载谁**来查，不靠人记名单 —— 新挂一个整屏浮层就自动被查。
 */
class AppearanceGuardTest {

    private val root = File("src/main")

    private fun read(rel: String): String {
        val f = File(root, rel)
        assertTrue("找不到 $rel（测试的工作目录变了？）", f.isFile)
        return f.readText()
    }

    private val appearanceFiles = listOf(
        "java/top/ccbase/campus/ui/me/AppearanceScreen.kt",
        "java/top/ccbase/campus/ui/theme/Background.kt",
        "java/top/ccbase/campus/ui/theme/Theme.kt",
    )

    @Test
    fun `背景图不出网_外观相关文件里不许有网络调用`() {
        val forbidden = listOf(
            "Net.", "CampusApi", "okhttp", "OkHttp", "HttpURLConnection",
            "http://", "https://", "java.net.URL",
        )
        val bad = mutableListOf<String>()
        appearanceFiles.forEach { rel ->
            val text = read(rel)
            text.lines().forEachIndexed { i, line ->
                val code = codeOnly(line)
                forbidden.forEach { token ->
                    if (code.contains(token)) bad += "$rel:${i + 1}  含有「$token」  ${code.trim().take(70)}"
                }
            }
        }
        assertTrue(
            "背景图只存本机、不上传 —— 外观相关文件里不许出现网络调用：\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    /**
     * 只留**代码**部分：KDoc 行（`*` 开头）、整行 `//`、行尾注释都去掉。
     *
     * 为什么必须剥：这些文件里专门写着"这一整块代码没有 `Net`/`CampusApi` 之类的引用"——
     * 那是**说明**，不是调用。不剥注释，这条守卫会对着自己的注释报错（第一次就踩了）。
     */
    private fun codeOnly(line: String): String {
        val t = line.trimStart()
        if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return ""
        return line.substringBefore("//")
    }

    @Test
    fun `不申请任何存储权限`() {
        val manifest = read("AndroidManifest.xml")
        val storagePerms = listOf(
            "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE",
            "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED",
        )
        val hit = storagePerms.filter { manifest.contains(it) }
        assertTrue(
            "选图走系统照片选择器，不需要也不该申请存储权限；清单里出现了：$hit",
            hit.isEmpty(),
        )
        // 反向确认：这条路上用的确实是系统选择器，而不是自己拿权限去读相册
        assertTrue(
            "「外观」页必须用系统照片选择器（PickVisualMedia）选图",
            read("java/top/ccbase/campus/ui/me/AppearanceScreen.kt").contains("PickVisualMedia"),
        )
    }

    @Test
    fun `页面不许自己画不透明整页底`() {
        val uiRoot = File(root, "java/top/ccbase/campus/ui")
        assertTrue("找不到 ui 目录（测试的工作目录变了？）", uiRoot.isDirectory)
        val bad = mutableListOf<String>()
        uiRoot.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Theme.kt" }   // Theme.kt 自己就是 pageSurface 的定义处
            .forEach { f ->
                f.readLines().forEachIndexed { i, raw ->
                    val code = raw.substringBefore("//")
                    if (code.contains(".background(C.bg)")) bad += "${f.name}:${i + 1}  ${code.trim()}"
                }
            }
        assertTrue(
            "整页底要用 `Modifier.pageSurface`：有背景图时它会让出底色，\n" +
                "直接 .background(C.bg) 会把背景层盖住（设了背景图却看不见）：\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun `整屏浮层必须用 overlaySurface_不许用 pageSurface`() {
        val campusApp = read("java/top/ccbase/campus/ui/CampusApp.kt")
        val mounted = overlayScreens(campusApp)
        // 反向确认：正则/写法变了就当场喊，别让这条守卫变成"永远绿的空转"
        val mustSee = listOf("SafetyScreen", "AppearanceScreen", "CrawlScreen", "PlanScreen",
            "PermissionsScreen", "FeedbackScreen")
        assertTrue(
            "没能从 CampusApp 的 `if (showXxx)` 块里认出这些整屏浮层：${mustSee - mounted}（挂载写法变了？）",
            mounted.containsAll(mustSee),
        )

        // 豁免：**自绘半透明遮罩 + 不透明卡片**的那一类（遮罩本来就该透）——
        // PermissionsScreen / DiagOverlay 用的是 background(Color(0xB3000000)) + surface 卡片。
        val selfDrawnScrim = setOf("PermissionsScreen")

        val uiRoot = File(root, "java/top/ccbase/campus/ui")
        val bad = mutableListOf<String>()
        mounted.filterNot { it in selfDrawnScrim }.forEach { name ->
            val f = uiRoot.walkTopDown().firstOrNull { it.name == "$name.kt" }
            if (f == null) {
                bad += "$name：CampusApp 挂载了它，但 ui/ 下找不到源文件"
                return@forEach
            }
            val text = f.readText()
            if (!text.contains(".overlaySurface")) {
                bad += "$name：整屏浮层的底必须用 .overlaySurface（恒不透明）"
            }
            if (text.contains(".pageSurface")) {
                bad += "$name：用了 .pageSurface —— 设过背景的机器上会透出下层那页的字（2026-09-30 真机 bug）"
            }
        }
        assertTrue(
            "整屏浮层的底必须恒不透明（Theme.overlaySurface）；pageSurface 是有背景图时的\"透明底\"，\n" +
                "浮层用它就会让下面那页的字与浮层的字叠在一起：\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    /**
     * 从 CampusApp 里抠出**由 `if (showXxx) { … }` 挂载的整屏浮层**。
     *
     * 为什么按花括号配平扫、而不是一句正则了事：`when (tab)` 那些**页面**是直接调用的，
     * 它们用 `pageSurface` 才是对的；一句宽正则会把 LibraryScreen / GrabScreen 这些页面
     * 也当成浮层（第一次写就是这样，修好的代码反而被判红）。浮层的判据是"挂在 `showXxx` 开关下"。
     * 条件里带别的东西也算（例：`if (ready && showSafety)` —— 这种写法出现过）。
     */
    private fun overlayScreens(campusApp: String): Set<String> {
        val lines = campusApp.lines()
        val found = mutableSetOf<String>()
        val showIf = Regex("""^\s*if \(.*\bshow[A-Z]\w*""")
        val call = Regex("""\b(\w+Screen)\(""")
        var i = 0
        while (i < lines.size) {
            if (!showIf.containsMatchIn(lines[i])) { i++; continue }
            var depth = 0
            var j = i
            while (j < lines.size) {
                val code = lines[j].substringBefore("//")
                depth += code.count { it == '{' } - code.count { it == '}' }
                call.findAll(code).forEach { found += it.groupValues[1] }
                j++
                if (depth <= 0) break          // 这个 if 块合上了
            }
            i = if (j > i) j else i + 1
        }
        return found
    }
}
