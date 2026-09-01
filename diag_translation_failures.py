import re, sys

TPL = 'android/assets/jsons/translations/template.properties'
LANG_FILES = {
    'Simplified_Chinese': 'android/assets/jsons/translations/Simplified_Chinese.properties',
    'Traditional_Chinese': 'android/assets/jsons/translations/Traditional_Chinese.properties',
}

def load_lines(path):
    return open(path, encoding='utf-8').read().splitlines()

def find_in_file(lines, needle):
    """find first line index (1-based) that starts with needle"""
    for i, l in enumerate(lines, 1):
        if l.startswith(needle):
            return i
    return None

print("======== 1) template.properties 格式问题(53处) ========")
tpl = load_lines(TPL)
for i, l in enumerate(tpl, 1):
    if not l.strip() or l.startswith('#'): continue
    if l.endswith(" ="): print(f"  {i}: 缺尾空格 | {l}")
    elif not l.endswith(" = ") and ' = ' in l: print(f"  {i}: 含值 | {l[:80]}")
    elif not l.endswith(" = "): print(f"  {i}: 缺等号 | {l[:80]}")

print("\n======== 2) 模板key重复(归一化) ========")
seen = {}
for i, l in enumerate(tpl, 1):
    if not l.strip() or l.startswith('#'): continue
    key = re.sub(r'\[[^\]]*\]', '[]', l)
    if key in seen:
        print(f"  '{l[:55]}' (行{i}) == '{seen[key][0][:55]}' (行{seen[key][1]})")
    else:
        seen[key] = (l, i)

print("\n======== 3) 模板key同一占位符两次 ========")
for i, l in enumerate(tpl, 1):
    if not l.strip() or l.startswith('#'): continue
    params = re.findall(r'\[([^\]]*)\]', l)
    dup = {p for p in params if params.count(p) > 1}
    if dup:
        print(f"  {i}: {l[:70]}  dup={dup}")

print("\n======== 4) 翻译条目同一占位符两次(简/繁) ========")
for lang, path in LANG_FILES.items():
    lines = load_lines(path)
    for i, l in enumerate(lines, 1):
        if not l.strip() or l.startswith('#'): continue
        if '=' not in l: continue
        val = l.split('=', 1)[1]
        params = re.findall(r'\[([^\]]*)\]', val)
        dup = {p for p in params if params.count(p) > 1}
        if dup:
            print(f"  {lang} 行{i}: {l[:70]}  dup={dup}")
