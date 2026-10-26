# -*- coding: utf-8 -*-
"""还原脚本：把 avatars.b64 解码回 res/drawable-nodpi/avatar_*.png（clone 后先运行本脚本再编译）。"""
import base64
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B64 = os.path.join(os.path.dirname(os.path.abspath(__file__)), "avatars.b64")
DST_DIR = os.path.join(ROOT, "android-compose", "app", "src", "main", "res", "drawable-nodpi")

with open(B64, "r", encoding="utf-8") as f:
    payload = json.load(f)

os.makedirs(DST_DIR, exist_ok=True)
count = 0
for name, b64 in payload.items():
    data = base64.b64decode(b64)
    with open(os.path.join(DST_DIR, name), "wb") as f:
        f.write(data)
    count += 1
    print("restored %s (%d bytes)" % (name, len(data)))

print("done: %d avatars restored to %s" % (count, DST_DIR))
