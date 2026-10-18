#!/usr/bin/env python3
"""从 Danbooru（SFW：rating:general）批量拉二次元插画，做成本 App 的在线图库。

为什么换到 Danbooru：
  · 之前用 Openverse（CC 授权），覆盖太薄 —— 搜 anime 出来的全是白底剪贴画；
  · Wallhaven 的缩略图最大只有 432px，全图 2~5MB，几百张就要几小时；
  · Danbooru 的 `large_file_url`（sample）约 850px / 120KB，量大、下载快，且**带完整 tag**，
    正好用来做"搜得到"这件事。

注意：这些是同人/原创二次元画作，版权归画师，**本 App 只在个人范围内使用**；
清单里记下 `page`（作品页地址）与作者，用户可以点回原作出处。要商用必须逐张谈授权。

用法：
  export HTTPS_PROXY=http://127.0.0.1:7892
  python3 tools/art_pull_danbooru.py --out /tmp/art_sb --portrait 500 --landscape 500
"""
import argparse
import concurrent.futures as cf
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

API = "https://danbooru.donmai.us/posts.json"
UA = "campus-art-puller/1.0"
PROXY = os.environ.get("HTTPS_PROXY") or os.environ.get("http_proxy")

# 用来凑够数量的查询组合（都带 rating:general，SFW）
QUERIES = [
    "rating:general order:score",
    "rating:general 1girl order:score",
    "rating:general scenery order:score",
    "rating:general sky order:score",
    "rating:general city order:score",
    "rating:general night order:score",
    "rating:general solo order:score",
    "rating:general landscape order:score",
    "rating:general water order:score",
    "rating:general flower order:score",
]

# 常见 tag → 中文（搜索用中文也要能命中；没收录的 tag 原样保留）
TAG_ZH = {
    "1girl": "少女", "2girls": "双人", "1boy": "少年", "solo": "单人",
    "scenery": "风景", "landscape": "风景", "sky": "天空", "cloud": "云", "clouds": "云",
    "night": "夜晚", "night_sky": "夜空", "star": "星空", "stars": "星空", "moon": "月亮",
    "sunset": "黄昏", "sunrise": "清晨", "city": "城市", "cityscape": "城市",
    "building": "建筑", "street": "街道", "rain": "雨", "snow": "雪", "fog": "雾",
    "water": "水", "ocean": "海", "sea": "海", "beach": "海滩", "river": "河",
    "mountain": "山", "forest": "森林", "tree": "树", "trees": "树", "flower": "花",
    "flowers": "花", "cherry_blossoms": "樱花", "sakura": "樱花", "grass": "草地",
    "school_uniform": "校服", "serafuku": "水手服", "kimono": "和服", "yukata": "浴衣",
    "dress": "连衣裙", "hat": "帽子", "glasses": "眼镜", "umbrella": "雨伞",
    "cat": "猫", "dog": "狗", "animal": "动物", "bird": "鸟",
    "long_hair": "长发", "short_hair": "短发", "twintails": "双马尾", "ponytail": "马尾",
    "blue_hair": "蓝发", "blonde_hair": "金发", "black_hair": "黑发", "white_hair": "白发",
    "smile": "微笑", "looking_at_viewer": "看镜头", "from_behind": "背影",
    "fantasy": "奇幻", "sci-fi": "科幻", "mecha": "机甲", "cyberpunk": "赛博",
    "space": "太空", "autumn": "秋天", "winter": "冬天", "summer": "夏天", "spring": "春天",
    "indoors": "室内", "outdoors": "户外", "window": "窗", "book": "书", "library": "图书馆",
    "food": "食物", "cafe": "咖啡", "train": "电车", "station": "车站",
}


def opener():
    handlers = [urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})] if PROXY else []
    return urllib.request.build_opener(*handlers)


def get_json(op, url, tries=3):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            return json.load(op.open(req, timeout=40))
        except Exception as e:
            if i == tries - 1:
                print(f"  ! 查询失败 {url[:70]}: {e}", file=sys.stderr)
                return []
            time.sleep(2 + i * 2)


def collect(op, want_landscape, want_portrait):
    seen, land, port = set(), [], []
    for q in QUERIES:
        if len(land) >= want_landscape and len(port) >= want_portrait:
            break
        for page in range(1, 41):          # 每页 100 条，最多 40 页/查询
            if len(land) >= want_landscape and len(port) >= want_portrait:
                break
            url = API + "?" + urllib.parse.urlencode({"tags": q, "limit": 100, "page": page})
            posts = get_json(op, url)
            if not posts:
                break
            for p in posts:
                pid = p.get("id")
                w, h = p.get("image_width") or 0, p.get("image_height") or 0
                if not pid or pid in seen or not w or not h:
                    continue
                if p.get("rating") not in (None, "g"):
                    continue
                url_img = p.get("large_file_url") or p.get("file_url")
                if not url_img:
                    continue
                if w >= h * 1.2:
                    if len(land) >= want_landscape:
                        continue
                    land.append(p)
                elif h >= w * 1.1:
                    if len(port) >= want_portrait:
                        continue
                    port.append(p)
                else:
                    continue
                seen.add(pid)
            print(f"  查询「{q[:32]}」p{page}: 横 {len(land)} / 竖 {len(port)}", flush=True)
            time.sleep(0.4)
    return land, port


def slug(s):
    s = re.sub(r"[^a-zA-Z0-9]+", "_", s).strip("_").lower()
    return s[:20] or "art"


def make_titles(tag_string):
    tags = tag_string.split()
    zh = [TAG_ZH[t] for t in tags if t in TAG_ZH]
    # 标题取最具体的中文标签两三个，够在格子上认出是哪张
    title = "·".join(dict.fromkeys(zh))[:14] or "插画"
    keep = [t for t in tags[:14]]
    search_tags = list(dict.fromkeys(zh + [t.replace("_", " ") for t in keep]))[:10]
    return title, search_tags


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--portrait", type=int, default=500)
    ap.add_argument("--landscape", type=int, default=500)
    ap.add_argument("--workers", type=int, default=6)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    from PIL import Image

    op = opener()
    print("① 收集清单…", flush=True)
    land, port = collect(op, args.landscape, args.portrait)
    print(f"   横 {len(land)} 竖 {len(port)}", flush=True)

    jobs = [("landscape", p) for p in land] + [("portrait", p) for p in port]
    meta_path = os.path.join(args.out, "meta.jsonl")
    done = set()
    if os.path.exists(meta_path):
        for line in open(meta_path):
            try:
                done.add(json.loads(line)["id"])
            except Exception:
                pass
    jobs = [j for j in jobs if f"db_{j[1]['id']}" not in done]
    print(f"② 下载 {len(jobs)} 张（已有 {len(done)} 张，断点续跑）…", flush=True)

    out_lock = __import__("threading").Lock()

    def one(job):
        orient, p = job
        aid = f"db_{p['id']}"
        full = f"{aid}.jpg"
        thumb = f"t_{aid}.jpg"
        fp = os.path.join(args.out, full)
        try:
            if not os.path.exists(fp):
                req = urllib.request.Request(p["large_file_url"], headers={"User-Agent": UA})
                raw = op.open(req, timeout=90).read()
                if len(raw) < 20000:
                    return None
                tmp = fp + ".tmp"
                open(tmp, "wb").write(raw)
                im = Image.open(tmp).convert("RGB")
                # 竖图给手机铺满屏幕：长边 1440；横图给小组件：宽 1280
                limit = 1440 if orient == "portrait" else 1280
                if max(im.size) > limit:
                    if orient == "portrait":
                        im = im.resize((int(im.width * limit / im.height), limit), Image.LANCZOS)
                    else:
                        im = im.resize((limit, int(im.height * limit / im.width)), Image.LANCZOS)
                im.save(fp, "JPEG", quality=82, optimize=True)
                os.remove(tmp)
                th = im.copy()
                th.thumbnail((360, 360))
                th.save(os.path.join(args.out, thumb), "JPEG", quality=78, optimize=True)
            title, tags = make_titles(p.get("tag_string") or "")
            item = {
                "id": aid,
                "title": title,
                "tags": tags,
                "kind": "illustration",
                "orient": orient,
                "file": full,
                "thumb": thumb,
                "w": 0,
                "h": 0,
                "bytes": os.path.getsize(fp),
                "author": (p.get("tag_string_artist") or "").replace(" ", ", ")[:40] or "未知画师",
                "license": "同人作品 · 版权归画师",
                "page": f"https://danbooru.donmai.us/posts/{p['id']}",
                "source": "Danbooru",
            }
            with out_lock:
                with open(meta_path, "a") as f:
                    f.write(json.dumps(item, ensure_ascii=False) + "\n")
            return item
        except Exception as e:
            print(f"  ! 跳过 {aid}: {str(e)[:60]}", file=sys.stderr)
            return None

    n = 0
    with cf.ThreadPoolExecutor(max_workers=args.workers) as ex:
        for r in ex.map(one, jobs):
            if r:
                n += 1
                if n % 25 == 0:
                    print(f"   已下 {n}/{len(jobs)}", flush=True)

    items = []
    if os.path.exists(meta_path):
        for line in open(meta_path):
            items.append(json.loads(line))
    json.dump({"version": 1, "items": items}, open(os.path.join(args.out, "catalog_web.json"), "w"), ensure_ascii=False)
    print(f"完成：{len(items)} 张（横 {sum(1 for i in items if i['orient']=='landscape')} / "
          f"竖 {sum(1 for i in items if i['orient']=='portrait')}），"
          f"约 {sum(i['bytes'] for i in items)//1024//1024} MB")


if __name__ == "__main__":
    main()
