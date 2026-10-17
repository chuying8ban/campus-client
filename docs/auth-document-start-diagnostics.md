> **⚠️ 已归档（2026-10-17）**：用户决定放弃多学校方向，正式版只支持中国石油大学（北京）克拉玛依校区。
> 本文件记录的是当时的研究与边界；相关代码（外校登录测试、通用文件导入、登录诊断、全国高校目录）已从仓库删除，
> 2.1.7 起不再随包分发。留档只为将来若重启这条线时不再重复踩坑。

# Authentication document-start diagnostics (not a blank-page fix)

## Implemented boundary

AuthDocumentStart registers AndroidX WebKit DOCUMENT_START_SCRIPT before root loadUrl and before child WebView transport is delivered. WebKit 1.9.0 supports this API and compileSdk 34; added only that dependency, no version/build-number changes. Cache discovery found no WebKit artifact; bounded dependency resolution completed in 2 seconds, subsequent build/tests work offline.

Rules are exact https://host origins, no wildcard, credentials, port, path/query/fragment; script independently checks location.origin. Collection deliberately covers top-level documents only (including popup documents), not iframe error aggregation. Fixed JS types and boolean execution marker are local-only, native decoder allowlists them again. Unsupported feature and registration failure have explicit no-observer states; no page-commit/page-finished late injection exists. Registration success is distinct from the current-page execution marker. Missing errors or marker cannot establish absence of failure. Page-controlled globals can be spoofed; these are diagnostic hints, not security or login-success decisions.

No exception message/stack/rejection value, console, body, cookie, input or HTML read. No preventDefault, request interception, fetch/XHR replacement, authentication retry, UA/cookie changes. Existing in-App popup handling, HTTPS navigation and certificate rejection preserved.

## XHR/fetch failure investigation

Existing onReceivedHttpError receives native HTTP status >=400 for resources; onReceivedError receives fixed numeric WebView failures. AuthResourceDiagnostics records bounded fixed extension category + numeric status only, never response body/headers or raw URL. This can expose HTTP/network failure of some AJAX requests, but WebResourceRequest has no reliable initiator-type (XHR vs fetch vs resource) and cannot prove authentication endpoint identity. A generic `other:403` must not be relabeled `authentication failed` without exact verified endpoint evidence.

Passive PerformanceObserver resource entries can identify initiatorType `fetch`/`xmlhttprequest`, but HTTP responseStatus availability varies by engine and cross-origin timing policy; status 0 is ambiguous, entries are not complete evidence of CORS/network rejection. It also entails processing resource URLs to attribute an auth endpoint. No unverified endpoint matcher added.

Wrapping fetch or XHR to watch completion risks observable function identity, promise handling/unhandled-rejection changes, extra arguments/getters or listeners; strict 'do not alter authentication request behavior' rules out that technique here. Current phase intentionally retains native safe signals rather than implementing a misleading auth-XHR detector.

## Existing server schema investigation (no deployment)

SchoolDiagnosticSender emits only id/school/version/page/tables/frames/hasTreeModel/ready/events; it drops observerStarted/jsTypes. migration/diagnostics/school_diagnostics.py normalize keeps known event prefixes only and discards all detail. Therefore adding `资源错误:script:404` or `诊断完成:unsupported` to current events loses the actual category/status on the server. Changing ready/tables/page to encode diagnostics would corrupt their meaning. No such workaround implemented.

Possible compatible next change: retain top-level schema and allow an explicit small finite event vocabulary in BOTH client payload sanitizer and backend normalize, alongside existing events; consent preview/frozen snapshot/30-day retention remain. Backend deployment and readback needed before claiming developer inbox captures them. Until then copy/share is necessary for these local signals.

## Verification and limits

AuthDocumentStartTest adds 4 regressions: exact origins, support-before-registration/no fallback/fixed registration failure, root/popup registration order and no late injection, forbidden sensitive reads/request mutations and execution marker. Related Auth*/SchoolDiagnostic* suite: 14 tests, failures/errors/skips 0. assembleRelease successful; repeat --offline --max-workers=1 successful. Build warning pre-exists: Room foreign key course_id lacks index. APK is build validation only (apiBase=https://example.invalid, still test13/code9013), not a new user release. No commit/deploy.

No device or NEAU credentials: no reproduction or proof that blank MFA body is fixed. Next evidence must be same-page user report containing registered/unsupported state, execution marker, fixed JS types and native fixed failures; record WebView provider version through approved fixed metadata in a later change if needed. If supported+executed yields no discriminating signal, stop WebView guesswork and proceed only to separately approved in-App GeckoView probe.

Reference: https://developer.android.com/reference/androidx/webkit/WebViewCompat (addDocumentStartJavaScript: executes in matching-origin frames when document begins to load).
