# 03 · App 主题改造方案

> 关联代码：`ui/theme/DynamicBackground.kt`（249 行）、`ui/theme/DynThumb.kt`、`ui/theme/Theme.kt`、`ui/theme/UserMedia.kt`、`ui/me/ThemeSection.kt`（871 行）、`ui/me/AppearanceScreen.kt`（903 行）。

## 0. 一条总原则

**App 内与桌面小组件必须是同一套视觉。** 同一个主题 id，在 App 里看到的和桌面上看到的要是同一幅画，只是 App 里流畅、桌面上慢。做法是共用 `ShaderPresets` 的参数表与 GLSL 源码，只是 App 走实时渲染、桌面走烘焙。任何"App 一套素材、桌面另一套"的做法都会在用户截图对比时暴露。

---

## 1. 现状与三个具体问题

**① 图库是 90 宫格，用户挑不过来。**
`DYN_PRESETS = DynKind.entries × DYN_TINTS` = 15 × 6 = **90 款**（`DynamicBackground.kt` 第 240 行，注释还写着"12 种 × 6"，已过期）。这 90 款里有一半是同一幅画换色，用户滑两下就放弃，且会觉得"都是差不多的东西"。

**② 实时绘制占用 UI 线程。**
`DynamicBackground` 用 Compose `Canvas` + `withFrameNanos`，每帧在 UI 线程跑一遍 `DynPainter.frame()`。虽然加了"连续 20 帧掉速就降到 30fps"的自适应（第 107~134 行），但那只是止损——**根因是背景和列表在抢同一帧的预算**。上 GL 之后背景有自己的线程，这个问题从架构上消失。

**③ 缩略图和正片不是同一套实现。**
`DynThumb.kt` 用 `DynPainter.frame(c, kind, 3.7f, ...)` 画一张静态缩略。换了新内核后，如果缩略还走老画笔，图库里就会"缩略是一个样、点进去是另一个样"。

---

## 2. 实时 GL 集成（App 内 60fps）

### 2.1 容器选择

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| `GLSurfaceView` + `AndroidView` | 独立渲染线程、成熟、掉帧不影响 Compose | z-order 与裁剪要靠 `setZOrderOnTop` 调；部分 ROM 上圆角裁剪失效 | **主选**（背景铺满、不需要裁剪） |
| `TextureView` | 能参与 Compose 变换/圆角/动画 | 多一次 GPU→CPU→GPU 拷贝，功耗高 15~20% | 备选，用于需要裁圆角/做转场的场景 |

放在页面最底层：`Box { GlBackground(...); content }`，`glSurface.setZOrderMediaOverlay(false)`。

### 2.2 生命周期与暂停（省电的关键）

```kotlin
// 三条状态必须都处理，缺一条就是"后台还在烧电"
onResume / onPause          → glSurface.onResume() / onPause()
Compose 可见性（LazyColumn 滚出、页面被覆盖） → requestRender 停
屏幕熄灭（ACTION_SCREEN_OFF） → 停渲染线程
```

绘制用 `RENDERMODE_CONTINUOUSLY` 但内部自己做节流：以 vsync 为准（`eglSwapBuffers` 本身会阻塞到刷新），**不要再加 delay**（现在的 33ms 定长 tick 正是"一顿一顿"的来源，注释里已经记过这个教训）。

### 2.3 尺寸与密度

- 渲染分辨率按 `min(1.0, 1080f / height)` 缩放：1080p 屏 1.0，1440p 屏 0.75，既保住观感又省一半像素。
- `onSurfaceChanged` 里重建 framebuffer，**不要**在每帧里判断尺寸变化。
- 高刷屏（120Hz）要限制到 60fps：`eglSwapInterval(1)` 在两倍刷新率下会跑 120fps，功耗翻倍。用 `Choreographer` 每两帧提交一次，或设 `eglSwapInterval(2)`（设备行为不一致，优先用 Choreographer 计数）。

### 2.4 与滚动联动（App 独有的高级感）

App 内能做到而小组件做不到的两件事，值得做：

1. **视差**：列表滚动 `firstVisibleItemScrollOffset` → 背景 `uv.y += offset * 0.0008`（幅度上限 4%）。滚动时的"深度感"是壁纸级观感里最便宜的一档。
2. **转场**：从图库点选主题 → 新主题从点击位置以半径扩散切入（GL 里一个 `uReveal` uniform，0.45s），替换现在的硬切。

---

## 3. 图库结构重构（90 → 24 + 调色）

### 3.1 新结构

```
外观 → App 主题
├─ 精选（24 款，横滑大卡，真首帧预览）
│   ├─ 缓流 fluid：液态 / 丝绸 / 雾霭 / 极光 / 沙丘 / 水面折射 / 熔岩灯
│   ├─ 光晕 glow：地平线 / 云隙光 / 霓虹雾 / 单光源呼吸 / 焦外光斑 / 星云
│   └─ 静图微动 still：摄影 6 款（Ken Burns + 视差 + 颗粒）
├─ 调色（选中后进二级页：6 色系 + 明暗两档）  ← 替代现在的 90 宫格
├─ 动态壁纸（"设为壁纸"入口，见 §5）
└─ 经典（折叠区，15 款老的 DynPainter 款，标"经典"）
```

**为什么这样分**：用户挑的是"画面"，不是"参数"。先给 24 张一眼能选的画面，色系收进二级页——**选择成本从 90 降到 24，且不减少实际可选组合（24×6=144）**。

### 3.2 数据模型

```kotlin
// 新：一条主题 = 一个 shader preset，色系是可调参数而非独立条目
data class ShaderTheme(
    val id: String,            // "liquid"
    val name: String,          // "液态"
    val group: Group,          // FLUID / GLOW / STILL
    val tags: String,          // 搜索用："流动 液态 玻璃 极简"
    val defaultTint: Int,
    val animated: Boolean,     // 静态素材为 false，卡片上不打"动"角标
)
```

`ArtItem`（`ArtLibrary.kt`）已经有一个 `animated: Boolean` 字段，语义保留；新图库的条目要么是 `ShaderTheme`（程序化），要么是 `ArtItem`（服务器图）。**两条流合并展示，但筛选项分开**（程序化 / 图库）。

### 3.3 预览必须是"真帧"

- 程序化款：进入可见区域时用离线 GL 烘一帧 320×180 的缩略（一次，落盘缓存，key 带 shaderHash），**不要**用老 `DynPainter` 画。
- 图库款：用 `ArtItem.thumbUrl`。
- 卡片右下角标：`动` / `静` / `需下载`。用户 2026-10-18 明确要求过动态内容要标注。

### 3.4 搜索

保留现有按"标题/标签/id"匹配的本机搜索（`ArtRepo` 的做法），但 haystack 要把 `name + tags + group` 都放进去。搜索页不再展示 90 款，只展示 24 + 服务器条目。

---

## 4. spec 与存储迁移

现有 spec 前缀（都在 `DynamicBackground.kt` / `WidgetTheme.kt`）：

| 前缀 | 含义 | 新方案 |
| --- | --- | --- |
| `anim:<kind>:<argb>` | 老的 15 款程序化 | **保留可用**，归到「经典」折叠区 |
| `animphoto:<effect>` | 静图 + Ken Burns + 粒子 | 保留，升级参数（见 §6） |
| `preset:` / `photo` / `solid` | 静态主题 | 不动 |
| **`shader:<id>`**（新增） | 新内核程序化款 | 色系存在独立字段 `shaderTint`，或写成 `shader:liquid:ffa06bff` |

**迁移规则**：
- 老用户已选的 `anim:aurora:ffa06bff` **不改写**，界面显示为新款 + 提示"这款有新版本"，用户点才换。静默替换会让用户觉得"我的主题被换了"。
- 新装用户默认 `shader:liquid`。
- 落盘位置沿用现在的 `bgChoice` 偏好项，不新增迁移逻辑。

---

## 5. 动态壁纸（`DynWallpaperService`）

现状：它已经用 `SurfaceHolder` + 自己的绘制线程跑 `DynPainter.frame()`，是 App 里唯一真 60fps 的地方。

改造：

1. 引擎里加一条分支：主题是 `shader:` 款 → 用 GL 渲染到 wallpaper 的 Surface（`EGLSurface` 直接从 `SurfaceHolder.getSurface()` 建），而不是 `Canvas`。
2. 保留 `DynPainter` 分支给经典款。
3. 进入壁纸后不可见时（`onVisibilityChanged(false)`）必须停线程——这是壁纸耗电的第一大来源。
4. 壁纸侧的 `uRes` 要按屏幕真实像素（含导航栏区域），不能用 App 的渲染缩放。

---

## 6. 静图微动（`animphoto` 的升级）

现在是 `zoom = 1.03 + 0.03 * sin(t/3.2 * 2π)`——周期 **3.2 秒**，太快，像"呼吸急促"。改成：

| 参数 | 现在 | 改成 | 理由 |
| --- | --- | --- | --- |
| 推近周期 | 3.2s | **12s** | 壁纸级的推拉是"几乎察觉不到" |
| 缩放幅度 | ±3% | **±2%** | 幅度小才不晕 |
| 位移 | 1.2% / 1.0% | **0.6% / 0.5%**，周期 12s / 18s（不同频） | 双频叠加去掉"往复"感 |
| 前景粒子 | `DynPainter.particles` 0.6 强度 | 只在 `PETALS/SNOW/FIREFLIES` 三种保留，强度 0.35，速度 ×0.6 | 低强度前景粒子才像景深，不像贴纸 |
| 新增 | — | 颗粒 1.5% + 暗角 10% + 边缘虚化（径向模糊 2px） | 让照片有"镜头"味 |

边缘虚化用 Compose 做不了，得走 `RenderEffect`（API 31+）或直接用 GL 后处理；API <31 的设备跳过这一步，不要用 `RenderScript`（已弃用）。

---

## 7. 性能与耗电

| 项 | 预算 | 手段 |
| --- | --- | --- |
| App 内帧率 | 60fps（高刷屏锁 60） | 见 §2.3 |
| 背景线程占用 | 独立线程，不占 UI 线程 | GL 的固有优势 |
| 不可见 | 立即停 | 三条状态（§2.2） |
| 渲染分辨率 | 1080p 屏 1.0 / 1440p 屏 0.75 | 观感几乎无差，像素减一半 |
| 缩略图 | 每款只烘一次，落盘 | key 带 `shaderHash` |

**验收时必测**：连续滚动 3 分钟不出现掉帧（`Choreographer` 掉帧统计），以及息屏 10 分钟后 `dumpsys batterystats` 里本 App 的 `wake` 计数为 0。

---

## 8. 文件级改动清单

| 文件 | 改什么 |
| --- | --- |
| `ui/theme/DynamicBackground.kt` | 新增 `ShaderBackground`（`AndroidView` + `GLSurfaceView`）；`DynamicBackground` 保留为经典款；`DYN_PRESETS` 改为只服务「经典」区 |
| 新增 `ui/theme/ShaderThemes.kt` | 24 款 `ShaderTheme` 表 + 分组 + 搜索 |
| 新增 `ui/theme/GlBackgroundView.kt` | `GLSurfaceView` 封装：尺寸缩放、Choreographer 限帧、可见性、视差 uniform |
| `ui/theme/DynThumb.kt` | 增加 shader 分支（离线烘一帧），保留老分支 |
| `ui/me/ThemeSection.kt` | `WidgetBlock` 与 App 主题区都要接新图库；`MotionChips` 改成"色系"二级页 |
| `ui/me/AppearanceScreen.kt` | `AppThemePane` 重构为 精选 / 调色 / 壁纸 / 经典 四段；`DynSwatch` 改为真帧缩略 |
| `ui/theme/UserMedia.kt` | 不动（用户自己上传的图/视频逻辑保持） |
| `widget/DynWallpaperService.kt` | 加 GL 分支（见 §5） |

---

## 9. 坑

1. **`GLSurfaceView` 在 Compose 的 `AndroidView` 里被重建**：`key` 要写死，且 `onRelease` 里必须 `onPause()` + 释放 EGL，否则退出页面后 GPU 上下文泄漏，第二次进来黑屏。
2. **`setZOrderOnTop(true)` 会让背景盖住文字**——只允许 `setZOrderMediaOverlay`，且必须在 `AndroidView.factory` 里设置（晚了不生效）。
3. **GL 线程里不能碰 Compose state**：所有 uniform 通过 `AtomicInteger/AtomicReference` 传，不要 `mutableStateOf`。
4. **`rememberDynTime` 的教训别丢**：任何"定长 delay 循环"都会和 vsync 错位。新代码一律以 `eglSwapBuffers`/`Choreographer` 为准。
5. **缩略图缓存不能存 GL Bitmap 之外的引用**：`ArtRepo.thumbs` 是 `LruCache<String, Bitmap>`，塞进去的 Bitmap 不能被 recycle（见 02 §9 第 3 条）。
6. **旧款不许删**：`anim:` 那 15 款有用户在用，删了等于把人家桌面变黑（`DynPainter` 里已有"很多动态主题根本用不了、用了之后变成纯黑"的历史教训）。
