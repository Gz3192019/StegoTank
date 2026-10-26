# -*- coding: utf-8 -*-
import json
import subprocess
import sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"
p = subprocess.run([GH, "api", "repos/Gz3192019/Tuyin-Suite/git/trees/c98f8262e64346c570e4edfe507d9388e2fcba74?recursive=1"],
                   capture_output=True, text=True, encoding="utf-8")
if p.returncode != 0:
    sys.exit(p.stdout + p.stderr)
data = json.loads(p.stdout)
paths = [t["path"] for t in data.get("tree", []) if "drawable-nodpi" in t.get("path", "")]
for x in sorted(paths):
    print(x)
print("TOTAL drawable-nodpi:", len(paths))
