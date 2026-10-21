#!/usr/bin/env bash
# 把版本同步成 GitHub Release（用户 2026-10-21 口径：「github要自动同步」）。
#
# 为什么单独一支脚本而不是塞进 publish.sh：
#   ① 补历史要用同一套逻辑（3.4.49~3.4.76 一直没推到 GitHub，是旧账）；
#   ② 这台 Mac 连 GitHub 必须走代理，而 publish.sh 的上传走的是内网 dl/ECS ——
#      两种网络路径混在一起失败时根本分不清是代理挂了还是源站挂了。
#
# 用法：
#   tools/gh_release_sync.sh 3.4.76 --notes-file /tmp/notes_3.4.76.txt
#   tools/gh_release_sync.sh --backfill              # 补齐：GitHub 上缺的、源站上有的全部版本
#   tools/gh_release_sync.sh --backfill --from 3.4.49
#   tools/gh_release_sync.sh <ver> --force            # 覆盖已存在的同名 release
#
# 环境变量：STUDY_GH_REPO / HTTPS_PROXY / STUDY_APK_BASE
set -euo pipefail

REPO="${STUDY_GH_REPO:-chuying8ban/campus-client}"
GH="${GH_BIN:-$HOME/.local/bin/gh}"
# GitHub 直连不通（技能 github-access-china）；只给 github 走代理，本机直连。
export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7892}"
export NO_PROXY="${NO_PROXY:-127.0.0.1,localhost,::1}"
APK_BASE_DL="${STUDY_APK_BASE:-https://dl.ccbase.top/updates}"
APK_BASE_PROD="https://study.ccbase.top/updates"
CACHE="${STUDY_GH_CACHE:-$HOME/.cache/study-native/gh}"
mkdir -p "$CACHE"

cd "$(dirname "$0")/.."
[ -x "$GH" ] || GH="$(command -v gh || true)"
[ -n "$GH" ] || { echo "!! 找不到 gh（用 GH_BIN 指定）"; exit 1; }

MODE="${1:-}"
case "$MODE" in
  --backfill) shift ;;
  "") echo "用法: $0 <version> [--notes-file f] [--force] | --backfill [--from X]"; exit 1 ;;
  *) shift ;;
esac

NOTES_FILE=""
FORCE=0
FROM=""
VER_ARG="$MODE"
while [ $# -gt 0 ]; do
  case "$1" in
    --notes-file) NOTES_FILE="${2:?}"; shift 2 ;;
    --force) FORCE=1; shift ;;
    --from) FROM="${2:?}"; shift 2 ;;
    *) echo "!! 未知参数 $1"; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# 版本 → 标题 / 正文 / 提交
# ---------------------------------------------------------------------------
# 标题沿用已有风格「3.4.48 删掉三块没用的控件」：版本号 + 一句话。
summary_of() {
  local v="$1" out
  # 优先用提交标题里 `3.4.x：<一句话>` 冒号后那半句（那是当时写给人看的）
  local subj
  subj="$(git log --format=%s --all | grep -F "${v}：" | head -1 || true)"
  if [ -n "$subj" ]; then
    out="$(printf '%s' "${subj#*：}" | cut -c1-20)"
    [ "${#subj}" -gt 24 ] && out="${out}…"
    printf '%s' "$out"
    return
  fi
  # 退路：changelog 第一条
  local first
  first="$(awk -F'\t' -v v="$v" '$1==v{print $2}' app/src/main/assets/changelog.tsv | head -1 | sed 's/\\n.*//')"
  out="$(printf '%s' "${first:-更新}" | cut -c1-20)"
  printf '%s' "$out"
}

commit_of() {
  local v="$1" c
  # 最可靠的一路：提交标题就是 `3.4.49：<一句话>`（历史提交全都这么写）。
  # 注意必须写 "${v}：" —— macOS 自带 bash 3.2 会把紧跟其后的全角冒号并进变量名，
  # 结果 grep 一个空串、啥也不匹配，tag 就悄悄指到了 HEAD（这次补历史时踩过）。
  c="$(git log -1 --format=%H --grep="^${v}[：:]" 2>/dev/null || true)"
  # 退路：版本号那次提交
  [ -n "$c" ] || c="$(git log -1 --format=%H -S "versionName \"$v\"" -- app/build.gradle 2>/dev/null || true)"
  # 再退：含 versionName 的提交（--all 兜住别的分支）
  [ -n "$c" ] || c="$(git log -1 --format=%H --all -S "versionName \"$v\"" -- app/build.gradle 2>/dev/null || true)"
  [ -n "$c" ] || c="$(git rev-parse HEAD)"
  printf '%s' "$c"
}

# 正文：给了 notes 文件就用它（publish.sh 传的就是闸门验过的那份，公开口径）；
# 没给就用 changelog（补历史时用），再不行用提交标题。
body_of() {
  local v="$1" out="$CACHE/body-$v.md"
  : > "$out"
  if [ -n "$NOTES_FILE" ] && [ -f "$NOTES_FILE" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      [ -z "${line//[[:space:]]/}" ] && continue
      printf -- '- %s\n' "$line" >> "$out"
    done < "$NOTES_FILE"
  else
    local row
    row="$(awk -F'\t' -v v="$v" '$1==v{print $2}' app/src/main/assets/changelog.tsv | head -1)"
    if [ -n "$row" ]; then
      printf '%s' "$row" | awk '{gsub(/\\n/,"\n"); print}' | while IFS= read -r line; do
        [ -z "${line//[[:space:]]/}" ] && continue
        printf -- '- %s\n' "$line"
      done >> "$out"
    else
      git log --format='%s' --all | grep -F "${v}：" | while IFS= read -r line; do
        printf -- '- %s\n' "${line#*：}"
      done >> "$out"
    fi
  fi
  [ -s "$out" ] || printf -- '- 常规更新\n' > "$out"
  printf '%s' "$out"
}

# ---------------------------------------------------------------------------
# 取 APK：先 dl 镜像（快），没有再回源站（历史版本都在）
# ---------------------------------------------------------------------------
fetch_apk() {
  local v="$1" dst="$CACHE/campus-$v.apk"
  if [ -s "$dst" ] && [ "$(stat -f%z "$dst" 2>/dev/null || stat -c%s "$dst")" -gt 1000000 ]; then
    printf '%s' "$dst"; return 0
  fi
  local url
  for url in "$APK_BASE_DL/campus-$v.apk" "$APK_BASE_PROD/campus-$v.apk"; do
    # 历史版本不在 dl 镜像上（只有最近几版），第一次会 404 —— 静音，别让日志看着像故障
    if curl -fsS --max-time 300 -o "$dst.part" "$url" 2>/dev/null; then
      mv "$dst.part" "$dst"
      printf '%s' "$dst"
      return 0
    fi
    rm -f "$dst.part"
  done
  return 1
}

# 把本地提交推到 GitHub。
#
# 为什么必须有这一步：`gh release create --target <sha>` 在 GitHub 上**没这个提交**时报
# 422 target_commitish is invalid —— 实测第一次就撞上了（本机领先远端 36 个提交）。
# 推送走 github.com + token（origin 是 gh-proxy，只能读不能写，push 会要用户名）。
ensure_pushed() {
  local ahead
  # `git fetch origin main` 只更新 FETCH_HEAD、不建 origin/main，于是 `rev-list origin/main..HEAD`
  # 会报 "Needed a single revision" —— 显式 refspec 才靠得住。
  git fetch origin '+refs/heads/main:refs/remotes/origin/main' >/dev/null 2>&1 || true
  ahead="$(git rev-list --count origin/main..HEAD 2>/dev/null || echo 0)"
  [ "$ahead" -eq 0 ] && return 0
  echo "  推送 $ahead 个提交到 GitHub…"
  local tok
  tok="$("$GH" auth token)"
  GIT_TERMINAL_PROMPT=0 git -c http.proxy="$HTTPS_PROXY" \
    push "https://x-access-token:${tok}@github.com/${REPO}.git" HEAD:main >/dev/null 2>&1 \
    || { echo "  !! 推送失败（检查代理/权限）"; return 1; }
  git fetch origin --tags >/dev/null 2>&1 || true
  echo "  ✅ 已推送（HEAD=$(git rev-parse --short HEAD)）"
}

exists_upstream() { "$GH" release view "v$1" --repo "$REPO" >/dev/null 2>&1; }

publish_one() {
  local v="$1"
  if exists_upstream "$v" && [ "$FORCE" -ne 1 ]; then
    echo "  跳过 v${v}（GitHub 上已有）"
    return 0
  fi
  local apk title body commit size
  if ! apk="$(fetch_apk "$v")"; then
    echo "  !! v$v 取不到安装包（源站上也没有）"
    return 1
  fi
  title="$v $(summary_of "$v")"
  body="$(body_of "$v")"
  commit="$(commit_of "$v")"
  size="$(stat -f%z "$apk" 2>/dev/null || stat -c%s "$apk")"
  echo "  → v${v}「${title}」$(printf '%.1f' "$(echo "$size/1048576" | bc -l)")MB @ ${commit:0:7}"

  local try
  for try in 1 2 3; do
    if [ "$FORCE" -eq 1 ] && exists_upstream "$v"; then
      # gh 没有“替换资产”的顺手命令：先删 release 再建（tag 会一起没了，正好重建）
      "$GH" release delete "v$v" --repo "$REPO" --yes >/dev/null 2>&1 || true
    fi
    if "$GH" release create "v$v" "$apk" \
        --repo "$REPO" --title "$title" --notes-file "$body" \
        --target "$commit" >/dev/null 2>&1; then
      # 回读校验：非要看到资产列表里真有这只包才算成
      if "$GH" release view "v$v" --repo "$REPO" --json assets \
           -q '.assets[].name' 2>/dev/null | grep -qx "campus-$v.apk"; then
        echo "  ✅ GitHub Release v$v 已就绪（含 campus-$v.apk）"
        # 包已经在 GitHub 上了，本地这份缓存没有第二次用途：28 个版本就是 800MB，
        # 留着只会把磁盘塞满（这台机器的旧机空间本来就紧）。
        rm -f "$apk"
        return 0
      fi
      echo "  !! v$v release 建好了但资产里没有 campus-$v.apk（第 $try 次）"
      "$GH" release delete "v$v" --repo "$REPO" --yes >/dev/null 2>&1 || true
    else
      echo "  !! v$v 创建失败（第 $try 次），5 秒后重试"
    fi
    sleep 5
  done
  return 1
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
if [ "$MODE" != "--backfill" ]; then
  echo "=== GitHub Release 同步：v$VER_ARG ==="
  ensure_pushed || exit 1
  publish_one "$VER_ARG"
  exit $?
fi

echo "=== GitHub Release 补齐 ==="
ensure_pushed || exit 1
# 源站有哪些版本（以文件名反推），和 GitHub 上已有的比差集
list_versions() {
  ssh -o ConnectTimeout=15 club 'ls /var/www/dl/updates/*.apk 2>/dev/null' 2>/dev/null \
    | sed -E 's#.*campus-([0-9.]+)\.apk#\1#' || true
  ssh -o ConnectTimeout=15 aliyun 'ls /var/www/updates/*.apk 2>/dev/null' 2>/dev/null \
    | sed -E 's#.*campus-([0-9.]+)\.apk#\1#' || true
}
ALL="$(list_versions | sort -uV)"
[ -n "$ALL" ] || { echo "!! 拿不到源站版本列表"; exit 1; }
HAVE="$("$GH" release list --repo "$REPO" --limit 200 --json tagName -q '.[].tagName' | sed 's/^v//' | sort -uV)"

fail=0
for v in $ALL; do
  case "$v" in 3.4.*) ;; *) continue ;; esac
  if [ -n "$FROM" ]; then
    # 只看 >= FROM 的版本
    [ "$(printf '%s\n%s\n' "$FROM" "$v" | sort -V | head -1)" = "$FROM" ] || continue
  fi
  grep -qx "$v" <<< "$HAVE" && continue
  publish_one "$v" || fail=$((fail+1))
done
[ "$fail" -eq 0 ] && echo "✅ 补齐完成" || { echo "⚠️ $fail 个版本没补上"; exit 1; }
