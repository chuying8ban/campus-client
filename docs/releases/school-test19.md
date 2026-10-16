# school-test19 / 9019 — offline APK candidate; diagnostic backend deployed

## 2026-10-15 authorized pre-publication acceptance
- Fixed release-only `CloudConsentDialogTest` host resolution with a test-only ordered Robolectric PackageManager registration. Debug alone depends on `ui-test-manifest`; release has no test activity. No production manifest changes, no deleted tests. Changing to testImplementation alone did not merge its manifest; explicit @Config manifest is unsupported by this AGP/Robolectric configuration. Both approaches were reverted.
- Fresh separate wildcard executions: release **55**, debug **55**, all zero failures/errors/skips (includes SchoolTestChannelTest). A combined two-task invocation accidentally applied filters only to the final task and ran unrelated full release UI tests; that exploratory failure is retained in android-regression.log, not counted as passing acceptance. Native-runtime temp directory cleanup warning remains non-fatal.
- Fresh real FastAPI/httpx suite **29 executed**, zero failures/errors/skips; exact embedded JS **9 assertions** passed. System Python lacks dependencies; verified suite uses `python`, not `/usr/bin/python3`.
- Live exact baseline backed up at `/root/diagnostics-test19-live/backup`; only school_diagnostics.py plus loadDiagnostics function updated. v2app hash `0917ac1f14cc8466d6c831ee488d45dd8af3695b4470cfe8ac88e28c6856ed03` and existing cron hash `bcc4d71120762ea08ac1599abf9299226c4bd6f9c79117b427ab5fabd40dd61e` unchanged. campus-v2-live active. Deployment script rolls back on failure.
- Real CA-verified public HTTPS `https://study.ccbase.top`: v1, old-v2, extended-v2, complete probe and null/unknown/unavailable probe each POST 200, duplicate 200, existing author_only authenticated GET 200 with exact UUID and complete payload equality. Executed true/false/null, capability true/false/null, fixed reauth stages and session/previousReceipt chain covered. Six nested extra-field levels rejected 422; conflict 409; no-auth GET 401.
- Five synthetic UUIDs deleted by exact id in finally and SQL zero-residual check. Dedicated admin session revoked in finally, same-token public GET 401 verified. No real user record deletion.
- Actual served admin HTML exact byte comparison passed; its actual loadDiagnostics function renders actual authenticated public readback fixtures including both probe values, warning and unknown text. This is executed JS text rendering, not a browser screenshot.
- All scripts, exact baselines/diffs, authenticated readback fixtures, cleanup evidence and logs: `migration/evidence/test19-live/`. No secrets included. Final APK unchanged: 7711556 bytes, SHA256 `35cc369991f664ba755b28a5828408a90070de887deffea30490842bcaf40463`; API_BASE remains intentionally example.invalid. No APK manifest publication or commit. No real-device MFA repair claimed.

## Historical candidate record (before authorized deployment)


## Artifact
- `artifacts/school-test19/campus-2.1.5-school-test19-9019.apk`
- 7711556 bytes; SHA256 `35cc369991f664ba755b28a5828408a90070de887deffea30490842bcaf40463`
- aapt: top.ccbase.campus.privacytest / 9019 / 2.1.5-school-test19
- apksigner verification passed; certificate SHA256 `6eb42692c49f799bc198cc8a4dc1300e9d9c2cee0a39190d0bed4afcbe1688ab`
- API_BASE https://example.invalid. Formal versionCode remains 89. No commit, deploy, publication or Codex.

## Candidate chain
`MfaProbe.kt` -> AuthResourceDiagnostics script -> AustLogin page-generation/request/round guarded callback -> LoginDiagnosticSession frozen consent snapshot -> SchoolDiagnosticSender telemetry.probe -> migration/diagnostics/school_diagnostics.py strict schema -> authenticated admin GET -> admin-index.test19.html visible text rendering.
Old v1/v2 and extended v2 remain accepted. New probe is optional only with the full current v2 telemetry keys. Unknown measurements are null; additional keys at every probe object level rejected. Navigation uses fixed reauth_check/reauth_submit enums, no raw endpoint path upload. Probe only measures approved HTTPS auth origins and fixed reAuthSubmit action pathname. No input, parameter object, text, HTML, cookies, network hooks or request/response bodies read. UA only classified locally, never serialized verbatim. Preview does not treat missing form as root cause and does not claim a present form proves MFA generation.

## Actual checks
- Android RED: missing MfaProbe compilation failure recorded in migration/evidence/test19-red.log.
- API RED: real FastAPI TestClient returned 422 instead of 200 before new schema implemented.
- Android focused release regression: **29 tests, 0 failures/errors/skips**; migration/evidence/test19-final.log. Includes frozen consent upload payload, stop/late round rejection, unknown, fixed stage, schema and previous diagnostics/window/channel regressions.
- Real FastAPI + httpx TestClient: **29 executed, 0 failures/errors/skips**, including inherited repeated tests, not 29 unique cases. Legacy schemas, new null probe POST/admin readback equality, unauthorized 401 and extra/wrong-type 422; migration/evidence/test19-fastapi.log.
- Exact Kotlin embedded JS executed with fixture DOM in Node: 9 assertions passed, including DOM unavailable, unauthorized origin, missing/present/hidden form and hostile action origin. `node migration/diagnostics/test19-probe.cjs`.
- Candidate admin loadDiagnostics function executed with fixture API response: complete probe and warning visibly rendered as text, 3 assertions passed. Not a real browser screenshot or production readback.
- Release assemble succeeded with JAVA_HOME ~/.local/opt/temurin-21, ANDROID_HOME ~/android-sdk, --offline --max-workers=1 -PschoolTest -PapiBase=https://example.invalid. migration/evidence/test19-focused.log.
- Broader school wildcard run: 54 tests, 2 pre-existing release Compose CloudConsentDialogTest failures (unresolvable androidx.activity.ComponentActivity in release manifest), remaining 52 passed. No tests skipped. Did not alter unrelated UI/manifest to hide these failures. Robolectric also logged temp-directory cleanup errors; focused final run passed.

## Deployment prerequisites / unresolved
1. Review local backend candidate `migration/diagnostics/school_diagnostics.py` and admin UI candidate `migration/diagnostics/admin-index.test19.html`. Existing deployed filenames were not overwritten. Obtain exact live baselines and reject drift; replace only loadDiagnostics in live UI, preserve everything else and cron/v2app/auth.
2. Backend accepting telemetry.probe and new stage enums MUST precede users sending this APK, otherwise old server correctly rejects with 422. Perform authorized real public POST plus authenticated exact-ID GET readback and cleanup after deployment approval.
3. Rebuild with approved real API_BASE before production distribution; recompute hash/signature. This example.invalid APK is not a production API release.
4. No real Android MFA page or authenticated MFA template obtained. Fixed form absence is inconclusive; collect same-phone screenshots/probe at same MFA stage, compare system browser. No UA/cookie/CSS remediation applied and no login repair claimed.
