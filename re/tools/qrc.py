"""Unpack PS3 VSH .qrc containers (QRCC zlib wrapper around a QRCF tree)."""
import struct, sys, zlib, pathlib

def load(path):
    b = pathlib.Path(path).read_bytes()
    if b[:4] == b'QRCC':
        size = struct.unpack('>I', b[4:8])[0]
        b = zlib.decompress(b[8:])
        assert len(b) == size, (len(b), size)
    return b

if __name__ == '__main__':
    data = load(sys.argv[1])
    out = pathlib.Path(sys.argv[2]); out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(data)
    print(len(data))
