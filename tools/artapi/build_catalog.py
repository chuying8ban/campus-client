#!/usr/bin/env python3
"""
把「精选清单」从近千条扩到数千条 —— 不下载任何图片，只把清单写厚。

为什么要有这个：用户看到入口写着"上千张"，进去数一数只有 976 张，直接问"怎么那么少"。
图库真正的库量是 1200 万张（safebooru），但精选清单只有本地存过图的那批。

做法：按一批固定 tag（IP / 题材 / 场景）翻页抓元数据，过滤掉白底/线稿/带字，把每条
写成一个指向本机 `/api/art/img?u=` 的条目 —— 图仍然"不入包"，也不落盘，第一次看图时
由 artapi 代理下来并缓存。原有那批"本地有图、秒开"的条目全部保留并排在最前面。

用法（服务器上）：
    sudo python3 /opt/artapi/build_catalog.py --limit 3600
写回 /var/www/updates/art/catalog.json（先备份成 catalog.old.json）。
"""
import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

UP = "https://safebooru.donmai.us"
UA = "campus-art-api/1.0 (catalog builder)"
# 和 app.py 保持一致的过滤口径：白底、线稿、带字、拼图、动图都不要
BAD_TAGS = {
    "white_background", "simple_background", "transparent_background", "grey_background",
    "monochrome", "greyscale", "sketch", "lineart", "comic", "text", "watermark",
    "english_text", "chinese_text", "japanese_text", "letterboxed", "border",
    "sample_watermark", "character_sheet", "reference_sheet", "multiple_views",
    "animated", "animated_gif", "photo", "realistic", "cosplay", "3d",
}
TAGS = [
    # 游戏 IP
    "genshin_impact", "honkai:_star_rail", "honkai_impact_3rd", "zenless_zone_zero",
    "wuthering_waves", "arknights", "blue_archive", "azur_lane", "girls'_frontline",
    "punishing:_gray_raven", "nikke", "fate/grand_order", "umamusume",
    "project_sekai", "love_live!", "idolmaster", "vocaloid", "hatsune_miku",
    # 动画 IP
    "kimetsu_no_yaiba", "jujutsu_kaisen", "spy_x_family", "sousou_no_frieren",
    "bocchi_the_rock!", "lycoris_recoil", "violet_evergarden", "evangelion",
    "sword_art_online", "shingeki_no_kyojin", "bungou_stray_dogs", "oshinoko",
    # 题材 / 场景
    "1girl", "scenery", "landscape", "sky", "cloud", "night", "night_sky", "starry_sky",
    "moon", "sunset", "sunrise", "rain", "snow", "cherry_blossoms", "flower",
    "forest", "ocean", "beach", "mountain", "city", "cityscape", "street",
    "building", "room", "window", "school_uniform", "kimono", "cat", "dog",
    "cat_ears", "umbrella", "train", "bicycle", "food", "book", "coffee",
    "cyberpunk", "fantasy", "magic", "mecha", "space", "water", "lake",
    "autumn", "winter", "spring", "summer", "aqua_eyes", "long_hair",
]
MAX_TAGS = 2  # safebooru 匿名最多 2 个 tag


def fetch(tags, page, limit=100):
    q = urllib.parse.urlencode({"tags": tags, "limit": limit, "page": page})
    req = urllib.request.Request(
        f"{UP}/posts.json?{q}",
        headers={"User-Agent": UA, "Referer": "https://safebooru.donmai.us/"},
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        d = json.loads(r.read().decode())
    return d if isinstance(d, list) else []


def orient_of(w, h):
    if not w or not h:
        return None
    if h >= w * 1.1:
        return "portrait"
    if w >= h * 1.2:
        return "landscape"
    return None


def good(p):
    tags = set((p.get("tag_string") or "").split())
    if tags & BAD_TAGS:
        return None
    prev = p.get("preview_file_url") or ""
    if "flash-preview" in prev:
        return None
    large = p.get("large_file_url") or ""
    if not large or not large.lower().endswith((".jpg", ".png", ".jpeg")):
        return None
    o = orient_of(p.get("image_width"), p.get("image_height"))
    if not o:
        return None
    return o


def proxy(u):
    return "/api/art/img?u=" + urllib.parse.quote(u, safe="")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=3600)
    ap.add_argument("--pages", type=int, default=4, help="每个 tag 每档翻几页")
    ap.add_argument("--out", default="/var/www/updates/art/catalog.json")
    args = ap.parse_args()

    old = {"version": 1, "items": []}
    if os.path.exists(args.out):
        try:
            old = json.load(open(args.out, encoding="utf-8"))
        except Exception:
            pass
    keep = [it for it in old.get("items", []) if it.get("file") and not it["file"].startswith("/")]
    new = []
    seen = set()
    base_id = 0
    t0 = time.time()
    for tag in TAGS:
        if len(keep) + len(new) >= args.limit:
            break
        for orient in ("portrait", "landscape"):
            for page in range(1, args.pages + 1):
                if len(keep) + len(new) >= args.limit:
                    break
                try:
                    posts = fetch(f"{tag} -simple_background", page)
                except Exception as e:
                    print(f"! {tag} p{page}: {e}", file=sys.stderr)
                    continue
                for p in posts:
                    o = good(p)
                    if o != orient:
                        continue
                    pid = p.get("id")
                    if not pid or pid in seen:
                        continue
                    seen.add(pid)
                    base_id += 1
                    url = p.get("large_file_url") or p.get("file_url")
                    new.append({
                        "id": f"sb_{pid}",
                        "title": f"{tag.replace('_', ' ')} #{base_id}",
                        "tags": [t.replace("_", " ") for t in tag.split()],
                        "file": proxy(url),
                        "thumb": proxy(p.get("preview_file_url") or url),
                        "w": p.get("image_width") or 0,
                        "h": p.get("image_height") or 0,
                        "bytes": 0,
                        "author": "画师见作品页",
                        "license": "同人作品 · 版权归画师 · 仅限个人使用",
                        "orient": o,
                        "kind": "illustration",
                        "score": int(p.get("score") or 0),
                    })
                time.sleep(0.35)  # 别把镜像打急
        print(f"  {tag}: 累计 {len(keep) + len(new)} 条", flush=True)

    items = keep + new
    out = {"version": 1, "items": items}
    tmp = args.out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, args.out)
    try:
        os.chmod(args.out, 0o644)
    except Exception:
        pass
    print(f"写入 {args.out}：{len(items)} 条（本地图 {len(keep)} + 代理图 {len(new)}），"
          f"{os.path.getsize(args.out)} 字节，{time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
