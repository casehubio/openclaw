#!/usr/bin/env python3
"""Strip @casehubio/* dependencies from unpacked .casehub-packages.

Component packages declare internal cross-deps on other @casehubio/* packages
(mosaic-core, pages-primitives, etc.). These break npm install because they're
not published to npmjs.org. The consuming app provides them as direct file:
dependencies — so we remove them from the component package.json files before
npm install runs.

Usage: python3 strip-internal-deps.py <packages-dir>
"""
import json
import sys
from pathlib import Path

packages_dir = Path(sys.argv[1])
count = 0
for pkg_json in sorted(packages_dir.glob("*/package.json")):
    with open(pkg_json) as f:
        pkg = json.load(f)
    changed = False
    for dep_key in ("dependencies", "peerDependencies", "devDependencies"):
        deps = pkg.get(dep_key, {})
        internal = [k for k in deps if k.startswith("@casehubio/")]
        for k in internal:
            del deps[k]
            changed = True
    if changed:
        with open(pkg_json, "w") as f:
            json.dump(pkg, f, indent=2)
            f.write("\n")
        count += 1

print(f"Stripped internal deps from {count} packages")
