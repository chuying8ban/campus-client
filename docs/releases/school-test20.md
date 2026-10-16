# school-test20 / 9020 — local evidence

No deploy/publish/commit. Production versionCode remains 89. API_BASE=https://example.invalid; SCHOOL_TEST=true. Release built offline, one worker, Temurin 21, ~/android-sdk.

## Diagnosis boundary
User-supplied test19 device probe: viewport/root/visualViewport 360x178; secureContext true; top-level visible; reAuthParams present; fixed form not matched; no JS errors. Existing native chrome is demonstrably wasteful, but low viewport causing MFA blank is a hypothesis, NOT a proven root cause. reAuthParams uses typeof only; fixed form absence does not prove component absence. Probe/schema/network/security/consent upload code unchanged.

## Real Robolectric Compose measurements (mdpi, controlled parent constraints)
Before: at 360x640, WebView Rect(0,308,360,640), measured 360x332; new viewport assertion failed at the intended assertion. Robolectric text metrics do not reproduce real device wrapping, so this does NOT recreate the device's 178px.
After: 360x640 -> 360x592; 360x480 -> 360x432; 360x320 -> 360x272. Toolbar hidden by actual MotionEvents dispatched to composed Android WebView -> 360x640 / 360x480 / 360x320. Finger downward restores same measured toolbar viewport without scrollY. Opening/closing information AlertDialog leaves underlying viewport bounds unchanged. 320 is a reduced parent/keyboard-size simulation, not a real IME integration test.

59 selected UI/school/diagnostic/security/channel tests passed, 0 failures/errors/skips (XML aggregate). Real MotionEvent tests cover up/down, tiny movement, direction hysteresis, horizontal rejection, pointer-down cancellation, open-menu blocking, non-scrollable WebView, false return (no event consumption). Compose-to-AndroidView swipe injection did not reach WebView in Robolectric; replaced with real MotionEvent dispatchTouchEvent on actual composed WebView, not a constant-only assertion.

Native chrome: one 48dp toolbar (back/refresh/diagnose/info); privacy and controls in scrollable info dialog. Progress/error feedback overlays rather than consuming WebView height. Finger-up hides toolbar and overlays; finger-down restores. System back restores hidden root toolbar first. Child auth dialog's address/close controls follow finger direction and Back restores them first. System status/navigation bars not hidden. Full safety copy preserved; default diagnostic session and preview-consent-upload-receipt chain retained.

## Artifact
artifacts/study-school-test20-9020.apk
7727940 bytes
SHA256 b3cf5619838eab91bae085420a7df1056bad055524db7ed81a77d1989c6a4d17
Signing certificate SHA256 6eb42692c49f799bc198cc8a4dc1300e9d9c2cee0a39190d0bed4afcbe1688ab
apksigner verify succeeded; aapt confirms top.ccbase.campus.privacytest / 9020 / 2.1.5-school-test20.

## Not verified
Real device MFA success, actual browser inner/visual viewport after update, real keyboard resize/pinch/input behaviour and pixel-level appearance. No live school login or upload performed. Same phone should need only one update, then same-stage before/after diagnostic and screenshot; do not label MFA fixed beforehand.
