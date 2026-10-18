package top.ccbase.campus.school

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入 WebView 的那段学籍解析脚本，本体是字符串 —— 编译期查不出错（`${...}` 漏替换、
 * 路径写错、少了 DOMParser），在手机上才会表现为"卡片永远空白"。
 * 这里把能静态查的几条钉住：
 *  ① 请求路径是教务的学籍页（不是 `/student/info` 那种 404 地址）；
 *  ② 没有残留的模板占位符（`${'$'}{PROFILE_PATH}` 忘了 replace 就会把这段原文发给 WebView，
 *     JS 直接语法错误，页面里什么都不会发生）；
 *  ③ 解析靠 DOMParser + 文本节点顺序，不依赖 class 名。
 */
class ShuweiProfileScriptTest {

    private val js = ShuweiExtraction.profileScript

    @Test
    fun `请求的是真实存在的学籍页`() {
        assertTrue(js.contains("fetch('/student/for-std/student-info'"))
    }

    @Test
    fun `没有残留的模板占位符`() {
        // 只看占位符本身（`${...}`）；正则里的 `$`（行尾锚点）是合法的
        assertFalse("占位符没替换掉就会把这段原文发给 WebView", js.contains("${'$'}" + "{"))
        assertFalse(js.contains("PROFILE_PATH"))
    }

    @Test
    fun `解析用文本节点顺序 不靠 class 选择器`() {
        assertTrue(js.contains("DOMParser"))
        assertTrue(js.contains("createTreeWalker"))
        assertFalse("教务换皮肤就该失效的选择器，不要用", js.contains("querySelector"))
    }

    @Test
    fun `六个字段都在`() {
        listOf("college", "major", "adminClass", "counselor", "status", "campus").forEach {
            assertTrue("缺字段 $it", js.contains("$it:"))
        }
    }
}
