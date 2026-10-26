# -*- coding: utf-8 -*-
"""最终核对：远端树 vs 本地需要入库的文件（临时脚本/APK/旧 android 子工程除外）。"""
import json
import os
import subprocess
import sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"
LOCAL_ROOT = r"D:\files\Desk\work\Github\Tuyin-Suite"

p = subprocess.run([GH, "api", "repos/Gz3192019/Tuyin-Suite/git/trees/64dad75605ea0c377bdd569d9c776e84ee583b49?recursive=1"],
                   capture_output=True, text=True, encoding="utf-8")
if p.returncode != 0:
    sys.exit(p.stdout + p.stderr)
data = json.loads(p.stdout)
remote_files = set()
for t in data.get("tree", []):
    if t.get("type") == "blob":
        remote_files.add(t.get("path", ""))

EXCLUDE_DIRS = {"build", ".gradle", "gradle", "kotlin", ".idea", ".git", "node_modules",
                "__pycache__", ".kotlin", "refs", "resukisu_ref", "android", "scripts",
                "_m3tmp", "_m3tmp14", "_miuix091", "_miuixtmp", "_navtmp", "_nav_classes"}
EXCLUDE_PREFIX = {"_"}
EXCLUDE_EXT = {".png", ".jpg", ".jpeg", ".webp", ".ico", ".mp4", ".apk", ".aar", ".jar", ".xml"}
EXCLUDE_NAMES = {"local.properties", "图隐套件工作台.html", "hyperlight.mp4", "pb2.mp4", "pb3.mp4",
                 "pb4.mp4", "ref_theme.mp4", "card_api.txt", "card_api2.txt", "card_colors.txt",
                 "parse_ui.py", "v9p.xml", "v9p2.xml", "v9ui.xml", "Tuyin-Suite-2.0.apk",
                 "Tuyin-Suite-v0.1.39.beat.apk", "avatars.b64"}

missing = []
for root, dirs, files in os.walk(LOCAL_ROOT):
    dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS and not d.startswith("_")]
    for f in files:
        if f in EXCLUDE_NAMES or f.startswith("_") or os.path.splitext(f)[1].lower() in EXCLUDE_EXT:
            continue
        rel = os.path.relpath(os.path.join(root, f), LOCAL_ROOT).replace("\\", "/")
        if rel not in remote_files:
            missing.append(rel)

print("Remote blobs:", len(remote_files))
if missing:
    print("MISSING on remote:")
    for m in sorted(missing):
        print("  ", m)
else:
    print("OK: 远端已包含全部应入库文件。")
