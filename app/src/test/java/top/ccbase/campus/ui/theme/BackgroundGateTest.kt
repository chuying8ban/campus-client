package top.ccbase.campus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「切换背景看不出区别」这条只有**画面级**的指标守得住 —— 所以这里不看单个 token，
 * 而是把每一款背景铺满屏、叠上它反算出来的那层遮罩，量整个画面：
 *
 *  一、**可读性**：每款预设的两个极值像素处，`txt/txt2/txt3` 都要过阈值（与 `ThemeTokenTest` 同口径）。
 *  二、**可辨识性**：每款与纯色底的平均通道差、任意两款之间的平均通道差都要够大。
 *      这是 2026-09-30 那次返工的机器判据 —— 肉眼能分辨大约要 10/255，
 *      而老版本（两段深色 + 固定遮罩 0.82）实测**六款里最接近的两款只差 2/255**。
 *  三、**背景图按图自己的明暗来算**：纯白图要压得住（字看得清），深色图几乎不压（图看得清）。
 *      老版本一张深色风景照也硬压 0.82，只剩 18% 透出来，看着就是一片深灰。
 *
 * 阈值取在实测值下方留一档余量（深色 vs 纯色实测 17.7 / 两两 9.8；浅色 12.8 / 3.2），
 * 这样"确实看得见"是守住的，但微调某一款的颜色不会让测试无缘无故翻红。
 */
class BackgroundGateTest {

    private val n = 32

    private fun field(preset: BgPreset, palette: CampusPalette): List<List<Color>> {
        val themed = forTheme(preset, dark = palette.bg.luminance() <= 0.5f)
        val scrim = scrimFor(themed, palette)
        val (hi, lo) = extremesOf(themed)
        return (0 until n).map { j ->
            (0 until n).map { i ->
                over(presetPixel(themed, i.toFloat() / (n - 1), j.toFloat() / (n - 1)), palette.bg, scrim)
            }
        }.also {
            // 顺带把可读性也验了：反算出来的遮罩必须真的让两个极值都达标
            val (t1, t2, t3) = textThresholds(palette)
            listOf(hi, lo).forEach { src ->
                val comp = over(src, palette.bg, scrim)
                assertTrue(
                    "「${preset.name}」在 $scrim 的遮罩下文字不达标：txt=%.2f txt2=%.2f txt3=%.2f"
                        .format(contrastRatio(palette.txt, comp), contrastRatio(palette.txt2, comp), contrastRatio(palette.txt3, comp)),
                    contrastRatio(palette.txt, comp) >= t1 &&
                        contrastRatio(palette.txt2, comp) >= t2 &&
                        contrastRatio(palette.txt3, comp) >= t3,
                )
            }
        }
    }

    /** 两个画面的平均通道差（0~255）。比"只看峰值"更贴近肉眼：它还管渐变走向与光晕位置。 */
    private fun meanDiff(a: List<List<Color>>, b: List<List<Color>>): Float {
        var sum = 0f
        for (j in 0 until n) {
            for (i in 0 until n) {
                val x = a[j][i]
                val y = b[j][i]
                sum += maxOf(
                    abs(x.red - y.red), abs(x.green - y.green), abs(x.blue - y.blue),
                ) * 255f
            }
        }
        return sum / (n * n)
    }

    private fun solid(p: CampusPalette) = List(n) { List(n) { p.bg } }

    private fun assertVisible(palette: CampusPalette, theme: String, vsSolid: Float, pairMin: Float) {
        val solidField = solid(palette)
        val fields = BgPresets.associateWith { field(it, palette) }

        fields.forEach { (preset, f) ->
            val d = meanDiff(f, solidField)
            assertTrue(
                "「${preset.name}」在${theme}主题下与纯色底只差 %.1f（要 ≥ %.1f）—— 用户会看不到任何背景".format(d, vsSolid),
                d >= vsSolid,
            )
        }

        for (i in BgPresets.indices) {
            for (j in i + 1 until BgPresets.size) {
                val a = BgPresets[i]
                val b = BgPresets[j]
                val d = meanDiff(fields.getValue(a), fields.getValue(b))
                assertTrue(
                    "「${a.name}」与「${b.name}」在${theme}主题下只差 %.1f（要 ≥ %.1f）—— 这就是 2026-09-30 那句「切换背景根本看不出区别」".format(d, pairMin),
                    d >= pairMin,
                )
            }
        }
    }

    @Test
    fun `深色主题下每款都看得出区别_且互相分得开`() = assertVisible(DarkPalette, "深色", vsSolid = 12f, pairMin = 6f)

    @Test
    fun `浅色主题下每款都看得出区别_且互相分得开`() = assertVisible(LightPalette, "浅色", vsSolid = 8f, pairMin = 2.5f)

    @Test
    fun `遮罩按预算反算_不再是无脑压到底`() {
        // 老版本对所有背景一律 0.82。反算之后深色主题下最"轻"的那款应当明显低于它 ——
        // 这条守住"能不加就不加"，防止哪天又被人改回一个常数。
        val scrims = BgPresets.map { scrimFor(forTheme(it, dark = true), DarkPalette) }
        assertTrue("遮罩全都 ≥0.5，等于又压死了一层（实测最轻的几款只有 0.1~0.4）", scrims.min() < 0.3f)
        assertTrue("遮罩不该出现 >0.96 这种把背景抹平的值", scrims.max() <= 0.96f)
        // 浅色主题下渐变自己就落在浅底能承受的带子里，几乎不用遮罩
        val lightScrims = BgPresets.map { scrimFor(forTheme(it, dark = false), LightPalette) }
        assertTrue("浅色主题不该再压一层厚遮罩（实测 ≈0.02）", lightScrims.max() < 0.2f)
    }

    @Test
    fun `背景图按图自己的明暗算_亮图压得住_暗图几乎不压`() {
        // 纯白图 = 最坏情况，必须压得住（和 SCRIM_ALPHA 那张表一个口径）
        val white = Color.White to Color.White
        listOf(DarkPalette, LightPalette).forEach { pal ->
            val a = photoScrim(white, pal)
            val comp = over(Color.White, pal.bg, a)
            val (t1, t2, t3) = textThresholds(pal)
            assertTrue(
                "纯白背景图没有压到可读（alpha=%.2f）：txt=%.2f txt2=%.2f txt3=%.2f"
                    .format(a, contrastRatio(pal.txt, comp), contrastRatio(pal.txt2, comp), contrastRatio(pal.txt3, comp)),
                contrastRatio(pal.txt, comp) >= t1 &&
                    contrastRatio(pal.txt2, comp) >= t2 &&
                    contrastRatio(pal.txt3, comp) >= t3,
            )
        }

        // 深色风景照（最亮处也才 #3A3A3A）在深色主题下**几乎不该压**：老版本一律 0.82 会把它洗成一片灰
        val darkPhoto = Color(0xFF3A3A3A) to Color(0xFF101010)
        val a = photoScrim(darkPhoto, DarkPalette)
        assertTrue("深色照片被压到了 %.2f —— 照片本身还看得见吗（老版本一律 0.82）".format(a), a < 0.3f)

        // 量不出极值（旧版本装过、或量失败）时退回最坏情况，不能变成"不压"
        assertTrue("缺极值时必须退回 SCRIM_ALPHA", photoScrim(null, DarkPalette) == SCRIM_ALPHA)
    }

    /** 极值采样本身也得守：它必须覆盖到光晕中心（否则"最亮像素"会比真机暗，遮罩就压不够） */
    @Test
    fun `极值采样要覆盖到画面的最亮处`() {
        BgPresets.forEach { p ->
            val themed = forTheme(p, dark = true)
            val (hi, lo) = extremesOf(themed)
            assertTrue("「${p.name}」的最亮像素不该比基色还暗", relLum(hi) >= relLum(themed.base.first()) - 1e-4f)
            assertTrue("「${p.name}」的最亮与最暗不能是同一个点（否则采样白做了）", relLum(hi) > relLum(lo))
            val glowPeakLum = themed.glows.maxOf { g -> relLum(presetPixel(themed, g.cx, g.cy)) }
            assertTrue(
                "「${p.name}」的光晕中心比采样到的极值还亮 —— 采样格太疏，遮罩会压不够",
                relLum(hi) >= glowPeakLum - 0.02f,
            )
        }
    }
}
