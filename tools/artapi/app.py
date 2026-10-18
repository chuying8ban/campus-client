#!/usr/bin/env python3
"""在线图库的搜索服务：把 safebooru（Danbooru 的 SFW 镜像）整个库变成 App 能搜的接口。

为什么要这一层：
  · 手机/服务器直连 danbooru.donmai.us 在国内是断的（实测 000），safebooru 却通（200）；
  · 图床 cdn.donmai.us 需要 Referer，浏览器能过、App 直接请求会被拒；
  · 搜索结果里**大部分图**用户不会点开，没必要预先全量同步到本机（那是 976 张的上限）。

接口：
  GET /search?q=少女&page=1&orient=portrait|landscape&limit=60
      → {"items":[...],"page":1,"has_more":true}
  GET /img?u=<urlencoded cdn url>     → 图片字节（带 Referer，落盘缓存）

只用标准库（服务器上装不了什么依赖，也懒得装）。
"""
import hashlib
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = "https://safebooru.donmai.us/posts.json"
REFERER = "https://safebooru.donmai.us/"
UA = "campus-art-api/1.0"
CACHE_DIR = "/var/cache/artimg"
SEARCH_TTL = 300
PORT = int(os.environ.get("ARTAPI_PORT", "8791"))

# 中文 → danbooru tag。搜索框里用户只会打中文，服务端负责翻译。
# safebooru 匿名查询有「最多 2 个 tag」的限制，所以这里一个词只映射到一个 tag。
ZH2TAG = {
    "少女": "1girl", "女孩": "1girl", "女生": "1girl", "少年": "1boy", "男生": "1boy",
    "双人": "2girls", "单人": "solo",
    "风景": "scenery", "景色": "scenery", "天空": "sky", "云": "clouds",
    "夜": "night", "夜晚": "night", "夜空": "night_sky", "星空": "star_(sky)", "星": "stars",
    "月亮": "moon", "黄昏": "sunset", "日落": "sunset", "清晨": "sunrise", "日出": "sunrise",
    "城市": "city", "街道": "street", "建筑": "building", "雨": "rain", "雪": "snow", "雾": "fog",
    "海": "ocean", "海边": "beach", "河": "river", "水": "water", "山": "mountain",
    "森林": "forest", "树": "tree", "花": "flower", "樱花": "cherry_blossoms", "草": "grass",
    "校服": "school_uniform", "水手服": "serafuku", "和服": "kimono", "浴衣": "yukata",
    "连衣裙": "dress", "帽子": "hat", "眼镜": "glasses", "雨伞": "umbrella",
    "猫": "cat", "狗": "dog", "鸟": "bird", "动物": "animal",
    "长发": "long_hair", "短发": "short_hair", "双马尾": "twintails", "马尾": "ponytail",
    "蓝发": "blue_hair", "金发": "blonde_hair", "黑发": "black_hair", "白发": "white_hair",
    "微笑": "smile", "背影": "from_behind", "看镜头": "looking_at_viewer",
    "奇幻": "fantasy", "科幻": "sci-fi", "机甲": "mecha", "赛博": "cyberpunk",
    "太空": "space", "宇宙": "space", "秋": "autumn", "秋天": "autumn",
    "冬": "winter", "冬天": "winter", "夏": "summer", "夏天": "summer", "春": "spring", "春天": "spring",
    "室内": "indoors", "户外": "outdoors", "窗": "window", "书": "book", "图书馆": "library",
    "食物": "food", "咖啡": "cafe", "电车": "train", "车站": "station", "教室": "classroom",
    "泳装": "swimsuit", "浴巾": "towel", "睡觉": "sleeping", "跳舞": "dancing", "唱歌": "singing",
    # ── 游戏 / 动画 IP（用户点名："原神这种很火的 ip 都没有"）──
    "原神": "genshin_impact", "提瓦特": "genshin_impact", "甘雨": "ganyu_(genshin_impact)",
    "雷电将军": "raiden_shogun", "芙宁娜": "furina_(genshin_impact)", "胡桃": "hu_tao_(genshin_impact)",
    "纳西妲": "nahida_(genshin_impact)", "可莉": "klee_(genshin_impact)",
    "崩铁": "honkai:_star_rail", "星穹铁道": "honkai:_star_rail", "崩坏星穹铁道": "honkai:_star_rail",
    "崩坏3": "honkai_impact_3rd", "崩坏": "honkai_(series)",
    "绝区零": "zenless_zone_zero", "鸣潮": "wuthering_waves",
    "明日方舟": "arknights", "蔚蓝档案": "blue_archive", "碧蓝档案": "blue_archive",
    "碧蓝航线": "azur_lane", "少女前线": "girls'_frontline", "战双": "punishing:_gray_raven",
    "妮姬": "nikke", "胜利女神": "nikke", "第五人格": "identity_v",
    "英雄联盟": "league_of_legends", "永劫无间": "naraka:_bladepoint",
    "初音": "hatsune_miku", "初音未来": "hatsune_miku", "镜音": "kagamine_rin",
    "fgo": "fate/grand_order", "fate": "fate_(series)", "型月": "fate_(series)",
    "lovelive": "love_live!", "偶像大师": "idolmaster",
    "鬼灭之刃": "kimetsu_no_yaiba", "鬼灭": "kimetsu_no_yaiba",
    "咒术回战": "jujutsu_kaisen", "间谍过家家": "spy_x_family",
    "芙莉莲": "sousou_no_frieren", "孤独摇滚": "bocchi_the_rock!",
    "莉可丽丝": "lycoris_recoil", "紫罗兰永恒花园": "violet_evergarden",
    "新世纪福音战士": "neon_genesis_evangelion", "eva": "neon_genesis_evangelion",
    "刀剑神域": "sword_art_online", "进击的巨人": "shingeki_no_kyojin",
    "魔法少女": "mahou_shoujo", "偶像": "idol", "女仆": "maid", "猫娘": "catgirl",
}

# 英文/拼音别名：用户可能直接打英文，或者打了拼音缩写
ALIAS = {
    "genshin": "genshin_impact", "原": "genshin_impact", "yuanshen": "genshin_impact",
    "starrail": "honkai:_star_rail", "hsr": "honkai:_star_rail", "bengtie": "honkai:_star_rail",
    "arknights": "arknights", "bluearchive": "blue_archive", "blue": "blue_archive",
    "miku": "hatsune_miku", "zzz": "zenless_zone_zero", "wuwa": "wuthering_waves",
    "nikke": "nikke", "azurlane": "azur_lane", "fgo": "fate/grand_order",
}

# 反查表：tag → 中文（给搜索结果的标题/标签用）
TAG2ZH = {}
for _zh, _tag in ZH2TAG.items():
    TAG2ZH.setdefault(_tag, _zh)

_cache = {}
_lock = threading.Lock()


def translate(q):
    """把用户输入翻成一个 danbooru tag。已经是英文/下划线的一律原样用。"""
    q = q.strip()
    if not q:
        return None
    if q in ZH2TAG:
        return ZH2TAG[q]
    # 中文子串：取最长命中（"樱花树" → 樱花）
    hits = [k for k in ZH2TAG if k in q]
    if hits:
        return ZH2TAG[max(hits, key=len)]
    low = q.replace(" ", "_").lower()
    if low in ALIAS:
        return ALIAS[low]
    return low


# 当"手机背景/小组件底图"很难看的一类：白底/透明底/纯线稿/带字。这些在 metadata 里
# 就是 tag，不用下图就能判 —— 比先下载再算像素便宜得多。（2026-10-18 抽样发现
# "最新"排序里这类占了近一半：白底人物立绘、线稿草图、带水印的图）
BAD_TAGS = {
    "white_background", "simple_background", "transparent_background", "grey_background",
    "monochrome", "greyscale", "sketch", "lineart", "comic", "text", "watermark",
    "english_text", "chinese_text", "japanese_text", "letterboxed", "border",
    "sample_watermark", "character_sheet", "reference_sheet", "multiple_views",
}


def orient_ok(p, orient):
    """这张图在这个方向里能不能用（竖图给 App 背景、横图给小组件）。"""
    w, h = p.get("image_width") or 0, p.get("image_height") or 0
    if not w or not h:
        return False
    if orient == "portrait":
        return h >= w * 1.1
    return w >= h * 1.2


def http_get(url, headers, timeout=25):
    req = urllib.request.Request(url, headers=headers)
    return urllib.request.urlopen(req, timeout=timeout).read()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    # ---------- /search ----------
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query)
        try:
            if parsed.path == "/search":
                self.handle_search(qs)
            elif parsed.path == "/img":
                self.handle_img(qs)
            elif parsed.path == "/health":
                self._send(200, b'{"ok":true}')
            else:
                self._send(404, b'{"error":"not found"}')
        except Exception as e:
            self._send(500, json.dumps({"error": str(e)[:200]}, ensure_ascii=False).encode())

    def handle_search(self, qs):
        q = (qs.get("q") or [""])[0]
        page = max(1, int((qs.get("page") or ["1"])[0]))
        orient = (qs.get("orient") or ["landscape"])[0]
        limit = min(200, max(20, int((qs.get("limit") or ["60"])[0])))

        tag = translate(q) if q.strip() else None
        # 没收录的中文词：上游是英文 tag 库，硬查只会静默 0 条。明确告诉 App 换个说法。
        if tag and any("\u4e00" <= ch <= "\u9fff" for ch in tag):
            return self._send(200, json.dumps({
                "items": [], "page": page, "has_more": False, "tag": tag,
                "hint": f"「{q.strip()}」还没收录，试试 IP 名（原神/明日方舟/初音）或题材（樱花/星空/校服）",
            }, ensure_ascii=False).encode())
        ck = f"{tag}|{page}|{orient}|{limit}"
        with _lock:
            hit = _cache.get(ck)
            if hit and time.time() - hit[0] < SEARCH_TTL:
                return self._send(200, hit[1])

        # 只用单 tag、按默认（最新）排序。
        # 实测（2026-10-18）：safebooru 对 `1girl order:score` 这种"热门排序"直接 500
        # （ActiveRecord::QueryCanceled，库太大算不动），而普通 tag 查询稳定 200。
        # 所以别加 order:* —— 换来的就是偶发整页空白。
        #
        # 一个词查不到就退一步：`night_sky` 在 safebooru 里是**零结果**（镜像库比
        # danbooru 小），但 `night` 有几十万条。所以 tag 用下划线拼的时候，失败再拿
        # 第一段试一次。
        base = tag or "1girl"
        # 让上游先替我们筛一遍背景：safebooru 支持 `-tag` 取反，一个 tag + 一个否定正好
        # 卡在"最多 2 个 tag"的限制里。`-simple_background` 顺带把白底/纯色底都排掉，
        # 本地 BAD_TAGS 只当第二道闸 —— 否则 600 条里能用的常常不到 20 条。
        cands = [f"{base} -simple_background", base]
        if tag and "_" in tag:
            head = tag.split("_")[0]
            if head != tag:
                cands.append(head)
        # 一页抓到的可能**全是横图**（用户要的是竖图），这时不能直接回空 ——
        # 往上游多翻几页，凑够一屏再算完。实测 `night_sky` 单页 100 条全横图。
        wanted = max(12, limit // 3)
        posts = []
        upstream_full = False
        upstream_err = None
        # 客户端第 page 页 = 上游第 (page-1)*3+1 .. (page-1)*3+3 页。
        # 为什么"一段一段占"而不是 page+extra：tag/orient/白底过滤会砍掉 60% 以上，
        # 若两页都从 page 和 page+1 开始，翻页会大量重复（还撞 Compose 的 key）。
        span = 6
        first = (page - 1) * span + 1
        for cand in cands:
            posts = []
            upstream_full = False
            for extra in range(span):
                url = UPSTREAM + "?" + urllib.parse.urlencode(
                    {"tags": cand, "limit": limit, "page": first + extra})
                try:
                    raw = http_get(url, {"User-Agent": UA, "Referer": REFERER, "Accept": "application/json"})
                    got = json.loads(raw)
                except Exception as e:
                    upstream_err = str(e)[:80]
                    break
                if not isinstance(got, list) or not got:
                    break
                posts.extend(got)
                upstream_full = upstream_full or len(got) >= limit
                usable = sum(1 for q in posts if orient_ok(q, orient))
                if usable >= wanted:
                    break
            if posts:
                break
        if not posts and upstream_err:
            return self._send(200, json.dumps({"items": [], "page": page, "has_more": False,
                                               "error": f"upstream {upstream_err}",
                                               "tag": tag}).encode())

        items = []
        seen = set()
        for p in posts:
            if p.get("id") in seen:
                continue
            seen.add(p.get("id"))
            w, h = p.get("image_width") or 0, p.get("image_height") or 0
            if not w or not h:
                continue
            is_port = h >= w * 1.1
            is_land = w >= h * 1.2
            if orient == "portrait" and not is_port:
                continue
            if orient == "landscape" and not is_land:
                continue
            sample = p.get("large_file_url")
            preview = p.get("preview_file_url")
            if not sample or not preview:
                continue
            # 动图（ugoira/animated）的"预览"是一张 flash-preview.png，拿来做背景就是一张占位图
            tagstr = (p.get("tag_string") or "")
            toks = set(tagstr.split())
            if "animated" in toks or preview.endswith("flash-preview.png"):
                continue
            if toks & BAD_TAGS:
                continue
            if not preview.endswith((".jpg", ".png")) or "/sample/" not in sample:
                continue
            # 标题/标签给中文：搜出来的东西要一眼认得出是什么
            zh = [TAG2ZH[t] for t in tagstr.split() if t in TAG2ZH]
            title = "·".join(dict.fromkeys(zh))[:14] or (p.get("tag_string_character") or "插画").replace("_", " ")[:12]
            items.append({
                "id": f"sb_{p['id']}",
                "title": title,
                "tags": list(dict.fromkeys(zh + tagstr.split()[:6]))[:8],
                "kind": "illustration",
                "orient": orient,
                "thumb": "/img?u=" + urllib.parse.quote(preview, safe=""),
                "full": "/img?u=" + urllib.parse.quote(sample, safe=""),
                "page": f"https://safebooru.donmai.us/posts/{p['id']}",
                "author": (p.get("tag_string_artist") or "").replace("_", " ")[:40] or "未知画师",
                "license": "同人作品 · 版权归画师",
                "score": p.get("score") or 0,
            })
        body = json.dumps({"items": items, "page": page, "has_more": upstream_full or len(items) >= 10,
                           "tag": tag}, ensure_ascii=False).encode()
        with _lock:
            _cache[ck] = (time.time(), body)
        self._send(200, body)

    # ---------- /img ----------
    def handle_img(self, qs):
        u = (qs.get("u") or [""])[0]
        if not u.startswith("https://cdn.donmai.us/"):
            return self._send(400, b'{"error":"bad url"}')
        h = hashlib.sha1(u.encode()).hexdigest()
        path = os.path.join(CACHE_DIR, h)
        if os.path.exists(path):
            data = open(path, "rb").read()
        else:
            data = http_get(u, {"User-Agent": UA, "Referer": REFERER}, timeout=40)
            os.makedirs(CACHE_DIR, exist_ok=True)
            tmp = path + ".tmp"
            open(tmp, "wb").write(data)
            os.replace(tmp, path)
        self._send(200, data, ctype="image/jpeg",
                   extra={"Cache-Control": "public, max-age=2592000"})


if __name__ == "__main__":
    os.makedirs(CACHE_DIR, exist_ok=True)
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"artapi listening 127.0.0.1:{PORT}", flush=True)
    srv.serve_forever()
