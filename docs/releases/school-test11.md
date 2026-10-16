# test11：东北农业登录链路与URP读取试验

- 东北农业入口改为官方WebVPN的学生教务目标，校外访问先经过学校认证。
- 仅允许精确核验的HTTPS认证/VPN/学生教务域，不放行整个neau.edu.cn或任意子域。
- 加入URP课表接口读取尝试，课程白名单字段仅在本机预览及确认导入。
- 使用接口返回的jcsjbs节次作息，不套CUPK时间；缺少作息/周次或字段异常整批拒绝，原课表不变。
- 仍需真实学生登录验证；合成JS执行及相关Android测试通过不等于真实支持已验收。

字段协议调查参考ShiGuangSchedule/shiguang_warehouse/resources/NEAU/NEAU_01.js（MIT），不是复制其自动写入桥/默认45分钟时长。上游许可位于migration/school-directory/entry-candidates.zip的LICENSE。
