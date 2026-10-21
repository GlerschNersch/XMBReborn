"""Decode XMB month background DDS (64x32 BGRA) to PNG + a JSON of corner/center colors."""
import sys, json, pathlib
from PIL import Image
src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
summary, sheet = {}, Image.new('RGB', (12 * 128, 2 * 64))
for row, kind in enumerate(['rgb', 'night']):
    for m in range(1, 13):
        im = Image.open(src / f'textures_month_bg_{kind}_{m:02d}.dds').convert('RGB')
        im.save(out / f'{kind}_{m:02d}.png')
        w, h = im.size
        pts = {'tl': (0, 0), 'tr': (w-1, 0), 'c': (w//2, h//2), 'bl': (0, h-1), 'br': (w-1, h-1)}
        summary[f'{kind}_{m:02d}'] = {k: '#%02x%02x%02x' % im.getpixel(p) for k, p in pts.items()}
        sheet.paste(im.resize((128, 64)), ((m-1) * 128, row * 64))
sheet.save(out / 'sheet.png')
(out / 'month_colors.json').write_text(json.dumps(summary, indent=1))
print(json.dumps(summary)[:1500])
