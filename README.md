[English](README_EN.md) | 中文

# 校园课表与学习客户端（Android · 中国石油大学（北京）克拉玛依校区）

一个学生自用的 Android 客户端：课表、早晚自习、任务、公共学习资源、统计看板，外加一个课位余量监控。数据以本机为主（Room 落库），联网部分连的是**中国石油大学（北京）克拉玛依校区教务系统**（`eams.cupk.edu.cn`，只读）与作者自己的一个后端服务（不进本仓库）。

> **非官方项目。** 由一名学生个人开发，和学校教务处及任何学校官方系统不存在隶属、授权或背书关系；使用它可能不符合你所在学校对教务系统的使用规定，风险自负。仓库里只有客户端源码：不带任何个人数据、不带作者的后端地址、不附安装包。

## 功能

底部七格：

| 页面 | 做什么 |
| --- | --- |
| 今日 | 今天的课 + 早晚自习 + 今日任务，带课前提醒开关 |
| 课表 | 周视图与当天课程；从教务系统只读导入自己的课表 |
| 抢课 | 课位余量监控、到点通知、可选课程清单（清单与网页版同一个服务） |
| 学习 | 阶段切换、任务卡、学习步骤与进度；顶部是「AI 规划学习计划」入口 |
| 学习库 | 公共资源目录：按课程 / 按类型挑链接，挑中的落成自己清单里的一条任务 |
| 看板 | 统计卡 + 近 14 天图 + 里程碑 + 自检 |
| 我的 | 账户、密码管理、网络自检、安全与隐私、授权与白名单、检查更新、提建议 |

另外：课前自动提醒、上课自动静音（都要系统权限，App 里有一页专门带着去开）；因为走旁加载安装，内置了应用内更新（查版本 → 下载 → 拉起系统安装器）；「我的」里还挂了站点后台管理页（地址随 `apiBase` 走）。

**抢课那一格说清楚**：它做的是监控与提醒——轮询某门课的余量、到点通知你，真正的选课动作还是你自己在教务系统里点。重建课程清单、改教务凭据这类事要回网页版，App 里那条提示就是干这个的。

## 构建

需要 JDK 17 和 Android SDK（版本以 `app/build.gradle` 为准）。

**必须自己给后端地址**，不给就构建失败——这是故意的：留一个默认值等于让默认包指向别人的服务器，这种错在编译期看不出来，只能等用户装上才发现。

```bash
# 命令行
./gradlew assembleRelease -PapiBase=https://your-backend.example.com
```

```properties
# 或者写进 local.properties（已 gitignore）
apiBase=https://your-backend.example.com
```

release 签名用根目录的 `campus.keystore`（不进版本库），四个参数可以用环境变量覆盖：`CAMPUS_KEYSTORE` / `CAMPUS_STORE_PASS` / `CAMPUS_KEY_ALIAS` / `CAMPUS_KEY_PASS`；也可以写在 `local.properties`（`campusKeystore` / `campusStorePass` / `campusKeyPass`）。**keystore 文件和口令都不在仓库里，所以 release 包必须用你自己的**；开发阶段跑 `assembleDebug` 即可，debug 变体走默认调试签名。

你 clone 下来想直接用教务那半部分：`eams.cupk.edu.cn` 是源码里的默认值，用你的学号密码登录即可，**密码不会以明文离开手机**（先用服务端下发的 salt 做一次 SHA-1，再提交哈希）。

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

全部跑在 JVM 上（Robolectric + Compose 渲染冒烟），不需要真机或模拟器。测得的数字从 `app/build/test-results/testDebugUnitTest/*.xml` 汇总，不看屏显。

**落地那一次的实跑记录**（不是示意输出，是从命令行原样粘的；跑的就是本仓 213 个受版本控制的文件）：

```
$ ./gradlew :app:testDebugUnitTest --offline --rerun-tasks
BUILD SUCCESSFUL in 2m 43s
→ 85 classes / 761 tests / 0 failures / 0 errors / 0 skipped

$ ./gradlew :app:assembleRelease --offline --rerun-tasks
BUILD SUCCESSFUL in 1m 32s
51 actionable tasks: 51 executed
→ app-release.apk 7601768 B   (sha256 71bc278c06c85da645e2f90865dcbab5dc7eaf3a056e90320dfccdd8f29fc191)
```

那次 release 包用的是**一次性 demo keystore**（本机临时生成、随机口令、跑完即删），只证明构建链通，**不是发布包**——真发布要用你自己的 keystore 签。测试类数与用例数随开发变化，以上数字属于那一次记录。

## 技术栈

Kotlin + Jetpack Compose（Material 3）+ Room + kotlinx.serialization，Gradle 构建，minSdk 26 / targetSdk 34；确切版本号看 `app/build.gradle` 和 `gradle/wrapper/gradle-wrapper.properties`。

## 目录

```
app/src/main/java/top/ccbase/campus/
├── alarm/    课前提醒、自动静音、抢课监控与开机后重排
├── data/     本地库（Room）、种子导入、学习计划模板
├── domain/   课表 / 今日 / 周视图 / 任务 / 看板 等纯逻辑
├── net/      接口客户端（含教务系统只读客户端）与传输层
├── ui/       各页面（Compose）
└── update/   应用内更新
```

## 内置数据与隐私

`app/src/main/assets/` 里两份文件随包走：`seed.json`（节次、自习时段，外加合成演示的课程 / 任务 / 学习步骤 / 资源 / 清单 / 里程碑）与 `plan_templates.json`（计划模板）。

App 启动时**只把节次与自习时段灌进本地库**：导入前会把课程、任务、学习步骤、资源、清单、里程碑显式清空（见 `CampusApplication.kt` 里那个 `full.copy(...)`），所以文件里那几段合成内容不会落进任何人的库——每个人的任务由自己的课表推出来，谁还没同步到自己的计划，界面就明说"还没有你的计划"，宁可空着也不拿别人的数据凑。

那几段合成演示数据用来撑住界面与逻辑测试：任务文本是「演示条目NNN：…」这样的占位语，人名是「讲师甲」这类虚构称呼，院系与校区是「示例大学（北京）示例市校区」。演示数据的课程标题里会看到公开慕课平台上的课程名和来源校名（如中国大学MOOC 上各高校的课），那是为让目录看起来像真的，都是公开信息，不是本机构的课程数据。

教师姓名、教师工号、行政班号、本人课表这类**个人可识别数据在仓库里是零命中**：清理时把真实取值换成了合成值（教师一…教师十二、`XXXXXXXXXX` 之类），并有一道闸门反复扫过整棵树来把这件事固定下来。`tools/privacy_scan.py` 是发行包侧的同类检查，可以拿它扫你打出来的 APK：

```bash
python3 tools/privacy_scan.py dist/your-build.apk
```

## 来源与边界

- 目标系统：中国石油大学（北京）克拉玛依校区教务系统（`eams.cupk.edu.cn`），只读读取当前登录用户自己的课表数据。
- 本仓库是这个工具的 Android 一侧；同一作者的网页版（Python/FastAPI）不在仓库内，`apiBase` 指向的就是它。/admin/ 那页也由它提供。
- 参考了什么、哪些是通用做法、哪些不能做：见 [docs/PRIOR-ART.md](docs/PRIOR-ART.md)。
- 课表规则（周次换算、streak 口径等）是与网页版逐项对齐后写进测试的，不是凭空定的——两边实现有偏差测试会红。

## 许可

MIT，见 [LICENSE](LICENSE)。
