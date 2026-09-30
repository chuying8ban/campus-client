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
}
