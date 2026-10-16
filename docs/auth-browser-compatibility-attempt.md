# 学校登录 App 内浏览器兼容尝试（待真机验收）

## 证据与边界
- 用户证据：东北农业 test11 MFA 只有标题/安全退出、正文空白；系统浏览器正常。test12仅诊断。未取得已登录 MFA 页面，不能确认空白根因，更不能声称已复现或修复真实 MFA。
- 公开无凭据 GET `https://webvpn.neau.edu.cn/` 实际跳转到现有白名单 `authserver-443.webvpn.neau.edu.cn/authserver/login`，200。
- 官方公开脚本（版本查询 `v=20260615.104330`）login.js 19967字符、schoolCombinedLogin.js 374字符、common-header.js 13210字符、fido.js 13931字符均200。common-header含 MULTIFACTOR_BROWSER_FINGERPRINT；fido含 navigator.credentials.get。登录页含 target=_blank（使用指南）。**未在上述抽样脚本找到 MFA 的 window.open 证据**；FIDO/WebAuthn能力也不能用单纯窗口配置保证。
- 当前工程已有 androidx.browser:browser:1.8.0（Custom Tabs依赖浏览器，不满足此需求），无 androidx.webkit；本次仅使用平台 WebView API，不增加依赖/改 Gradle。
- 旧 AustLogin 无 WebChromeClient/多窗口配置，Android默认 onCreateWindow 返回false。这是已确认的浏览器能力缺口，不等于真实 MFA 根因。

## 行为与安全
- 平台多窗口开关，用户手势 window.open/target=_blank通过 WebViewTransport进入同App Dialog子WebView；最多一个子窗口，拒绝无手势/嵌套窗口。保留原生opener与同WebView profile，未提取/复制Cookie或Chrome凭据。
- 父子共享精确HTTPS主导航白名单、证书cancel、诊断客户端；对新窗口首个非白名单主请求增加拒绝响应。子窗显示HTTPS host，不显示敏感query。
- DOM storage/JS沿用，补viewport；UA不变，第三方Cookie仍禁，文件/content/文件跨域/混合内容仍禁，Safe Browsing开启。
- 活动子窗口作为读取/脱敏诊断目标，切换窗口使旧读取失效。返回先子窗历史再关闭；window.close/关闭按钮/清除登录/页面销毁均销毁子窗。普通关子窗保留认证会话以便opener继续，退出整个登录流程仍按旧逻辑清理学校域Cookie和WebStorage。
- console回调只返回true，不读取/记录任何原文。

## 验收局限
JVM Robolectric可验证设置、回调transport、活动窗口交接、返回/清理；不执行真实Chromium JS/WebAuthn，不证明真实MFA、实际postMessage或第三方SSO通过。需测试包真机验收；若MFA依赖无手势弹窗、第三方Cookie或WebAuthn，仍可能失败，不预先放宽安全。
