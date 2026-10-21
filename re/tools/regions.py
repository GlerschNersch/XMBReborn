"""Per-frame statistics of fixed screen boxes in an XMB capture, for fade and scale timing.

For each box (in 1280x720 reference pixels) prints, per frame: mean luminance above the
box's own background (its 10th percentile), and the width/height of the area brighter than
--thresh, which tracks an icon growing or shrinking in place.

usage: python -I regions.py capture.mp4 --box name x0 y0 x1 y1 [--box ...] [--from s] [--to s]
       [--csv out.csv]
"""
import argparse, csv, sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I drops the script dir
import numpy as np
from motion import fps_of

def frames(path, w=1280, h=720):
    import subprocess
    cmd = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', path, '-vf', f'scale={w}:{h}',
           '-f', 'rawvideo', '-pix_fmt', 'gray', '-']
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    n = w * h
    while True:
        b = p.stdout.read(n)
        if len(b) < n: break
        yield np.frombuffer(b, np.uint8).reshape(h, w).astype(np.float32)

def extent(mask, axis):
    hit = np.flatnonzero(mask.any(axis=axis))
    return int(hit[-1] - hit[0] + 1) if len(hit) else 0

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('video')
    ap.add_argument('--box', nargs=5, action='append', required=True, metavar=('NAME', 'X0', 'Y0', 'X1', 'Y1'))
    ap.add_argument('--thresh', type=float, default=60, help='luminance above background that counts as "lit"')
    ap.add_argument('--from', dest='t0', type=float, default=0); ap.add_argument('--to', dest='t1', type=float, default=1e9)
    ap.add_argument('--csv')
    a = ap.parse_args()
    fps = fps_of(a.video)
    boxes = [(n, *map(int, r)) for n, *r in a.box]
    rows = []
    for i, f in enumerate(frames(a.video)):
        t = i / fps
        if t < a.t0: continue
        if t > a.t1: break
        row = {'t': round(t, 3)}
        for n, x0, y0, x1, y1 in boxes:
            b = f[y0:y1, x0:x1]
            lit = b - np.percentile(b, 10)
            m = lit > a.thresh
            row[f'{n}_mean'] = round(float(lit.mean()), 2)
            row[f'{n}_w'] = extent(m, 0)
            row[f'{n}_h'] = extent(m, 1)
        rows.append(row)
    out = csv.DictWriter(open(a.csv, 'w', newline='') if a.csv else sys.stdout, fieldnames=list(rows[0]))
    out.writeheader(); out.writerows(rows)

if __name__ == '__main__':
    main()
