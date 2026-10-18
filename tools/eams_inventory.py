#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""登录一次教务，把**学生端真正能拿到的数据**列清楚。

为什么要这个脚本：教务对**未登录**的请求一律 302 到登录页（连不存在的路径也 302），
所以从外面永远看不清"到底有哪些接口"。只有带着一次有效会话进去，才能区分
「有这一页」和「没有这一页」，以及每页背后的数据接口返回什么形状。

它做的事：
  1. `GET /student/login-salt` → salt；`password = sha1("<salt>-<明文>")`（与 App 同一条路）
  2. 登录后 `GET /student/`：把首页里的菜单链接全部抓出来（这才是教务真正开给学生的功能清单）
  3. 逐个访问候选模块页，记录状态 / Content-Type / 页面标题
  4. 对每个模块再探常见的 JSON 数据接口，记录返回的**顶层字段名**（不打印内容，只打结构）

口令从 `~/.secrets/eams_creds.json` 读，跑完请删掉那个文件。

用法：
    python3 tools/eams_inventory.py            # 打印报告
    python3 tools/eams_inventory.py --json out.json
"""
import argparse
import hashlib
import http.cookiejar
import json
import os
import re
import ssl
import sys
import urllib.error
import urllib.request

BASE = "https://eams.cupk.edu.cn"
CREDS = os.path.expanduser("~/.secrets/eams_creds.json")
UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"

MODULES = [
    ("课表", "/student/for-std/course-table"),
    ("校历", "/student/calendar"),
    ("考试安排", "/student/for-std/exam-arrange"),
    ("成绩单", "/student/for-std/grade/sheet"),
    ("成绩统计", "/student/for-std/grade/statistics"),
    ("学分获得", "/student/for-std/credit"),
    ("培养方案", "/student/for-std/program"),
    ("培养方案完成度", "/student/for-std/program/completion"),
    ("选课", "/student/for-std/elective"),
    ("评教", "/student/for-std/appraisal"),
    ("通知", "/student/notice"),
    ("学生信息", "/student/info"),
    ("学籍", "/student/for-std/status"),
    ("实践", "/student/for-std/practice"),
    ("实习", "/student/for-std/internship"),
    ("毕业论文", "/student/for-std/thesis"),
    ("考勤", "/student/for-std/attendance"),
]

# 每类模块常见的 JSON 数据接口（树维 EAMS 的页面都会去拉一个 data 接口）
DATA_PROBES = [
    "/student/for-std/exam-arrange/search",
    "/student/for-std/grade/sheet/search",
    "/student/for-std/grade/search",
    "/student/for-std/credit/search",
    "/student/for-std/program/completion/search",
    "/student/for-std/elective/search",
    "/student/notice/search",
    "/student/for-std/attendance/search",
    "/student/calendar/search",
]


def opener_with_jar():
    cj = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(
        urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        urllib.request.HTTPCookieProcessor(cj),
    )
    return op, cj


def fetch(op, path, data=None, headers=None):
    h = {"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(BASE + path, data=data, headers=h)
    try:
        r = op.open(req, timeout=30)
        return r.status, r.headers.get("Content-Type", ""), r.read(), r.geturl()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read()[:4000], BASE + path
    except Exception as e:
        return None, str(e)[:80], b"", BASE + path


def top_keys(raw: bytes):
    """只取顶层字段名 —— 结构比内容重要，也不把课表/成绩原样落盘。"""
    try:
        obj = json.loads(raw.decode("utf-8", "replace"))
    except Exception:
        return None
    if isinstance(obj, dict):
        return sorted(obj.keys())[:12]
    if isinstance(obj, list):
        head = obj[0] if obj else None
        return ["<list len=%d>" % len(obj)] + (sorted(head.keys())[:12] if isinstance(head, dict) else [])
    return None


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", help="把报告另存为 JSON")
    a = ap.parse_args(argv)

    if not os.path.exists(CREDS):
        raise SystemExit("缺少 %s（学生登录凭据，格式 {\"student_id\":\"...\",\"password\":\"...\"}）" % CREDS)
    creds = json.load(open(CREDS))
    sid, pw = str(creds["student_id"]).strip(), str(creds["password"])
    os.chmod(CREDS, 0o600)

    op, cj = opener_with_jar()
    st, ct, body, _ = fetch(op, "/student/login-salt")
    salt = body.decode("utf-8", "replace").strip().strip('"')
    if st != 200 or not salt:
        raise SystemExit("拿不到登录盐：HTTP %s" % st)

    payload = json.dumps({
        "username": sid,
        "password": hashlib.sha1(("%s-%s" % (salt, pw)).encode()).hexdigest(),
        "captchaToken": "",
    }).encode()
    st, ct, body, _ = fetch(op, "/student/login", data=payload, headers={
        "Content-Type": "application/json",
        "Referer": BASE + "/student/login",
    })
    j = {}
    try:
        j = json.loads(body.decode("utf-8", "replace"))
    except Exception:
        pass
    if not j.get("result"):
        msg = j.get("message", body[:200].decode("utf-8", "replace"))
        raise SystemExit("登录没成功：%s%s" % (msg, "（教务要求图形验证码，这条路线走不通，只能人工核）" if j.get("needCaptcha") else ""))
    print("① 登录成功：%s" % sid)

    report = {"student_id": sid, "modules": [], "data": [], "menu": [], "notes": []}

    # ② 首页菜单 —— 教务真正开给学生的功能清单
    st, ct, body, _ = fetch(op, "/student/")
    html = body.decode("utf-8", "replace")
    links = sorted(set(re.findall(r'href="(/student/[^"#?]{2,80})"', html)))
    menu = [l for l in links if not re.search(r"\.(css|js|png|ico|woff2?)$", l)]
    report["menu"] = menu
    print("② 首页菜单项 %d 个：%s" % (len(menu), ", ".join(menu[:12])))

    def probe(path, label=None):
        st, ct, body, final = fetch(op, path)
        title = ""
        m = re.search(r"<title>(.*?)</title>", body.decode("utf-8", "replace"), re.S)
        if m:
            title = m.group(1).strip()[:40]
        keys = top_keys(body)
        row = {
            "path": path, "label": label, "status": st,
            "content_type": ct.split(";")[0],
            "title": title,
            "redirected_to_login": "login" in str(final),
            "json_keys": keys,
        }
        return row

    print("\n③ 模块页（登录后逐个访问）")
    for label, path in MODULES:
        row = probe(path, label)
        report["modules"].append(row)
        mark = "✓" if row["status"] == 200 and not row["redirected_to_login"] else "·"
        print("  %s %-14s %-46s %s %s%s" % (
            mark, label, path, row["status"],
            (row["content_type"] or "")[:28],
            (" | " + row["title"]) if row["title"] else "",
        ))

    print("\n④ 数据接口（返回结构，不含内容）")
    for path in DATA_PROBES:
        row = probe(path)
        report["data"].append(row)
        keys = row["json_keys"]
        print("  %-52s %s %-24s %s" % (
            path, row["status"], (row["content_type"] or "")[:24],
            ("keys=" + ",".join(keys[:8])) if keys else "",
        ))

    # ⑤ 学期列表（App 已用；确认还能拿到哪些学期）
    st, ct, body, _ = fetch(op, "/student/for-std/course-table")
    html = body.decode("utf-8", "replace")
    sems = re.findall(r"'id':\s*(\d+),\s*'name':\s*'([^']*)'", html)
    if not sems:
        sems = re.findall(r'"id":\s*(\d+),\s*"name":\s*"([^"]*)"', html)
    report["semesters"] = sems[:12]
    print("\n⑤ 学期列表 %d 个：%s" % (len(sems), ", ".join("%s=%s" % (i, n) for i, n in sems[:5])))

    if a.json:
        open(a.json, "w").write(json.dumps(report, ensure_ascii=False, indent=2))
        print("\n报告已写入 %s" % a.json)
    return 0


if __name__ == "__main__":
    sys.exit(main())
