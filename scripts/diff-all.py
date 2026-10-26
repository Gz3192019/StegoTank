# -*- coding: utf-8 -*-
"""工程级全量 diff：本地仓库根 vs 远端树（排除 build 产物/脚本临时文件）。"""
import json
import os
import subprocess
import sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"
LOCAL_ROOT = r"D:\files\Desk\work\Github\Tuyin-Suite"

p = subprocess.run([GH, "api", "repos/Gz3192019/Tuyin-Suite/git/trees/c98f8262e64346c570e4edfe507d9388e2fcba74?recursive=1"],
                   capture_output=True, text=True, encoding="utf-8")
if p.returncode != 0:
    sys.exit(p.stdout + p.stderr)
data = json.loads(p.stdout)
remote_files = set()
for t in data.get("tree", []):
    if t.get("type") == "blob":
        remote_files.add(t.get("path", ""))

EXCLUDE_DIRS = {"build", ".gradle", "gradle", "kotlin", ".idea", ".git", "node_modules",
                "__pycache__", ".kotlin", "refs", "resukisu_ref"}
EXCLUDE_EXT = {".png", ".jpg", ".jpeg", ".webp", ".ico"}  # 二进制已单独核对
EXCLUDE_NAMES = {"_pkg.py", "_ver39.py", "_persist.py", "_monet2~6.py", "_cmp.py", "_fl.txt",
                 "_o.txt", "_err.txt", "pack-avatars.py", "split-avatars.py", "restore-avatars.py",
                 "push-avatars.py", "verify-tree.py", "diff-tree.py", "avatars.b64",
                 "Tuyin-Suite-v0.1.39.beat.apk"}

missing = []
local_all = 0
for root, dirs, files in os.walk(LOCAL_ROOT):
    dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
    for f in files:
        full = os.path.join(root, f)
        rel = os.path.relpath(full, LOCAL_ROOT).replace("\\", "/")
        if f in EXCLUDE_NAMES or os.path.splitext(f)[1].lower() in EXCLUDE_EXT:
            continue
        local_all += 1
        if rel not in remote_files:
            missing.append(rel)

print("Local tracked candidates:", local_all)
print("Remote total blobs:", len(remote_files))
if missing:
    print("MISSING locally but not on remote:")
    for m in sorted(missing):
        print("  ", m)
else:
    print("OK: 工程级无遗漏（build/脚本/APK 已排除）。")
