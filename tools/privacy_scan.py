#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""隐私体检：发行包里不许出现本人标识。

为什么要有这个脚本（而不是继续靠人扫）：
    曾线上 `campus-x.y.apk` 被查出内置 `assets/*.json` 里有导出者的姓名与班级标识 ——
    这些 JSON 是**随包分发**的，任何人下载 APK 就能读出来，而且当时几个老包同样中招。
    那时的发布闸门只查 dex 里的隧道地址/生产地址，**没有任何一步看个人数据**，
    文本 grep 也扫不进二进制 zip。所以补这一步。

**needle 清单不在本文件里**（本文件会随公开仓发布，写死就等于自己把自己扫出来）。从仓外读：
    --needles-file <path>  →  $STUDY_PRIVACY_NEEDLES_FILE  →  ~/.config/study-native/privacy_needles.txt
格式：每行一个词；空行与 `#` 开头忽略；行首可写档位前缀 `HARD ` / `REPORT `（默认 HARD）。
见同目录 `privacy_needles.example.txt`。**清单缺失或为空 → 直接报错退出，不静默放行。**

两档，别混：
    HARD   —— 本人标识（姓名/班级/学号）。命中即 exit 1，发布闸门在这里停下。
    REPORT —— 学校名/城市名/「学号」这类词。App 界面文案里本来就写着
              「××校区学生自用工具 · 非学校官方产品」，那是产品身份不是个人数据；
              但它必须被**报出来**，不能装作没看见（要不要改是产品口径问题）。

用法：
    python3 tools/privacy_scan.py dist/campus-x.y.apk       # 扫包（含二进制 zip 项）
    python3 tools/privacy_scan.py app/src/main/assets       # 扫目录（构建前自检）
    python3 tools/privacy_scan.py app/src/main --needles-file ~/needles.txt
"""
import os
import sys
import zipfile

DEFAULT_NEEDLES = os.path.expanduser("~/.config/study-native/privacy_needles.txt")
CTX = 42   # 命中处前后各留多少字符，方便直接判断是不是真泄漏


def load_needles(path: str):
    """读 needle 清单 → (hard, report)。清单缺失/为空返回 None（调用方负责报错）。"""
    if not path or not os.path.isfile(path):
        return None
    hard, report = [], []
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            head, sep, rest = line.partition(" ")
            if sep and head.upper() in ("HARD", "REPORT") and rest.strip():
                (report if head.upper() == "REPORT" else hard).append(rest.strip())
            else:
                hard.append(line)
    if not hard and not report:
        return None
    return hard, report


def _iter_blobs(path: str):
    """产出 (条目名, 字节)。传目录则递归读文件；传文件是 APK/zip 就逐条目读。"""
    if os.path.isdir(path):
        for root, _dirs, files in os.walk(path):
            for f in sorted(files):
                p = os.path.join(root, f)
                with open(p, "rb") as fh:
                    yield os.path.relpath(p, path), fh.read()
        return
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.endswith("/"):
                    continue
                yield n, z.read(n)
        return
    with open(path, "rb") as fh:
        yield os.path.basename(path), fh.read()


def scan(path: str, hard_terms, report_terms) -> int:
    hard_hits, report_hits = [], {}
    total = 0
    for name, blob in _iter_blobs(path):
        total += 1
        for term in hard_terms:
            t = term.encode("utf-8")
            n = blob.count(t)
            if not n:
                continue
            pos = blob.find(t)
            ctx = blob[max(0, pos - CTX):pos + CTX].decode("utf-8", "replace").replace("\n", "\\n")
            hard_hits.append((name, term, n, ctx))
        for term in report_terms:
            t = term.encode("utf-8")
            n = blob.count(t)
            if n:
                report_hits.setdefault(term, []).append((name, n))

    print("扫描 %s（%d 个条目/文件）" % (path, total))
    print("  needle：HARD %d 条 / REPORT %d 条" % (len(hard_terms), len(report_terms)))
    print("  needle 出处：%s" % needle_source())
    for term, where in sorted(report_hits.items()):
        print("  [REPORT] %s：%s" % (term, "，".join("%s×%d" % w for w in where)))
    if not report_hits:
        print("  [REPORT] 无命中")

    if hard_hits:
        print("!! [HARD] 发行包里出现本人标识 —— 不许发布：")
        for name, term, n, ctx in hard_hits:
            print("   %s  %s ×%d" % (name, term, n))
            print("      …%s…" % ctx)
        return 1
    print("✅ 本人标识 0 命中（HARD 全清）")
    return 0


_NEEDLE_PATH = None


def needle_source():
    return _NEEDLE_PATH or DEFAULT_NEEDLES


def main() -> int:
    global _NEEDLE_PATH
    args = list(sys.argv[1:])
    needles_path = os.environ.get("STUDY_PRIVACY_NEEDLES_FILE") or DEFAULT_NEEDLES
    if "--needles-file" in args:
        i = args.index("--needles-file")
        if i + 1 >= len(args):
            print("--needles-file 后面要给路径")
            return 2
        needles_path = args[i + 1]
        del args[i:i + 2]
    if len(args) != 1:
        print(__doc__)
        return 2
    _NEEDLE_PATH = needles_path

    loaded = load_needles(needles_path)
    if loaded is None:
        print("!! needle 清单缺失或为空：%s" % needles_path)
        print("   本脚本不内联任何姓名/学号。请先建清单（照 tools/privacy_needles.example.txt 填），")
        print("   或用 --needles-file / $STUDY_PRIVACY_NEEDLES_FILE 指定。")
        return 2
    return scan(args[0], loaded[0], loaded[1])


if __name__ == "__main__":
    sys.exit(main())
