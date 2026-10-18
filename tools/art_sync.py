#!/usr/bin/env python3
"""把「网上免费可用的图库」同步到自己的服务器上，供 App 内搜索使用。

为什么要同步而不是让 App 直连：
  · App 走的是无 SNI 的内网链路，直连国外图库在校园网/移动网下根本不通；
  · 第三方图库随时限流/改协议，直连会让"图库"变成时好时坏的东西；
  · 授权信息（作者、协议、原页面）必须随图一起存，否则分发出去没法交代。

源：Openverse（openverse.org，聚合 Flickr/Wikimedia 等 CC 授权作品，无需 key）。
只收 cc0 / pdm / by / by-sa，并记下作者与原始页面；下载后统一压到 1280 宽。

用法：
  python3 tools/art_sync.py --out /tmp/art_sync --want 24
  # 之后把生成的文件传到服务器 /var/www/updates/art/（见脚本末尾提示）
"""
import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request

PROXY = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
API = "https://api.openverse.org/v1/images/"
UA = "campus-art-sync/1.0 (contact: campus app)"

# 两组查询词，两种用途：
#  · photo  —— 给 App 页面背景（真实摄影，铺满屏幕好看、压字也稳）
#  · illustration —— 给桌面小组件（插画感）
# 实测（2026-10-18）：Openverse 上的"anime"多是 CC0 剪贴画/白底素材，质量不行；
# 真正能看的是摄影类，所以两组的取词策略不同 —— 插画类只收画面有内容的（见 quality_ok）。
TERMS = {
    "photo": [
        "japanese street night", "sakura trees", "mount fuji", "starry night sky",
        "autumn leaves japan", "tokyo skyline", "misty mountains", "ocean sunset",
        "cherry blossom path", "snow forest",
    ],
    "illustration": [
        "anime illustration", "anime landscape", "manga art", "japanese illustration",
        "cyberpunk illustration", "pastel illustration",
    ],
}
LICENSES = "cc0,pdm,by,by-sa"
MIN_W, MIN_H = 1200, 700


def opener():
    handlers = [urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})] if PROXY else []
    return urllib.request.build_opener(*handlers)


def fetch(url, op, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    return op.open(req, timeout=timeout)


def search(term, op, page_size=20):
    q = urllib.parse.urlencode({
        "q": term, "license": LICENSES, "page_size": page_size, "safe_search": "true",
    })
    try:
        return json.load(fetch(API + "?" + q, op)).get("results", [])
    except Exception as e:
        print(f"  ! 查询失败 {term}: {e}", file=sys.stderr)
        return []


def quality_ok(im):
    """挡掉"白底剪贴画/素材图"：这类图铺满屏幕是一片白，压白字也看不见。

    判据两条（都很便宜）：
      · 近白像素占比 > 55% → 剪贴画/图表/封面；
      · 颜色标准差 < 12 → 纯色块、灰度图。
    真实照片几乎不可能同时满足这两条。
    """
    small = im.convert("RGB").resize((64, 64))
    px = list(small.getdata())
    near_white = sum(1 for r, g, b in px if r > 235 and g > 235 and b > 235)
    if near_white / len(px) > 0.55:
        return False
    import statistics
    vals = [c for p in px for c in p]
    if statistics.pstdev(vals) < 12:
        return False
    return True


def _license_label(code, version):
    """Openverse 给的是 cc0/by-sa 这种码，显示要写成人能读的「CC0 1.0」「CC BY-SA 4.0」。"""
    c = (code or "").lower()
    v = (version or "").strip()
    if c == "cc0":
        return f"CC0 {v}".strip()
    if c == "pdm":
        return "公共领域"
    if not c:
        return "未标注"
    return f"CC {c.upper()} {v}".strip()


def slug(s):
    s = re.sub(r"[^a-zA-Z0-9]+", "_", s).strip("_").lower()
    return s[:24] or "art"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--want", type=int, default=24)
    ap.add_argument("--kind", choices=["photo", "illustration"], default="photo")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    from PIL import Image  # 延迟导入：只是为了让 --help 在没有 Pillow 时也能用

    op = opener()
    seen_urls, picked = set(), []
    for term in TERMS[args.kind]:
        if len(picked) >= args.want:
            break
        for r in search(term, op):
            if len(picked) >= args.want:
                break
            u = r.get("url")
            w, h = r.get("width") or 0, r.get("height") or 0
            if not u or u in seen_urls:
                continue
            if w < MIN_W or h < MIN_H:
                continue
            # 只收横图（App 背景/小组件都是横的），比例 1.2~2.4
            ratio = w / max(h, 1)
            if not (1.2 <= ratio <= 2.4):
                continue
            seen_urls.add(u)
            picked.append((term, r))

    items, n = [], 0
    for term, r in picked:
        aid = f"web_{n:02d}_{slug(r.get('title') or term)}"
        try:
            raw = fetch(r["url"], op).read()
            if len(raw) < 60_000:
                continue
            tmp = os.path.join(args.out, aid + ".src")
            open(tmp, "wb").write(raw)
            im = Image.open(tmp).convert("RGB")
            if not quality_ok(im):
                os.remove(tmp)
                print(f"  · 过滤（白底/纯色）{aid}", file=sys.stderr)
                continue
            if im.width > 1280:
                im = im.resize((1280, int(im.height * 1280 / im.width)), Image.LANCZOS)
            full = aid + ".jpg"
            im.save(os.path.join(args.out, full), "JPEG", quality=84, optimize=True)
            th = im.copy()
            th.thumbnail((360, 360))
            th.save(os.path.join(args.out, "t_" + full), "JPEG", quality=80, optimize=True)
            os.remove(tmp)
        except Exception as e:
            print(f"  ! 跳过 {aid}: {e}", file=sys.stderr)
            continue

        title = (r.get("title") or term).strip()[:20] or term
        items.append({
            "id": aid,
            "title": title,
            "tags": [t for t in term.split()] + ["网图", "摄影" if args.kind == "photo" else "插画"],
            "kind": args.kind,
            "file": full,
            "thumb": "t_" + full,
            "w": im.width,
            "h": im.height,
            "bytes": os.path.getsize(os.path.join(args.out, full)),
            "author": (r.get("creator") or "未知作者")[:40],
            "license": _license_label(r.get("license"), r.get("license_version")),
            "page": r.get("foreign_landing_url") or "",
            "source": "Openverse",
        })
        n += 1

    meta = {"version": 1, "synced": len(items), "items": items}
    out_name = f"web_catalog_{args.kind}.json"
    json.dump(meta, open(os.path.join(args.out, out_name), "w"), ensure_ascii=False, indent=1)
    print(f"完成：{len(items)} 张，约 {sum(i['bytes'] for i in items)//1024} KB")
    print("下一步：scp 整个目录到服务器 → 与 art/catalog.json 合并（见 tools/art_merge.py）")


if __name__ == "__main__":
    main()
