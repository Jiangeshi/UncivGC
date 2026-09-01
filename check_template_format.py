import sys

def check(path, label):
    try:
        with open(path, encoding='utf-8') as f:
            lines = f.read().splitlines()
    except Exception as e:
        print(label, "读取失败:", e)
        return
    bad = []
    for i, l in enumerate(lines, 1):
        if not l.strip() or l.startswith('#'):
            continue
        if l.endswith(" ="):
            bad.append((i, '缺尾空格', l))
        elif not l.endswith(" = ") and ' = ' in l:
            bad.append((i, '含值', l[:70]))
        elif not l.endswith(" = "):
            bad.append((i, '缺等号', l[:70]))
    print(label, "格式问题数:", len(bad))
    for i, k, l in bad[:60]:
        print(f"  {i}: [{k}] {l}")

if __name__ == '__main__':
    check(sys.argv[1], sys.argv[2])
