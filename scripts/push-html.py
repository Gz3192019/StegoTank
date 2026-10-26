# -*- coding: utf-8 -*-
"""用 gh api contents API 推送 图隐套件工作台.html 到 Tuyin-Suite main。"""
import base64
import json
import os
import subprocess
import sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"
OWNER = "Gz3192019"
REPO = "StegoTank"
SRC = r"D:\files\Desk\work\Github\Tuyin-Suite\图隐套件工作台.html"
REMOTE_PATH = "图隐套件工作台.html"

with open(SRC, "rb") as f:
    b64 = base64.b64encode(f.read()).decode("ascii")

# 先看文件是否已存在（决定是否需 base sha）
p = subprocess.run([GH, "api", "repos/%s/%s/contents/%s" % (OWNER, REPO, REMOTE_PATH)],
                   capture_output=True, text=True, encoding="utf-8")
sha = None
if p.returncode == 0:
    try:
        sha = json.loads(p.stdout).get("sha")
    except Exception:
        sha = None

payload = {
    "message": "feat: Web 四合一隐写工作台（单文件自包含）",
    "content": b64,
    "branch": "main",
}
if sha:
    payload["sha"] = sha

p = subprocess.run([GH, "api", "-X", "PUT",
                    "repos/%s/%s/contents/%s" % (OWNER, REPO, REMOTE_PATH),
                    "--input", "-"],
                   capture_output=True, text=True, encoding="utf-8",
                   input=json.dumps(payload))
if p.returncode != 0:
    sys.exit("PUT failed: %s\n%s" % (p.stdout, p.stderr))
out = json.loads(p.stdout)
print("pushed:", out.get("commit", {}).get("sha", "?"))
