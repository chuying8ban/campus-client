# school-test16：回到系统 WebView 小包主线

## 给用户的说明

这个测试版回到 Android 系统 WebView，不再把 Gecko 作为常规更新内核。保留页面初始化前的安全诊断和 App 内认证子窗口；没有把这些诊断当作登录成功判据。

东北农业大学 MFA 空白问题仍未解决，也没有真机验证修复。若仍遇到空白，请在同一次认证页面内生成脱敏诊断，使用“复制诊断”或“系统分享”反馈观察器状态、执行标记、固定 JS 错误类型与资源错误。当前“同意并发送给开发者”不包含这些新增本机信号，不能替代复制/分享。不要发密码、验证码、Cookie、完整页面或带敏感参数的地址。

版本 2.1.5-school-test16 / code9016，高于 Gecko test15 / code9015；测试包名和签名一致，本机生成数据库结构与 Gecko 隔离版一致，具备覆盖升级的静态兼容条件。请直接覆盖安装，不卸载、不清数据。真实覆盖安装与数据保留仍需真机确认。Gecko 私有浏览器会话不会因为数据库兼容而自动转成 WebView 会话，可能需要重新登录；不要把重新登录误判为课表丢失。

本轮没有删除数据库、执行清会话操作或改会话策略。原有认证页面关闭时清理该认证会话的行为仍存在，不代表永久保留浏览器登录态。正式版本 code89 未变。

## 本机交付与验证

- APK：`/Users/a29285/ChenC/projects/study-native/evidence-school-test16/campus-2.1.5-school-test16.apk`
- 包名：`top.ccbase.campus.privacytest`
- 版本：`2.1.5-school-test16` / `9016`
- 大小：7695172 bytes（7.339 MiB）
- SHA256：`5b773c46a1a4adaf5abd8d4c6d168fb922c8ad4eb90ff302682640f6fee48ba7`
- apksigner verify 成功；证书 SHA256：`6eb42692c49f799bc198cc8a4dc1300e9d9c2cee0a39190d0bed4afcbe1688ab`，与只读复查 test15 APK 完全一致。
- 重新生成主 app release CampusDb_Impl.java，与隔离版 release CampusDb_Impl.kt 实查 identity `f28d5b1d215b76f4c8d9489ff75abbda`、legacy `42b6765ac9f97ba7ca0512f455834512`，排序 CREATE TABLE/INDEX SQL 相同；CampusDb.kt 字节相同。
- 12 个测试类，45 tests，failures/errors/skips 全为 0：迁移 guard/evidence、CampusDb、EamsLocalImport、GenericSchoolImport、TimetableContract、SchoolImportPolicy、SchoolTestChannel、AuthDocumentStart、AuthBrowserCompatibility/Windows、AuthResourceDiagnostics。
- 离线 releaseRuntimeClasspath 报告无 Gecko/Mozilla 依赖；APK ZIP 无 Gecko/libxul/Mozilla 项，DEX 无 GeckoView 类或 NeauGeckoActivity；DEX 检查测试 API 与更新清单路径存在。
- adb devices 无设备。未做覆盖安装、数据保留、登录/MFA/课表导入的真机验收。
- Gradle BUILD SUCCESSFUL。有既有 deprecated API 警告；Robolectric 临时字体目录退出清理报 DirectoryNotEmptyException，但测试 XML 无失败，构建退出码 0。本轮未为该非业务问题改配置或删目录。

```sh
export JAVA_HOME="$HOME/.local/opt/temurin-21"
export ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk"
./gradlew :app:assembleRelease :app:testDebugUnitTest --offline --max-workers=1 \
  -PschoolTest -PapiBase=https://example.invalid \
  --tests '*MigrationGuardTest' --tests '*MigrationEvidenceTest' \
  --tests '*CampusDbTest' --tests '*EamsLocalImportTest' \
  --tests '*GenericSchoolImportTest' --tests '*TimetableContractTest' \
  --tests '*SchoolTestChannelTest' --tests '*AuthDocumentStartTest' \
  --tests '*AuthBrowser*Test' --tests '*AuthResourceDiagnosticsTest' \
  --tests '*SchoolImportPolicyTest'
python3 evidence-school-test16/verify.py
./gradlew :app:dependencies --configuration releaseRuntimeClasspath --offline \
  --max-workers=1 -PschoolTest -PapiBase=https://example.invalid
```

证据目录包括 build-tests.log、verification.json、signature.txt、test15-signature-readonly.txt、badging.txt、dependencies.txt、verify.py。仅主 app/build.gradle 两处版本数字改动；未改认证/数据库源码，未运行 Codex、commit 或部署，未触碰 Gecko 工程/独立产物/既有后台任务。交父代理决定发布，不能把本机签名验收称作公网发布成功。

## 下一步只读调查结论：先建立能区分原因的真机回路

1. **document-start 完整性优先。** AuthBrowserWindows.install 在 root loadUrl 与 child transport 前执行 beforeNavigation，当前注册次序正确。但 registered 不等于页面执行。script 独立检查 exact HTTPS origin 和 window===window.top，仅观察顶层文档，故 MFA 若在 iframe 中可完全不被覆盖；不支持、注册失败、缺标记必须单独识别。缺少错误不能证明无错误。现有 error observer 忽略没有 e.error 的 error 事件（例如资源加载/部分跨域异常），native 回调另有固定资源失败但也不是完整 JS 可用性证明。全局标记可被页面覆盖或伪造，不能作为认证安全判据。当前资源错误是会话累计而非严格单页绑定，必须防止把之前页面错误归因给当前 MFA。
2. **下一轮获准后先加固定浏览器特性 probe，不先动 cookie/UA。** 真机先记录系统 WebView provider 包名/版本、Android SDK、DOCUMENT_START_SCRIPT 支持与当前页面执行标记；在白名单页面只测试 feature presence（例如 Promise/fetch/URL/TextEncoder/crypto.subtle/isSecureContext/PerformanceObserver/CSS.supports 的固定布尔值），不调用认证接口、不写存储、不读 Cookie/输入/异常 message、不包裹 fetch/XHR。具体 probe 集合应由公开学校页面实际使用的能力决定，不能无依据堆功能检测。当前代码没有 provider metadata 或这些 probes，本轮未加入。
3. **认证页固定结构比“读整页”有效。** 现有 SchoolDiagnostics 只有 table/frame 数、树维变量和 ready boolean，不能判断 MFA 应有组件是否挂载。先取经核实的公开登录页静态资源或经用户允许的本机脱敏证据，识别真实框架/固定 MFA 容器和 iframe origin，建立固定选择器存在/数量、挂载前后状态的允许列表；禁止从会话页抽取 body/HTML/文本/输入值或动态 token。NEAU 现有 URP 请求是课表接口，不能误当 MFA 认证探针。当前尚无已验证的 MFA 固定 selector/认证 endpoint 证据，不应编造或实现猜测 matcher。
4. **可复现验收路径。** 在同一手机/同一 provider 上，结构化记录“进入认证→触发 MFA→出现空白→当页复制诊断”，对比 root 与 popup、标记、frame 数和固定失败，再决定唯一假设与可失败测试。没有设备/凭据导致本机无法创建针对真实空白的红绿回路；已有 45 个测试只验证迁移/静态约束，不证明 MFA 可用。

结论：最有效下一步是同一次真机空白页面的 document-start 覆盖证据，再按实际页面能力与固定结构加有边界的诊断。不得盲改 UA/第三方 Cookie/存储，不扩大域白名单、不绕过证书，也不重新把 Gecko 推成常规更新。
