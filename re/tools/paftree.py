"""Generic parser for Sony PAF tree containers: QRCF (.qrc), FRP (.rco), P3TF (.p3t).

Layout (big-endian): 0x00 magic, 0x04 version, then (offset,size) pairs for
tree, id table, string table, int array, float array, file table/data.
Each node: name, attr_count, parent, prev, next, first_child, last_child,
followed by attr_count x (name, type, v0, v1).
"""
import struct, sys, zlib, pathlib, json

def load(path):
    b = pathlib.Path(path).read_bytes()
    if b[:4] == b'QRCC':
        b = zlib.decompress(b[8:])
    return b

ATTR = {1: 'int', 2: 'float', 3: 'string', 4: 'intarray', 5: 'floatarray',
        6: 'file', 7: 'id', 8: 'idref', 9: 'id2'}

class Tree:
    def __init__(self, b):
        self.b = b
        self.magic = b[:4]
        f = struct.unpack('>14I', b[4:0x3c])
        self.version = f[0]
        names = ['tree', 'id', 'str', 'int', 'float', 'file']
        self.sec = {n: (f[1 + 2*i], f[2 + 2*i]) for i, n in enumerate(names[:6])}
        self.sec['blob'] = self.sec['file']
    def u32(self, o): return struct.unpack('>I', self.b[o:o+4])[0]
    def cstr(self, sec, off):
        base = self.sec[sec][0] + off
        end = self.b.index(b'\0', base)
        return self.b[base:end].decode('utf-8', 'replace')
    def node(self, off):
        base = self.sec['tree'][0] + off
        name, nattr, parent, prev, nxt, first, last = struct.unpack('>7I', self.b[base:base+28])
        attrs = []
        for i in range(nattr):
            an, at, v0, v1 = struct.unpack('>4I', self.b[base+28+16*i: base+44+16*i])
            attrs.append((self.cstr('str', an), at, v0, v1))
        return dict(off=off, name=self.cstr('str', name), attrs=attrs, first=first, next=nxt)
    def walk(self, off=0, depth=0):
        n = self.node(off); n['depth'] = depth; yield n
        c = n['first']
        while c != 0xFFFFFFFF:
            yield from self.walk(c, depth + 1)
            c = self.node(c)['next']
    def value(self, at, v0, v1):
        t = ATTR.get(at, at)
        if t == 'int': return v0 if v0 < 2**31 else v0 - 2**32
        if t == 'float': return round(struct.unpack('>f', struct.pack('>I', v0))[0], 6)
        if t == 'string': return self.cstr('str', v0)
        if t in ('id', 'idref', 'id2'):
            if v0 == 0xFFFFFFFF: return None
            return self.cstr('id', v0 + 4)
        if t == 'intarray':
            o = self.sec['int'][0] + v0*4 if False else self.sec['int'][0] + v0
            return list(struct.unpack(f'>{v1}i', self.b[o:o+4*v1]))
        if t == 'floatarray':
            o = self.sec['float'][0] + v0
            return [round(x, 6) for x in struct.unpack(f'>{v1}f', self.b[o:o+4*v1])]
        if t == 'file':
            return f'<file off={v0:#x} size={v1:#x}>'
        return f'?{at}:{v0:#x},{v1:#x}'

if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    src, outdir = sys.argv[1], pathlib.Path(sys.argv[2])
    b = load(src)
    t = Tree(b)
    outdir.mkdir(parents=True, exist_ok=True)
    print(t.magic, hex(t.version), {k: (hex(a), hex(s)) for k, (a, s) in t.sec.items()})
    lines = []
    for n in t.walk():
        attrs = ' '.join(f'{a}={t.value(at, v0, v1)!r}' for a, at, v0, v1 in n['attrs'])
        lines.append('  ' * n['depth'] + f"<{n['name']} {attrs}>")
        for a, at, v0, v1 in n['attrs']:
            if ATTR.get(at) == 'file' and v1:
                ident = next((t.value(x, y, z) for nm, x, y, z in n['attrs'] if ATTR.get(x) in ('id', 'string')), f"{n['off']:x}")
                blob = b[t.sec['blob'][0] + v0: t.sec['blob'][0] + v0 + v1]
                if any(nm in ('size', 'iconsize', 'previewsize') for nm, *_ in n['attrs']) and blob[:1] == b'x':
                    blob = zlib.decompress(blob)
                (outdir / 'files').mkdir(exist_ok=True)
                safe = (str(ident) or f"{n['name']}_{n['off']:x}").replace('/', '_').replace(chr(92), '_')
                (outdir / 'files' / safe).write_bytes(blob)
    (outdir / 'tree.txt').write_text('\n'.join(lines), encoding='utf-8')
    print('\n'.join(lines[:60]))
