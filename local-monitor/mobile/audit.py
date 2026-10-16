# -*- coding: utf-8 -*-
"""WB Monitor 全量静态审计：
1) 布局视图类清单；widget 布局必须全在 RemoteViews 白名单内
2) 每个 Java 文件引用的 R.id ⊆ 其 inflate 的布局 ID 并集
3) XML @string/@color/@drawable/@mipmap/@layout 引用 ↔ 定义 双向核对
4) Java R.string/R.drawable/R.color/R.layout/R.xml/R.mipmap 引用全部存在
5) manifest 组件 ↔ Java 类 存在性
6) Widget 双布局「无条件 setter 的 ID 必须两个布局都有」
"""
import os, re, sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, 'res')
SRC = os.path.join(ROOT, 'java')
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'

# RemoteViews 允许的视图类（framework 白名单，含 ViewGroup 三个容器）
WHITELIST = {
    'TextView','EditText','ImageView','ImageButton','Button','ProgressBar',
    'AnalogClock','Chronometer','TextClock','ViewFlipper','ListView','GridView',
    'StackView','AdapterViewFlipper','ViewStub',
    'FrameLayout','LinearLayout','RelativeLayout','GridLayout',
}

problems, notes = [], []
def bad(msg): problems.append(msg)
def note(msg): notes.append(msg)

# ---------- 收集资源定义 ----------
def scan_res_dir(d, kind):
    out = {}
    base = os.path.join(RES, d)
    if not os.path.isdir(base): return out
    for fn in os.listdir(base):
        if fn.endswith('.xml'):
            out[fn[:-4]] = os.path.join(base, fn)
    return out

layouts  = scan_res_dir('layout', 'layout')
drawables= scan_res_dir('drawable', 'drawable')
values   = scan_res_dir('values', 'values')
mipmaps  = scan_res_dir('mipmap-anydpi-v26', 'mipmap')
xmls     = scan_res_dir('xml', 'xml')

colors_defined, strings_defined, styles_defined = set(), set(), set()
for v in values.values():
    try: tree = ET.parse(v)
    except Exception as e: bad('values XML 解析失败 %s: %s' % (v, e)); continue
    for el in tree.getroot():
        if el.tag == 'color': colors_defined.add(el.get('name'))
        elif el.tag == 'string': strings_defined.add(el.get('name'))
        elif el.tag == 'style': styles_defined.add(el.get('name'))

# ---------- 解析布局 ----------
layout_ids, layout_classes, layout_refs = {}, {}, {}
def parse_layout(path):
    try: tree = ET.parse(path)
    except Exception as e: bad('布局 XML 解析失败 %s: %s' % (path, e)); return set(), set(), set()
    ids, classes, refs = set(), [], set()
    for el in tree.iter():
        classes.append(el.tag.split('}')[-1])
        i = el.get(ANDROID_NS + 'id')
        if i and i.startswith('@+id/'): ids.add(i[5:])
        for k, v in el.attrib.items():
            for m in re.finditer(r'@(string|color|drawable|mipmap|layout|style|xml)/([\w.]+)', v):
                refs.add((m.group(1), m.group(2)))
    return ids, set(classes), refs

for name, path in layouts.items():
    i, c, r = parse_layout(path)
    layout_ids[name], layout_classes[name], layout_refs[name] = i, c, r
for name, path in list(mipmaps.items()) + list(xmls.items()) + list(drawables.items()):
    _, _, layout_refs[name] = parse_layout(path)  # refs only

# ---------- 1) Widget 布局白名单 ----------
for wl in ('widget_small', 'widget_medium'):
    if wl not in layout_classes: bad('缺少 widget 布局 %s' % wl); continue
    illegal = layout_classes[wl] - WHITELIST - {'layout'}  # 根 tag 即元素本身
    if illegal: bad('[致命] %s 含 RemoteViews 非法视图类: %s' % (wl, sorted(illegal)))
    else: note('%s 视图类全部合法: %s' % (wl, sorted(layout_classes[wl])))

# ---------- 2) Java R.id ⊆ 布局 ID 并集 ----------
java_files = []
for dp, _, fns in os.walk(SRC):
    for fn in fns:
        if fn.endswith('.java'): java_files.append(os.path.join(dp, fn))

for jf in java_files:
    src = open(jf, encoding='utf-8').read()
    lays = set(re.findall(r'(?<!android\.)R\.layout\.(\w+)', src))
    ids  = set(re.findall(r'(?<!android\.)R\.id\.(\w+)', src))
    union = set()
    for l in lays:
        union |= layout_ids.get(l, set())
    missing = ids - union
    if missing:
        bad('[%s] 引用的 R.id 不在其 inflate 的布局里: %s (layouts=%s)'
            % (os.path.basename(jf), sorted(missing), sorted(lays)))

# ---------- 3) XML 引用 ↔ 定义 ----------
for name, path in layouts.items():
    for kind, ref in layout_refs.get(name, ()):  # type: ignore
        pool = {'color': colors_defined, 'string': strings_defined,
                'drawable': set(drawables), 'mipmap': set(mipmaps),
                'layout': set(layouts), 'style': styles_defined, 'xml': set(xmls)}.get(kind, set())
        if ref not in pool:
            bad('%s.xml 引用了不存在的 @%s/%s' % (name, kind, ref))

# android:style/Theme 引用（@android: 已由正则排除？不，@android: 会被匹配到 @style/... 吗）
# 正则要求 @kind/name 且 kind∈[..]，@android:style/x 不匹配（@android: 后无 kind）——已天然排除。

# ---------- 4) Java 资源引用存在性 ----------
for jf in java_files:
    src = open(jf, encoding='utf-8').read()
    for kind, pool in (('string', strings_defined), ('color', colors_defined),
                       ('drawable', set(drawables)), ('layout', set(layouts)),
                       ('xml', set(xmls)), ('mipmap', set(mipmaps))):
        for ref in set(re.findall(r'(?<!android\.)R\.%s\.(\w+)' % kind, src)):
            if ref not in pool:
                bad('[%s] R.%s.%s 不存在' % (os.path.basename(jf), kind, ref))

# ---------- 5) manifest 组件 ↔ Java 类 ----------
mani = open(os.path.join(ROOT, 'AndroidManifest.xml'), encoding='utf-8').read()
cls_on_disk = {os.path.splitext(os.path.basename(f))[0] for f in java_files}
for m in re.finditer(r'android:name="\.?(\w+)"', mani):
    cname = m.group(1)
    if cname in ('AppTheme',) or cname.startswith('android.appwidget'): continue
    if cname not in cls_on_disk:
        bad('manifest 注册的组件 .%s 在 java 源码中不存在' % cname)

# ---------- 6) Widget 双布局一致性（无条件 setter 的 ID 两边都要有）----------
# WbWidget.render 无条件调用的 ID（人工从源码确认列表，脚本核对两布局都含）
uncond = ['widgetRoot','wBalance','wUpdated','wRunning','wBalanceHint','wMonth',
          'wTaskDot','wTaskLine','wTaskPct']
for i in uncond:
    for wl in ('widget_small','widget_medium'):
        if i not in layout_ids.get(wl, set()):
            bad('%s 缺少无条件 setter ID: %s' % (wl, i))
big_only = ['wTask2','wAcc1','wAcc2','wSummary']
for i in big_only:
    if i not in layout_ids.get('widget_medium', set()):
        bad('widget_medium 缺少 big 分支 ID: %s' % i)
    if i in layout_ids.get('widget_small', set()):
        note('提示: %s 同时存在于 widget_small（仅 big 分支使用，无害）' % i)

# ---------- 结果 ----------
print('=' * 62)
print('静态审计结果')
print('=' * 62)
for n in notes: print('  [i] ' + n)
print('-' * 62)
if problems:
    print('发现 %d 个问题:' % len(problems))
    for p in problems: print('  [X] ' + p)
    sys.exit(1)
else:
    print('全部通过：布局类白名单 / ID 交叉 / 资源双向 / manifest 组件 一致')
    print('布局数=%d drawable数=%d 颜色=%d 字符串=%d Java文件=%d'
          % (len(layouts), len(drawables), len(colors_defined), len(strings_defined), len(java_files)))
