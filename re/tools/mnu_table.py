"""
Gather a qgl theme's .mnu parameter files (as extracted by qrc unpacking into
re/out/qgl/<theme>/files) into one table: one row per preset, one column per
FILE.PARAM, so the presets can be compared. Prints a summary and writes CSV next
to the files (re/out is gitignored: the values stay with the user's firmware).

    python -I re/tools/mnu_table.py re/out/qgl/canyon/files
"""
import csv
import pathlib
import re
import sys


def parse(path):
    out = {}
    for line in path.read_text(encoding="latin-1").splitlines():
        parts = line.split(":")
        if len(parts) >= 3 and not line.startswith("#"):
            out[parts[0].strip()] = ":".join(parts[2:]).strip()
    return out


def main():
    root = pathlib.Path(sys.argv[1])
    presets = {}
    for f in sorted(root.glob("*.mnu")):
        m = re.match(r"(?:override_(.+?)_)?([a-z]+)_([A-Z0-9_]+)\.mnu$", f.name)
        if not m:
            # Earth keeps presets as presets/<n>/earth/X.mnu -> presets_<n>_earth_X.mnu
            m2 = re.match(r"(?:(?:earth_)?presets_(\d+)_)?earth_([A-Za-z0-9_]+)\.mnu$", f.name)
            if not m2:
                continue
            preset, name = m2.group(1) or "default", m2.group(2)
        else:
            preset, name = m.group(1) or "default", m.group(3)
        for k, v in parse(f).items():
            presets.setdefault(preset, {})[f"{name}.{k}"] = v
    cols = sorted({c for p in presets.values() for c in p})
    out = root.parent / "presets.csv"
    with open(out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["preset"] + cols)
        for name, vals in presets.items():
            w.writerow([name] + [vals.get(c, "") for c in cols])
    # Summary: which parameters vary between presets, and their range.
    print(f"{len(presets)} presets, {len(cols)} parameters -> {out}")
    for c in cols:
        vals = [p.get(c) for p in presets.values() if p.get(c) not in (None, "")]
        nums = []
        for v in vals:
            try:
                nums.append(float(v))
            except ValueError:
                pass
        if len(set(vals)) > 1 and nums:
            print(f"  {c:32s} {min(nums):10.4g} .. {max(nums):10.4g}  ({len(set(vals))} values)")


if __name__ == "__main__":
    main()
