#!/usr/bin/env python3
"""从 widget_today.xml 生成小组件的字体变体布局。

为什么是「生成一堆 XML」而不是运行时换字体：
`RemoteViews` 只允许白名单方法，`setTypeface` 不在里面（要跨进程传 Typeface 对象），
字体只能在**桌面充气我们的布局**那一刻由 `android:fontFamily` / `android:textFontWeight` 决定。
所以一款字体 = 一份布局。手抄 20 份必然「改一处忘十九处」，这里用模板生成，
并且 FontsTest 会验证：每份变体除了字体那两行，和基础布局**逐字相同**。

用法：python3 tools/gen_widget_fonts.py
"""
import re
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = ROOT / "app/src/main/res/layout/widget_today.xml"

# (id, 显示名, fontFamily, textFontWeight)
FONTS = [
    ("thin", "极细", "sans-serif-thin", 0),
    ("light", "细体", "sans-serif-light", 0),
    ("regular", "常规", "sans-serif", 400),
    ("medium", "中等", "sans-serif", 500),
    ("semibold", "半粗", "sans-serif", 600),
    ("bold", "粗体", "sans-serif", 700),
    ("black", "特粗", "sans-serif", 900),
    ("cnd_light", "窄细", "sans-serif-condensed", 300),
    ("cnd", "窄体", "sans-serif-condensed", 400),
    ("cnd_bold", "窄粗", "sans-serif-condensed", 700),
    ("cnd_black", "窄特粗", "sans-serif-condensed", 900),
    ("serif", "衬线", "serif", 400),
    ("serif_light", "衬线细", "serif", 300),
    ("serif_bold", "衬线粗", "serif", 700),
    ("serif_black", "衬线特粗", "serif", 900),
    ("mono", "等宽", "monospace", 400),
    ("mono_bold", "等宽粗", "monospace", 700),
    ("serif_mono", "等宽衬线", "serif-monospace", 0),
    ("casual", "手写", "casual", 0),
    ("cursive", "连笔", "cursive", 0),
    ("smallcaps", "小型大写", "sans-serif-smallcaps", 0),
]


def main() -> None:
    base = BASE.read_text()
    n_text = len(re.findall(r"<TextView\b", base))
    if n_text == 0:
        raise SystemExit("基础布局里没有 TextView，模板不对")
    for fid, label, fam, weight in FONTS:
        extra = f'android:fontFamily="{fam}"'
        if weight:
            extra += f' android:textFontWeight="{weight}"'
        txt = re.sub(r"<TextView\b", f"<TextView\n            {extra}", base)
        out = BASE.parent / f"widget_today_f_{fid}.xml"
        out.write_text(txt)
        print(f"{out.name:40s} {label:6s} {fam} {weight or ''}  TextView×{n_text}")


if __name__ == "__main__":
    main()
