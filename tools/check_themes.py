#!/usr/bin/env python3
"""上线前核对「精选主题」：清单能取、8 套的图和封面都在、尺寸对、字段合法。

为什么单独一个脚本而不是只靠单测：单测喂的是假清单，服务器上那 8 个目录
是不是真的存在、nginx 有没有把 /themes/ 配错，只有真去取一遍才知道。
"""
import json, urllib.request, sys, io
from PIL import Image

BASE = "https://dl.ccbase.top/themes"
OK_TYPES = {"image", "anim", "solid"}


def get(url, head=False):
    req = urllib.request.Request(url, method="HEAD" if head else "GET")
    return urllib.request.urlopen(req, timeout=20)


def main():
    raw = get(BASE + "/index.json").read().decode("utf-8")
    d = json.loads(raw)
    items = d.get("items") or []
    print("清单：%d 套  (version=%s updated=%s)" % (len(items), d.get("version"), d.get("updated")))
    assert items, "清单是空的"
    ids = set()
    bad = []
    for it in items:
        pid = it.get("id", "")
        if not pid or pid in ids:
            bad.append("id 缺失或重复：%r" % pid)
            continue
        ids.add(pid)
        if not it.get("name"):
            bad.append("%s 没有名字" % pid)
        mode = it.get("mode")
        if mode not in ("dark", "light", "system"):
            bad.append("%s mode=%r" % (pid, mode))
        acc = it.get("accent", "")
        if not (acc == "default" or acc.startswith("preset:") or acc.startswith("diy:")):
            bad.append("%s accent=%r" % (pid, acc))
        a = it.get("alpha", 0.5)
        if not (0.1 <= a <= 1.0):
            bad.append("%s alpha=%r" % (pid, a))
        bg = it.get("bg") or {}
        if bg.get("type") not in OK_TYPES:
            bad.append("%s bg.type=%r" % (pid, bg.get("type")))
            continue
        if bg["type"] != "image":
            continue
        for key, want in (("url", (1080, 1920)), ("cover", (480, 270))):
            u = bg.get(key)
            if not u:
                bad.append("%s 缺 bg.%s" % (pid, key))
                continue
            try:
                r = get(u)
                blob = r.read()
                im = Image.open(io.BytesIO(blob))
                if im.size != want:
                    bad.append("%s %s 尺寸 %s != %s" % (pid, key, im.size, want))
                print("  %-12s %-6s %-7s %s %6.1f KB" % (pid, key, im.size, im.format, len(blob) / 1024))
            except Exception as e:
                bad.append("%s %s 取不到：%s" % (pid, key, e))
    if bad:
        print("\n✗ 有问题：")
        for b in bad:
            print("  -", b)
        sys.exit(1)
    print("\n✓ %d 套全部核对通过：清单、背景 1080x1920、封面 480x270、字段合法" % len(items))


if __name__ == "__main__":
    main()
