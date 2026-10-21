"""Minimal GIM -> PNG (big-endian PS3 GIMs; RGBA5650/5551/4444/8888, index4/8, DXT1/3/5)."""
import struct, sys, pathlib
from PIL import Image

def blocks(b, off, end, be):
    E = '>' if be else '<'
    while off < end:
        bid, _, size, nxt, data = struct.unpack(E + 'HHIII', b[off:off+16])
        yield bid, off, size, data
        if bid in (2, 3): yield from blocks(b, off + data, off + size, be)
        off += size if bid not in (2, 3) else size
        if bid in (2, 3): return

def unswizzle(buf, pitch, h):
    out = bytearray(len(buf)); bw, i = pitch // 16, 0
    for by in range(h // 8):
        for bx in range(bw):
            for y in range(8):
                row = (by * 8 + y) * pitch + bx * 16
                out[row:row+16] = buf[i:i+16]; i += 16
    return bytes(out)

def image_block(b, off, be):
    E = '>' if be else '<'
    base = off + 16
    hsz, ref, fmt, order, w, h, bpp, palign, halign = struct.unpack(E + '9H', b[base:base+18])
    pstart = struct.unpack(E + 'I', b[base+0x1c:base+0x20])[0]
    pitch = ((w * bpp + 7) // 8 + palign - 1) // palign * palign
    hh = (h + halign - 1) // halign * halign
    return dict(fmt=fmt, order=order, w=w, h=h, bpp=bpp, pitch=pitch, hh=hh, data=base + pstart)

def decode(path):
    b = pathlib.Path(path).read_bytes()
    be = b[:4] == b'.GIM'
    imgs = {}
    for bid, off, size, data in blocks(b, 16, len(b), be):
        if bid in (4, 5): imgs[bid] = image_block(b, off, be)
    im = imgs[4]; w, h, fmt = im['w'], im['h'], im['fmt']
    raw = b[im['data']: im['data'] + im['pitch'] * im['hh']]
    if im['order'] == 1 and fmt < 8: raw = unswizzle(raw, im['pitch'], im['hh'])
    E = '>' if be else '<'
    def px16(v, f):
        if f == 0: return ((v & 31) * 255 // 31, ((v >> 5) & 63) * 255 // 63, ((v >> 11) & 31) * 255 // 31, 255)
        if f == 1: return ((v & 31) * 255 // 31, ((v >> 5) & 31) * 255 // 31, ((v >> 10) & 31) * 255 // 31, 255 if v >> 15 else 0)
        return ((v & 15) * 17, ((v >> 4) & 15) * 17, ((v >> 8) & 15) * 17, (v >> 12) * 17)
    def color(f, data, i):
        if f == 3: return tuple(data[i*4:i*4+4])
        v = struct.unpack(E + 'H', data[i*2:i*2+2])[0]; return px16(v, f)
    if fmt in (8, 9, 10):
        mode = {8: 'DXT1', 9: 'DXT3', 10: 'DXT5'}[fmt]
        return Image.frombytes('RGBA', (w, h), raw, 'bcn', {8: 1, 9: 2, 10: 3}[fmt])
    out = Image.new('RGBA', (w, h)); px = out.load()
    pal = None
    if fmt in (4, 5):
        p = imgs[5]; pdata = b[p['data']:]
        pal = [color(p['fmt'], pdata, i) for i in range(16 if fmt == 4 else 256)]
    for y in range(h):
        row = raw[y * im['pitch']:(y + 1) * im['pitch']]
        for x in range(w):
            if fmt == 4: px[x, y] = pal[(row[x // 2] >> (4 * (x & 1))) & 15]
            elif fmt == 5: px[x, y] = pal[row[x]]
            else: px[x, y] = color(fmt, row, x)
    return out

if __name__ == '__main__':
    for p in sys.argv[1:]:
        try:
            decode(p).save(pathlib.Path(p).with_suffix('.png')); print('ok', p)
        except Exception as e: print('FAIL', p, e)
