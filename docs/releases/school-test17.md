# school-test17 / code9017（正式89不变）

## 使用
在学校登录窗口主动点击「开启本次登录诊断（刷新）」，重新走本次登录。默认关闭，开启会在刷新前注册固定document-start观察器。发生主页面网络/HTTP/证书错误或课表读取失败时尝试生成诊断；也可主动生成。预览后点击「同意并发送给开发者」才上传。发送失败在同一个预览中重试，使用相同UUID和冻结快照；成功显示回执。停止解除ScriptHandler与当前文档固定监听器并清空本轮诊断。重新开启是独立一轮；同一登录窗口保留上次固定汇总作重试对照（不持久化）。

## 边界
仅固定JS错误名、WebView数字版本、document-start能力/注册/执行标记、至多16资源失败类别/HTTP或网络数字码、至多16导航阶段/相对毫秒；不读取错误原文、console、输入DOM、cookie、请求响应body/query。仅顶层和App内认证子窗口，不保证iframe错误覆盖。无错误不等于修好；other:403不是认证失败证明。

通过独立HTTPS诊断端点发送v2 telemetry字段；主AppAPI仍为example.invalid。服务器维护源码兼容旧v1固定字段和新v2，拒绝未知键/恶意枚举；后台使用现有认证，以textContent呈现v2数据，保留30天、幂等冲突检查与限流。不新增JS bridge或任意脚本/远控。不含Gecko。

## 本机验证与未验
Android school关键测试与测试更新通道测试、签名release构建通过；Python隔离venv中真实FastAPI TestClient和SQLite测试通过。线上未部署、未做真实学校账号或手机WebView登录验证。依赖环境PYTHONPATH曾污染隔离venv；使用env -u PYTHONPATH运行已解决。

仍需父代理安全审查与部署后公网POST→认证后台按UUID读回。特别需继续验收：无可执行文档/证书错误页上的诊断生成可能无法读取结构；目前会报告未完成而不是伪造零表格。全流程Compose交互/真机监听器移除还未证明，不能声称完整闭环验收。
