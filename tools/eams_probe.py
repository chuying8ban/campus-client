#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""清点教务(树维 EAMS)对**学生端**开放了哪些页面/接口 —— 不用登录也能做的第一步。

做法：未登录状态下逐个请求候选路径，看返回什么：
  302 → 这个路径存在，只是要登录（对本 App 有意义，值得进一步取数据）
  200 → 公开页面
  404/500 → 不存在或不可用
先有这张地图，再谈"登录后能拿什么"，不用拿真账号去乱试。

用法：python3 tools/eams_probe.py [--base https://eams.cupk.edu.cn]
"""
import argparse
import ssl
import sys
import urllib.error
import urllib.request

CANDIDATES = [
    # —— 已知在用 ——
    "/student/login",
    "/student/login-salt",
    "/student/for-std/course-table",
    # —— 课表 / 教学安排 ——
    "/student/for-std/course-table/semester/1/print-data?semesterId=1&hasExperiment=true",
    "/student/calendar",
    "/student/for-std/course-tool",
    "/student/for-std/course-table/search",
    # —— 考试 / 成绩 ——
    "/student/for-std/exam-arrange",
    "/student/for-std/exam-arrange/search",
    "/student/for-std/grade/sheet",
    "/student/for-std/grade/search",
    "/student/for-std/grade/statistics",
    "/student/for-std/course-score",
    "/student/for-std/credit",
    # —— 培养方案 / 学分 ——
    "/student/for-std/program",
    "/student/for-std/program/completion",
    "/student/for-std/program/curriculum",
    "/student/for-std/plan",
    # —— 选课 ——
    "/student/for-std/elective",
    "/student/for-std/elective/course",
    "/student/for-std/elective/search",
    # —— 评教 / 问卷 ——
    "/student/for-std/appraisal",
    "/student/for-std/appraisal/student",
    "/student/questionnaire",
    # —— 通知 / 公告 ——
    "/student/notice",
    "/student/notice/list",
    "/student/for-std/notice",
    # —— 学籍 / 个人信息 ——
    "/student/info",
    "/student/for-std/student-info",
    "/student/profile",
    "/student/for-std/status",
    # —— 实践 / 实习 / 论文 ——
    "/student/for-std/practice",
    "/student/for-std/internship",
    "/student/for-std/thesis",
    # —— 考勤 / 课堂 ——
    "/student/for-std/attendance",
    "/student/for-std/classroom",
    # —— 其它可能的总线式接口 ——
    "/student/api/menu",
    "/student/menu",
    "/student/home",
    "/student/index",
    "/student/for-std",
    "/student/cfg/menu",
]


def probe(base, path):
    url = base.rstrip("/") + path
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Linux; Android 14) campus-client-probe",
        "Accept": "*/*",
        "Accept-Language": "zh-CN,zh;q=0.9",
    })
    ctx = ssl.create_default_context()
    # 不跟随重定向：302 就是"存在但要登录"，这恰恰是我们要的信息
    opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx), NoRedirect())
    try:
        r = opener.open(req, timeout=20)
        return r.status, r.headers.get("Location", ""), r.headers.get("Content-Type", ""), r.read(200)
    except urllib.error.HTTPError as e:
        body = b""
        try:
            body = e.read(160)
        except Exception:
            pass
        return e.code, e.headers.get("Location", ""), e.headers.get("Content-Type", ""), body
    except Exception as e:  # 网络层：单独标出来，别当成"不存在"
        return None, str(e)[:60], "", b""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="https://eams.cupk.edu.cn")
    a = ap.parse_args(argv)

    hit, auth, miss, err = [], [], [], []
    for p in CANDIDATES:
        code, loc, ct, body = probe(a.base, p)
        row = (p, code, loc, ct, body)
        if code is None:
            err.append(row)
        elif code in (301, 302, 303, 307):
            auth.append(row)
        elif 200 <= code < 300:
            hit.append(row)
        else:
            miss.append(row)

    def show(title, rows):
        print("\n== %s (%d) ==" % (title, len(rows)))
        for p, code, loc, ct, body in rows:
            extra = ("-> " + loc.split("?")[0]) if loc else ""
            sniff = body.decode("utf-8", "replace")[:80].replace("\n", " ")
            print("  %-84s %s %s %s" % (p, code, extra, ("| " + sniff) if sniff and "json" in (ct or "").lower() else ""))

    show("存在但要登录（302）", auth)
    show("公开可访问（2xx）", hit)
    show("不存在/被拒", miss)
    if err:
        show("网络层失败", err)
    return 0


if __name__ == "__main__":
    sys.exit(main())
