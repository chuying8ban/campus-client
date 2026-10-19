#!/usr/bin/env bash
# 发一条公告给所有装了校园助手的手机（App 下次打开就弹）。
#
# 用法：
#   ./tools/notice.sh "标题" "正文" [链接] [图片地址]
#
# · 链接留空就不显示按钮；图片要能被手机直接下载（放 https://study.ccbase.top/updates/ 下的图最稳）
# · 内容存在服务端 /opt/artapi/notices.json，App 只读，不做推送通道
set -euo pipefail
TITLE="${1:?需要标题}"
BODY="${2:?需要正文}"
LINK="${3:-}"
IMG="${4:-}"
KEY="${STUDY_SSH_KEY:-$HOME/.ssh/study_deploy_ed25519}"
HOST="${STUDY_HOST:-root@8.153.204.182}"

python3 - "$TITLE" "$BODY" "$LINK" "$IMG" <<'PY' > /tmp/notice_payload.json
import json, sys, time, random
title, body, link, img = sys.argv[1:5]
print(json.dumps({
    "id": "%d-%04d" % (int(time.time()), random.randint(0, 9999)),
    "title": title, "body": body, "link": link, "image": img,
    "ts": int(time.time()),
}, ensure_ascii=False))
PY

scp -q -i "$KEY" /tmp/notice_payload.json "$HOST:/tmp/notice_payload.json"
ssh -i "$KEY" "$HOST" 'python3 - <<PY
import json, io
new = json.load(open("/tmp/notice_payload.json"))
p = "/opt/artapi/notices.json"
try:
    items = json.load(io.open(p, encoding="utf-8"))
except Exception:
    items = []
items.append(new)
items = sorted(items, key=lambda x: x.get("ts") or 0, reverse=True)[:100]
json.dump(items, io.open(p, "w", encoding="utf-8"), ensure_ascii=False)
print("已发布：", new["id"], new["title"], "（共", len(items), "条）")
PY'
rm -f /tmp/notice_payload.json
