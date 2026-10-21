"""Measure XMB motion from a capture made by xmb_capture.ps1.

Tracks the displacement of a horizontal (or vertical) strip of the screen frame-to-frame via
1-D cross-correlation of its intensity profile, integrates it into a position curve, splits it
into moves (one per key press) and reports each move's distance, duration and easing shape.

usage: python -I motion.py capture.mp4 --axis x --band 0.18 0.40 [--plot out.png]
"""
import argparse, json, subprocess, sys
import numpy as np

def frames(path, w=640, h=360):
    cmd = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', path, '-vf', f'scale={w}:{h}',
           '-f', 'rawvideo', '-pix_fmt', 'gray', '-']
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    n = w * h
    while True:
        b = p.stdout.read(n)
        if len(b) < n: break
        yield np.frombuffer(b, np.uint8).reshape(h, w).astype(np.float32)

def fps_of(path):
    out = subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                                   'stream=r_frame_rate', '-of', 'csv=p=0', path]).decode().strip()
    a, b = out.split('/')
    return float(a) / float(b)

def shift(a, b, maxs):
    """Sub-pixel shift s such that b(x) ~= a(x - s), by normalized cross-correlation."""
    a = a - a.mean(); b = b - b.mean()
    best, scores = None, []
    for s in range(-maxs, maxs + 1):
        if s >= 0: x, y = a[:len(a) - s], b[s:]
        else: x, y = a[-s:], b[:len(b) + s]
        d = np.sqrt((x * x).sum() * (y * y).sum()) or 1.0
        scores.append((x * y).sum() / d)
    scores = np.array(scores); i = int(scores.argmax())
    off = 0.0
    if 0 < i < len(scores) - 1:  # parabolic refinement
        l, c, r = scores[i - 1], scores[i], scores[i + 1]
        den = l - 2 * c + r
        off = 0.5 * (l - r) / den if den else 0.0
    return i - maxs + off, scores[i]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('video'); ap.add_argument('--axis', choices='xy', default='x')
    ap.add_argument('--band', nargs=2, type=float, default=[0.18, 0.40],
                    help='strip to track, as fractions of the other axis (e.g. bar rows for x)')
    ap.add_argument('--span', nargs=2, type=float, default=[0.0, 1.0],
                    help='part of the tracked axis to use (exclude static UI like the clock)')
    ap.add_argument('--maxshift', type=int, default=40)
    ap.add_argument('--plot'); ap.add_argument('--json')
    a = ap.parse_args()
    fps = fps_of(a.video)
    pos, prev, W, H = [0.0], None, 640, 360
    for f in frames(a.video, W, H):
        if a.axis == 'x':
            band = f[int(a.band[0] * H):int(a.band[1] * H), int(a.span[0] * W):int(a.span[1] * W)]
            prof = band.mean(axis=0)
        else:
            band = f[int(a.span[0] * H):int(a.span[1] * H), int(a.band[0] * W):int(a.band[1] * W)]
            prof = band.mean(axis=1)
        if prev is not None:
            s, _ = shift(prev, prof, a.maxshift)
            pos.append(pos[-1] + s)
        prev = prof
    pos = np.array(pos) * (1280 / W if a.axis == 'x' else 720 / H)  # in 1280x720 reference px
    vel = np.abs(np.diff(pos, prepend=pos[0]))
    moving = vel > 0.6
    # Group runs of motion (allow 2-frame gaps) into moves.
    moves, i = [], 0
    while i < len(pos):
        if moving[i]:
            j = i
            while j < len(pos) and (moving[j] or moving[min(j + 1, len(pos) - 1)] or moving[min(j + 2, len(pos) - 1)]):
                j += 1
            start, end = max(i - 1, 0), min(j, len(pos) - 1)
            d = pos[end] - pos[start]
            if abs(d) > 5:
                seg = (pos[start:end + 1] - pos[start]) / d
                t = np.arange(len(seg)) / fps
                # time to reach 50% / 90% / 99% of the move
                def reach(q): return float(t[np.argmax(seg >= q)])
                moves.append(dict(start=round(start / fps, 3), dist=round(float(d), 1),
                                  dur=round(float(t[-1]), 3), t50=reach(0.5), t90=reach(0.9),
                                  t99=reach(0.99), curve=[round(float(x), 3) for x in seg]))
            i = j
        i += 1
    for m in moves:
        print(f"t={m['start']:7.3f}s dist={m['dist']:7.1f}px dur={m['dur']:.3f}s "
              f"t50={m['t50']:.3f} t90={m['t90']:.3f} t99={m['t99']:.3f}")
    if a.json:
        json.dump(dict(fps=fps, moves=moves, pos=[round(float(p), 2) for p in pos]), open(a.json, 'w'))
    if a.plot:
        import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
        fig, ax = plt.subplots(figsize=(12, 4))
        ax.plot(np.arange(len(pos)) / fps, pos); ax.set_xlabel('s'); ax.set_ylabel('px (1280x720)')
        fig.savefig(a.plot, dpi=90)

if __name__ == '__main__':
    main()
