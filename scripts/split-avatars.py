# -*- coding: utf-8 -*-
"""把 avatars.b64 拆分为每个头像单独的 .b64 文件，便于 MCP 分批推送。"""
import base64
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
B64 = os.path.join(HERE, "avatars.b64")

with open(B64, "r", encoding="utf-8") as f:
    payload = json.load(f)

for name, b64 in payload.items():
    out = os.path.join(HERE, name + ".b64")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(b64)
        f.write("\n")
    print("%s -> %.1f KB" % (out, os.path.getsize(out) / 1024.0))
