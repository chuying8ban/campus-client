# 安徽理工大学本科直接导入：入口调查

官方教务处 https://jwc.aust.edu.cn/ 首页明确链接 http://jwgl.aust.edu.cn/eams/login.action （页面含动态会话尾缀，不能固化）。这是 /eams/ 老入口，不是 CUPK /student/print-data 协议；尚不能确认系统版本与课程接口。

本机探测该域 HTTP/HTTPS 均15秒连接超时；教务处和 https://service.aust.edu.cn/ 可达。不能据此断言教务仅校园网，更不能把HTTP改写HTTPS后宣称可登录。禁止降安全校验、允许明文密码或套CUPK适配器冒充支持。

下一证据：授权本科测试同学从日常入口打开教务，提供登录页地址和脱敏课表页截图（不包含密码/Cookie/完整HTML）。若必须WebVPN，核实官方VPN及实际HTTPS转发origin再配置精确白名单。若HTTP-only，停止内置凭据登录，需学校安全入口。

当前未登记安徽理工大学为可导入，未构建冒充已适配的新测试包。
