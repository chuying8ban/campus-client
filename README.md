# 校园助手

面向中国石油大学（北京）克拉玛依校区同学的非官方 Android 学习工具。

## 功能
- 今日安排、课表、课程提醒与本机任务。
- 学习任务、专注记录、打卡与统计看板。
- 学习库资源浏览与收藏、主题及背景设置。
- 云端身份有效时可使用 AI 学习建议、云端内容刷新和反馈。

课程余量监控已移除，不提供自动选课。

## 课表与隐私
课表在学校官方 HTTPS 页面登录后导入。App 不读取、不保存教务密码，不向自有后端上传教务密码或 Cookie。默认仅本机保存；云端同步需在导入结果页阅读条款并明确同意。

云端保存的是主动导入的课表快照，不持续读取教务。课表变化需重新导入。新云端身份是随机访客，约 30 天过期，不支持卸载、清除数据或退出后的账号恢复，也不支持跨设备恢复。本机-only 用户仍可使用本地功能；云端功能入口会说明前提并引导导入。

本项目不是学校官方 App。教务网页仍有排版兼容问题；提醒送达受系统权限及省电设置影响。AI 建议不保证学习效果，外部学习资源由对应网站提供。

## 下载与更新
Android 8.0 及以上：[正式版本](https://github.com/chuying8ban/campus-client/releases)。旧正式版请直接覆盖安装，不要先卸载。独立测试包与正式包数据不互通。

## 构建
JDK 21、Android SDK。本机 local.properties 配置 sdk.dir；后端地址可通过 -PapiBase 覆盖。发布签名需要自己的 keystore，口令不得入仓。

```sh
./gradlew :app:testDebugUnitTest --offline --max-workers=2
./gradlew :app:assembleDebug
```

发布说明见 [docs/RELEASING.md](docs/RELEASING.md)。源码不包含服务端、发布私钥或签名口令。

[English](README_EN.md) · [License](LICENSE)
