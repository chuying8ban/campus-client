# school-test18 / 9018

测试版在打开 AustLogin 学校登录页时默认开启本次脱敏诊断，正式版默认关闭（正式 versionCode 89 不变）。诊断只随本页存在，不在 App 后台全局常驻。Session 在 remember 创建，document-start 在根首次 loadUrl 和子窗口 transport 交付前注册。停止本次诊断后，刷新/跳转/清除登录不会重新开启；重新打开页面创建新 session，测试版恢复默认开启。关闭页面清理观察器、事件、快照、同意及回执关联和学校认证会话。

保留停止/手动重新开启入口。失败只生成本机预览，用户主动选择“同意并发送给开发者”才上传；后台 schema 不变，无自动上传。

## 本机实测

- 先新增 LoginDiagnosticDefaultTest，旧实现编译失败（缺少 defaultEnabled），再实现。
- Release 正式配置默认回归：4 项通过；未构建/发布正式包。
- Release schoolTest 关键诊断、document-start、窗口、上传和更新通道回归：23 项，0 失败/错误/跳过。
- 使用 JAVA_HOME=~/.local/opt/temurin-21、ANDROID_HOME=~/android-sdk，Gradle --offline --max-workers=1，-PschoolTest -PapiBase=https://example.invalid；assembleRelease 成功。
- 注册顺序由源码 wiring 回归检查，session 停止/重开/关联清理由执行单测覆盖；未做真实设备 WebView 登录验收，不声称实际学校登录已修复。

## 交给父代理的离线验收产物

路径：artifacts/school-test18/campus-2.1.5-school-test18-9018.apk
包名：top.ccbase.campus.privacytest
版本：2.1.5-school-test18 / 9018
大小：7711556 bytes
SHA-256：8387778a91ee762f63329e7179eb57591e1e5987f31157e645517082630d92af
apksigner verify：通过，v2 签名，RSA 2048；证书 SHA-256：6eb42692c49f799bc198cc8a4dc1300e9d9c2cee0a39190d0bed4afcbe1688ab

注意：此包 API_BASE=https://example.invalid，只用于本机离线构建验收，不可直接作为生产 API 可用包发布。父代理如发布，需按批准的实际 API_BASE 重新构建并重新核验签名和 hash。本任务未部署后台、未发布、未调用 Codex、未 commit。
