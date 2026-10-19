#!/usr/bin/env python3
"""校验烘焙出的动态帧序列：闭合 / 跳帧 / 体积 / 静止浪费。

用法:
    python3 tools/check_frames.py <帧目录> [--max-mb 6]

帧目录里应有 meta.json（{"frames": N, "step": 0.2, ...}）与 f0.png … f{N-1}.png。
只依赖 Pillow（macOS 系统 python3 已自带）。

判定线（与 docs/theme-gallery-2026-10/02、05 一致）:
  · 闭合：帧 N-1 → 帧 0 的差 ≤ 平均相邻差 × 1.5；否则桌面每轮会硬跳一次
  · 跳帧：最大相邻差 ≤ 平均相邻差 × 3
  · 体积：总体积 ≤ --max-mb（默认 6MB）
  · 静止浪费：最小相邻差 < 0.15 只警告（等于有几帧几乎一样，白占帧预算）
退出码：0 = PASS，1 = FAIL，2 = 输入有问题。
"""
import json
import os
import sys

try:
    from PIL import Image, ImageChops, ImageStat
except ImportError:
    print("需要 Pillow：python3 -m pip install --user pillow", file=sys.stderr)
    sys.exit(2)


def mean_diff(a, b):
    return ImageStat.Stat(ImageChops.difference(a, b)).mean[0]


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    d = argv[1]
    max_mb = 6.0
    if "--max-mb" in argv:
        max_mb = float(argv[argv.index("--max-mb") + 1])

    meta_path = os.path.join(d, "meta.json")
    if not os.path.exists(meta_path):
        print(f"✗ 找不到 {meta_path}", file=sys.stderr)
        return 2
    meta = json.load(open(meta_path, encoding="utf-8"))
    n = int(meta.get("frames") or 0)
    if n < 2:
        print(f"✗ meta.json 里的 frames={n}，至少要 2 帧", file=sys.stderr)
        return 2

    frames, missing = [], []
    for i in range(n):
        p = os.path.join(d, f"f{i}.png")
        if not os.path.exists(p):
            missing.append(p)
            continue
        frames.append(Image.open(p).convert("RGB"))
    if missing:
        print(f"✗ 缺 {len(missing)} 帧（meta 说 {n} 帧），第一张缺失：{missing[0]}", file=sys.stderr)
        return 2

    consec = [mean_diff(frames[i], frames[(i + 1) % n]) for i in range(n)]
    wrap = consec[-1]
    avg = sum(consec[:-1]) / (n - 1)
    size_mb = sum(os.path.getsize(os.path.join(d, f"f{i}.png")) for i in range(n)) / 1e6
    w, h = frames[0].size

    print(f"目录 {d}")
    print(f"  帧数 {n}  {w}×{h}  单帧 {w * h / 10000:.1f} 万像素  总像素 {n * w * h / 1e6:.1f}M")
    print(f"  平均相邻差 {avg:.2f}  闭合差 {wrap:.2f}  最小 {min(consec):.2f}  最大 {max(consec):.2f}")
    print(f"  总体积 {size_mb:.2f}MB  单帧均 {size_mb / n * 1000:.0f}KB")

    ok = True
    if avg <= 0.05:
        print("✗ 相邻帧几乎完全相同：画面根本没在动（或参数没生效）")
        ok = False
    if wrap > avg * 1.5:
        print(f"✗ 不闭合：闭合差 {wrap:.2f} > 平均相邻差 {avg:.2f} × 1.5，桌面每轮会硬跳一次")
        ok = False
    if max(consec) > avg * 3:
        print(f"✗ 存在跳帧：最大相邻差 {max(consec):.2f} > 平均 × 3")
        ok = False
    if size_mb > max_mb:
        print(f"✗ 体积超标 {size_mb:.2f}MB > {max_mb}MB（降颗粒强度或分辨率）")
        ok = False
    if min(consec) < 0.15:
        print(f"⚠ 最小相邻差 {min(consec):.2f} 过小：有几帧几乎一样，等于浪费帧预算")

    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
