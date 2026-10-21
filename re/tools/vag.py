"""Sony VAG (PS-ADPCM) decoder -> PCM16 samples."""
import struct, wave

COEF = [(0, 0), (60, 0), (115, -52), (98, -55), (122, -60)]

def decode(buf):
    """buf: a VAGp file (48-byte header) or headerless ADPCM. Returns (rate, [int16])."""
    rate = 48000
    if buf[:4] == b'VAGp':
        rate = struct.unpack('>I', buf[16:20])[0]; buf = buf[48:]
    out, h1, h2 = [], 0, 0
    for i in range(0, len(buf) - 15, 16):
        pf, flags = buf[i], buf[i + 1]
        if flags == 7: break
        shift, pred = pf & 15, (pf >> 4) % 5
        c1, c2 = COEF[pred]
        for byte in buf[i + 2:i + 16]:
            for nib in (byte & 15, byte >> 4):
                s = (nib - 16 if nib > 7 else nib) << 12
                s = (s >> shift) + (h1 * c1 + h2 * c2 + 32) // 64
                s = max(-32768, min(32767, s))
                h2, h1 = h1, s; out.append(s)
    return rate, out

def write_wav(path, rate, channels):
    n = min(len(c) for c in channels)
    with wave.open(str(path), 'wb') as w:
        w.setnchannels(len(channels)); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes(b''.join(struct.pack('<' + 'h' * len(channels), *(c[i] for c in channels)) for i in range(n)))
