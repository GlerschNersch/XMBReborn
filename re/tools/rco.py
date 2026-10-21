"""PS3 RCO ('FRP\0', big-endian) parser.

The table block is zlib-compressed right after the 0xA4 header; header pointers
address it as if it were decompressed in place at 0xA4. Text/image/sound data
live raw in the file at their own offsets.
"""
import struct, sys, zlib, pathlib, json
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import vag

TYPES = {1: 'main', 2: 'vsmx', 3: 'text', 4: 'image', 5: 'model', 6: 'sound',  # PS3 swaps sound/model vs PSP
         7: 'font', 8: 'object', 9: 'anim'}
IMGFMT = {0: 'png', 1: 'jpg', 2: 'tif', 3: 'gif', 4: 'bmp', 5: 'gim', 6: 'dds', 7: 'gtf'}
HDR = ['main', 'vsmx', 'text', 'sound', 'model', 'image', 'unk', 'font', 'object', 'anim',
       'textData', 'lTextData', 'labelData', 'lLabelData', 'eventData', 'lEventData',
       'textPtrs', 'lTextPtrs', 'imgPtrs', 'lImgPtrs', 'modelPtrs', 'lModelPtrs',
       'soundPtrs', 'lSoundPtrs', 'objPtrs', 'lObjPtrs', 'animPtrs', 'lAnimPtrs',
       'imgData', 'lImgData', 'soundData', 'lSoundData', 'modelData', 'lModelData']

class RCO:
    def __init__(self, raw):
        assert raw[:4] == b'FRP\0', raw[:4]
        self.raw = raw
        self.version, _, self.comp = struct.unpack('>3I', raw[4:16])
        self.h = dict(zip(HDR, struct.unpack(f'>{len(HDR)}I', raw[16:16 + 4 * len(HDR)])))
        if self.comp & 0xF0:
            packed, unpacked, _ = struct.unpack('>3I', raw[0xA4:0xB0])
            tbl = zlib.decompress(raw[0xB0:0xB0 + packed])
            assert len(tbl) == unpacked
            self.v = bytearray(raw[:0xA4]) + tbl  # virtual address space
        else:
            self.v = raw
    def u32(self, o, buf=None): return struct.unpack('>I', (buf or self.v)[o:o+4])[0]
    def label(self, off):
        if off == 0xFFFFFFFF: return None
        b = self.v[self.h['labelData'] + off:]
        return bytes(b[:b.index(0)]).decode('utf-8', 'replace')
    def event(self, off):
        b = self.v[self.h['eventData'] + off:]
        return bytes(b[:b.index(0)]).decode('utf-8', 'replace')
    def entry(self, off, bound):
        t, tid, _u, lbl, _h, first, nsub, nxt = struct.unpack('>BBHIIIII', self.v[off:off+0x18])
        child0 = off + first if nsub and first else None
        end = child0 or (off + nxt if nxt else bound)
        return dict(off=off, type=TYPES.get(t, t), subtype=tid, label=self.label(lbl),
                    nsub=nsub, next=nxt, child0=child0, bound=bound,
                    payload=bytes(self.v[off + 0x28:end]))
    def children(self, e):
        out, c = [], e['child0']
        end = e['off'] + e['next'] if e['next'] else e['bound']
        for _ in range(e['nsub']):
            ch = self.entry(c, end); out.append(ch)
            if not ch['next']: break
            c += ch['next']
        return out

def dump(path, outdir):
    raw = pathlib.Path(path).read_bytes(); r = RCO(raw)
    outdir = pathlib.Path(outdir); outdir.mkdir(parents=True, exist_ok=True)
    lines, manifest = [], {'images': [], 'sounds': [], 'texts': {}}
    def walk(e, d):
        payload = e['payload']
        extra = ''
        if e['type'] == 'image' and d > 1:
            fmt, comp, size, off = struct.unpack('>HHII', payload[:12])
            data = raw[r.h['imgData'] + off: r.h['imgData'] + off + size]
            if comp & 0xF:  # zlib
                data = zlib.decompress(data)
            ext = IMGFMT.get(fmt, f'fmt{fmt}')
            (outdir / 'images').mkdir(exist_ok=True)
            (outdir / 'images' / f"{e['label']}.{ext}").write_bytes(data)
            manifest['images'].append(e['label']); extra = f' {ext} {len(data)}B'
        elif e['type'] == 'sound' and d > 1:
            fmt, chans = struct.unpack('>HB', payload[:3])
            total, base = struct.unpack('>II', payload[4:12])
            pcm, rate, size = [], 48000, total // chans
            for c in range(chans):  # channels are back-to-back VAGp streams
                off = base + c * size
                rate, samples = vag.decode(raw[r.h['soundData'] + off: r.h['soundData'] + off + size])
                pcm.append(samples)
            (outdir / 'sounds').mkdir(exist_ok=True)
            vag.write_wav(outdir / 'sounds' / f"{e['label']}.wav", rate, pcm)
            manifest['sounds'].append(e['label'])
            extra = f' vag ch={chans} {rate}Hz {len(pcm[0]) / rate:.2f}s'
        elif e['type'] == 'text' and d > 1:
            lang, fmt, n = struct.unpack('>HHI', payload[:8])
            strs = {}
            for i in range(n):
                lo, ln, to = struct.unpack('>III', payload[8 + 12*i: 20 + 12*i])
                if ln and to != 0xFFFFFFFF and lo != 0xFFFFFFFF:
                    s = raw[r.h['textData'] + to: r.h['textData'] + to + ln]
                    strs[r.label(lo)] = s.decode('utf-16-be' if fmt == 1 else 'utf-8', 'replace').rstrip('\0')
            manifest['texts'][lang] = strs; extra = f' lang={lang} n={n}'
        elif e['type'] in ('object', 'anim') and payload:
            words = struct.unpack(f'>{len(payload)//4}I', payload[:len(payload)//4*4])
            vals = []
            for w in words:
                f = struct.unpack('>f', struct.pack('>I', w))[0]
                vals.append(('%g' % f) if 1e-4 < abs(f) < 1e5 else (hex(w) if w > 0xFFFF else str(w)))
            extra = ' [' + ' '.join(vals) + ']'
        lines.append('  ' * d + f"{e['type']}:{e['subtype']} {e['label']!r}{extra}")
        for c in r.children(e): walk(c, d + 1)
    walk(r.entry(r.h['main'], len(r.v)), 0)
    (outdir / 'tree.txt').write_text('\n'.join(lines), encoding='utf-8')
    (outdir / 'manifest.json').write_text(json.dumps(manifest, indent=1, ensure_ascii=False), encoding='utf-8')
    return lines

if __name__ == '__main__':
    print('\n'.join(dump(sys.argv[1], sys.argv[2])[:120]))
