# -*- coding: utf-8 -*-
"""推送单个本地文件到远端 Tuyin-Suite main（gh api contents PUT）。
用法: python3 push-one.py <local_path> <remote_path> [commit_message]"""
import base64, json, os, subprocess, sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"
OWNER, REPO, BRANCH = "Gz3192019", "StegoTank", "main"
DEFAULT_MSG = "sync: 更新文件"

local, remote = sys.argv[1], sys.argv[2]
msg = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_MSG

with open(local, "rb") as fh:
    b64 = base64.b64encode(fh.read()).decode("ascii")

sha = None
p = subprocess.run([GH, "api", "repos/%s/%s/contents/%s" % (OWNER, REPO, remote)],
                   capture_output=True, text=True, encoding="utf-8")
if p.returncode == 0:
    try:
        sha = json.loads(p.stdout).get("sha")
    except Exception:
        sha = None

payload = {"message": msg, "content": b64, "branch": BRANCH}
if sha:
    payload["sha"] = sha

p = subprocess.run([GH, "api", "-X", "PUT", "repos/%s/%s/contents/%s" % (OWNER, REPO, remote), "--input", "-"],
                   input=json.dumps(payload), capture_output=True, text=True, encoding="utf-8")
if p.returncode != 0:
    sys.exit("PUT 失败: %s\n%s" % (p.stdout, p.stderr))
out = json.loads(p.stdout)
print("pushed %s -> commit %s" % (remote, out.get("commit", {}).get("sha", "?")))
