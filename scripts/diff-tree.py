# -*- coding: utf-8 -*-
"""对比远端树与本地源码，找出遗漏文件。"""
import json
import os
import subprocess
import sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"
REMOTE_BASE = "android-compose/app/src/main/"
LOCAL_ROOT = r"D:\files\Desk\work\Github\Tuyin-Suite\android-compose\app\src\main"

p = subprocess.run([GH, "api", "repos/Gz3192019/Tuyin-Suite/git/trees/c98f8262e64346c570e4edfe507d9388e2fcba74?recursive=1"],
                   capture_output=True, text=True, encoding="utf-8")
if p.returncode != 0:
    sys.exit(p.stdout + p.stderr)
data = json.loads(p.stdout)
remote_files = set()
for t in data.get("tree", []):
    path = t.get("path", "")
    if path.startswith(REMOTE_BASE) and t.get("type") == "blob":
        remote_files.add(path[len(REMOTE_BASE):])

missing = []
for root, dirs, files in os.walk(LOCAL_ROOT):
    # 跳过 build 产物
    dirs[:] = [d for d in dirs if d not in ("build", "kotlin", "tmp")]
    for f in files:
        rel = os.path.relpath(os.path.join(root, f), LOCAL_ROOT).replace("\\", "/")
        if rel not in remote_files:
            missing.append(rel)

print("REMOTE files under src/main:", len(remote_files))
if missing:
    print("MISSING locally (not in remote):")
    for m in sorted(missing):
        print("  ", m)
else:
    print("OK: 本地 src/main 全部文件均已在远端。")
