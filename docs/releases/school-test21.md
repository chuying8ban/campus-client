# school-test21：浏览器手势连续性测试

- 上滑工具栏随手指平移淡出，下滑累计超过反向阈值后恢复；松手以160ms吸附。
- 网页触摸不消费；横向、多指、点击和信息弹窗期间不驱动工具栏。
- 主窗口与认证子窗口不再通过GONE/移除顶部布局改变WebView高度。

## 空间取舍（必须告知用户）
本次选择固定网页布局，保留顶部48dp原生槽位，隐藏后槽位仍在。因此不再宣称隐藏后网页扩展到整屏；这是用48dp空间换取拖动期间零viewport resize，避免原生布局高度变化及触摸坐标跳变。展开时原生工具栏不覆盖网页，网页顶部内容始终可访问。没有向网页注入padding或每帧resize。子窗口同样保留原生工具栏槽位。

## 实测
JDK ~/.local/opt/temurin-21，SDK ~/android-sdk；--offline --max-workers=1 -PschoolTest -PapiBase=https://example.invalid。
`:app:testDebugUnitTest --tests '*school.*' :app:assembleRelease` BUILD SUCCESSFUL，59 tests / 0 failures / 0 errors / 0 skipped。
Robolectric真实MotionEvent拖动帧：[0.86,0.66,0.46000004]；实际ValueAnimator seek到80/160ms帧：[0.86,0.86,0.43,0.0]。不是实机帧率测量。
360x640、360x480、360x320容器，网页显示/隐藏分别始终592、432、272px高。修改前相同“不resize”断言真实失败。
覆盖短反向重复抖动、不可滚动WebView、横向、第二指、点击、菜单锁定、restore及子窗口alpha/高度。

## 产物（未发布）
/Users/a29285/.hermes/cache/scratch/campus-2.1.5-school-test21.apk
versionName 2.1.5-school-test21，versionCode 9021，applicationId top.ccbase.campus.privacytest；正式89不变。
size 7727940 bytes
sha256 f8b89fb56b307153ac1880f991da2272087f58e0c3ca008685f155c1142257f4
apksigner verify成功，证书SHA256 6eb42692c49f799bc198cc8a4dc1300e9d9c2cee0a39190d0bed4afcbe1688ab。

## 边界
尚未真机安装/手势验收，未做全项目测试；本次仅school包59项。
登录、诊断、安全逻辑未修改。test20报告viewport/root/visual已接近375x707，但auth阶段、form.exists=false、jsTypes=[]；视口不足不是完整原因，不宣称MFA/教务登录已修复，不建议更新WebView。API为example.invalid，只供原有本机学校浏览器测试；云端不可用。父代理负责发布与用户更新说明。
