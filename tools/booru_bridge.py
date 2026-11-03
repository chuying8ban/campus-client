#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""booru 适配层：把 safebooru.org 的 dapi 转成 Danbooru 的 /posts.json 形状。

## 为什么要这一层

2026-11-03 用户报「图库里面的图咋消失了」。查下来是上游变了：
**safebooru.donmai.us 全站挂了 Cloudflare 人机验证**，从阿里云、从轻量那台、
从本机 Mac 打过去一律 `403 Just a moment...`，连图片 CDN（cdn.donmai.us）也是 403。
所以 artapi 的搜索拿不到一条数据，App 图库就是空的。

换源时选了 **safebooru.org**（同名但不是一家，老 safebooru，dapi 照样免费）：
EC2/ECS 直连 200、图片 200、`limit`/`pid`/`tags`/`-tag` 取反都支持。
代价是它的 JSON 是 gelbooru 那套字段，而且 `score` 是 null。

所以不重写 app.py 的业务逻辑（那里有 tag 映射、空词种子轮换、竖版筛、动图甄别），
只在中间放这一层：app.py 照旧请求 `/posts.json?tags=&limit=&page=`，这里翻成 dapi
去问 safebooru.org，再按 danbooru 的字段名返回。

## 字段映射

| danbooru | safebooru.org | 备注 |
|---|---|---|
| id | id | |
| created_at | change | epoch 秒 → ISO8601 |
| score | — | 上游是 null；给 0，"评分"排序退化为按时间 |
| width/height | width/height | 竖版筛选靠这两个 |
| file_url | file_url | |
| large_file_url | sample_url | 没有 sample（原图≤sample 尺寸）就回落原图 |
| preview_file_url | preview_url | |
| tag_string | tags | |
| tag_string_artist / _character | — | 上游没有，给空串（app 侧会回落"未知画师"/"插画"） |

## 用法

    python3 booru_bridge.py            # 监听 127.0.0.1:8899，GET /posts.json

只绑回环：它没有任何鉴权，不能让外面直接打到。
"""
import json
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UP = "https://safebooru.org/index.php"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8899

# app.py 会并发抓好几页；上游对突发不友好，串一下行，别把 IP 打成 429。
_LOCK = threading.Lock()
_LAST = [0.0]
MIN_GAP = 0.30


def _throttle():
    with _LOCK:
        gap = time.time() - _LAST[0]
        if gap < MIN_GAP:
            time.sleep(MIN_GAP - gap)
        _LAST[0] = time.time()


def fetch_posts(qs):
    tags = (qs.get("tags") or [""])[0]
    try:
        limit = max(1, min(int((qs.get("limit") or ["100"])[0] or 100), 100))
    except ValueError:
        limit = 100
    try:
        page = max(1, int((qs.get("page") or ["1"])[0] or 1))
    except ValueError:
        page = 1

    q = urllib.parse.urlencode({
        "page": "dapi", "s": "post", "q": "index", "json": "1",
        "limit": limit, "pid": page - 1, "tags": tags,
    })
    _throttle()
    req = urllib.request.Request(UP + "?" + q, headers={
        "User-Agent": UA,
        "Referer": "https://safebooru.org/",
        "Accept": "application/json",
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read().decode("utf-8", "replace")
    if not raw.strip():
        return []
    data = json.loads(raw)
    if not isinstance(data, list):
        return []

    out = []
    for p in data:
        try:
            ch = int(p.get("change") or 0)
        except (TypeError, ValueError):
            ch = 0
        out.append({
            "id": p.get("id"),
            "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ch)) if ch else "",
            "score": 0,
            "rating": p.get("rating") or "general",
            "width": p.get("width") or 0,
            "height": p.get("height") or 0,
            # app.py 读的是 danbooru 的 image_width/image_height（竖版筛选就靠它俩），
            # 不是 gelbooru 的 width/height —— 少这一对，整页都会被过滤成空。
            "image_width": p.get("width") or 0,
            "image_height": p.get("height") or 0,
            "file_url": p.get("file_url") or "",
            "large_file_url": p.get("sample_url") or p.get("file_url") or "",
            "preview_file_url": p.get("preview_url") or p.get("file_url") or "",
            "tag_string": p.get("tags") or "",
            "tag_string_artist": "",
            "tag_string_character": "",
            # app.py 的动态分支按 file_ext 判 mp4/webm/gif（safebooru.org 只有 gif，
            # 没有 mp4 投稿）。不给这个字段，动图页永远 0 条。
            "file_ext": (p.get("file_url") or "").rsplit(".", 1)[-1].lower()
                        if "." in (p.get("file_url") or "") else "",
            "is_animated": "animated" in (p.get("tags") or "").split(),
        })
    return out


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        sys.stderr.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % a))

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if not u.path.endswith("/posts.json"):
            self.send_error(404)
            return
        qs = urllib.parse.parse_qs(u.query)
        try:
            items = fetch_posts(qs)
            body = json.dumps(items, ensure_ascii=False).encode()
            code = 200
        except Exception as e:  # 上游挂了要让 app.py 看到"没数据"，而不是 500 打断整页
            sys.stderr.write("upstream fail: %r\n" % (e,))
            body = b"[]"
            code = 200
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
    sys.stderr.write("booru bridge on 127.0.0.1:%d -> safebooru.org\n" % PORT)
    srv.serve_forever()
