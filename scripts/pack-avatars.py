# -*- coding: utf-8 -*-
"""将 res/drawable-nodpi/avatar_*.png 打包为 base64 JSON，供仓库无 git 二进制通道时入库。"""
import base64
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, "android-compose", "app", "src", "main", "res", "drawable-nodpi")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "avatars.b64")

payload = {}
for name in sorted(os.listdir(SRC_DIR)):
    if not name.startswith("avatar_") or not name.endswith(".png"):
        continue
    with open(os.path.join(SRC_DIR, name), "rb") as f:
        payload[name] = base64.b64encode(f.read()).decode("ascii")

with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    json.dump(payload, f, ensure_ascii=False, indent=0)
    f.write("\n")

print("packed %d files -> %s (%.1f KB)" % (len(payload), OUT, os.path.getsize(OUT) / 1024.0))
