"""
Describe a short UI sound's shape - level envelope and the strongest partials over time - so
it can be re-synthesized from the description (nothing of the original is kept or shipped).

    python -I re/tools/sound_shape.py <file.wav> [window_ms]
"""
import math
import struct
import sys
import wave


def load(path):
    with wave.open(path, "rb") as w:
        n, ch, width, rate = w.getnframes(), w.getnchannels(), w.getsampwidth(), w.getframerate()
        raw = w.readframes(n)
    assert width == 2, "16-bit only"
    vals = struct.unpack("<%dh" % (len(raw) // 2), raw)
    mono = [sum(vals[i:i + ch]) / ch / 32768 for i in range(0, len(vals), ch)]
    return mono, rate


def partials(seg, rate, top=4, lo=150, hi=12000):
    """Strongest frequencies in [seg] by a plain DFT over a coarse grid, refined around peaks."""
    n = len(seg)
    win = [0.5 - 0.5 * math.cos(2 * math.pi * i / (n - 1)) for i in range(n)]
    x = [s * w for s, w in zip(seg, win)]

    def mag(f):
        re = im = 0.0
        k = 2 * math.pi * f / rate
        for i, v in enumerate(x):
            re += v * math.cos(k * i)
            im -= v * math.sin(k * i)
        return math.hypot(re, im) * 2 / sum(win)

    step = max(rate / n, 20)
    grid = [(mag(f), f) for f in frange(lo, hi, step)]
    peaks = [grid[i] for i in range(1, len(grid) - 1) if grid[i][0] > grid[i - 1][0] and grid[i][0] >= grid[i + 1][0]]
    peaks.sort(reverse=True)
    out = []
    for m, f in peaks[:top]:
        best = max((mag(g), g) for g in frange(f - step, f + step, step / 8))
        out.append((round(best[1]), round(20 * math.log10(best[0] + 1e-12), 1)))
    return out


def frange(a, b, s):
    while a <= b:
        yield a
        a += s


def main():
    path = sys.argv[1]
    win_ms = float(sys.argv[2]) if len(sys.argv) > 2 else 25
    x, rate = load(path)
    print(f"{path}: {len(x) / rate * 1000:.0f} ms, {rate} Hz, peak {max(abs(v) for v in x):.3f}")
    w = int(rate * win_ms / 1000)
    for start in range(0, len(x) - w // 2, w):
        seg = x[start:start + w]
        if len(seg) < w // 2:
            break
        rms = math.sqrt(sum(v * v for v in seg) / len(seg))
        db = 20 * math.log10(rms + 1e-12)
        if db < -70:
            continue
        print(f"{start / rate * 1000:6.0f} ms  rms {db:6.1f} dB  " + "  ".join(f"{f} Hz {m} dB" for f, m in partials(seg, rate)))


if __name__ == "__main__":
    main()
