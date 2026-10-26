# -*- coding: utf-8 -*-
"""用 gh api 将 9 个 drawable-nodpi/avatar_*.png 二进制一次提交到 Tuyin-Suite main。"""
import base64
import json
import os
import subprocess
import sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"
OWNER = "Gz3192019"
REPO = "StegoTank"
SRC_DIR = r"D:\files\Desk\work\Github\Tuyin-Suite\android-compose\app\src\main\res\drawable-nodpi"

def gh(args, stdin_bytes=None):
    p = subprocess.run([GH, "api", *args], capture_output=True, text=True,
                       encoding="utf-8", input=stdin_bytes.decode("utf-8") if stdin_bytes else None)
    if p.returncode != 0:
        sys.exit("gh api failed: %s\n%s" % (p.stdout, p.stderr))
    return p.stdout

# 1) HEAD
head = gh(["repos/%s/%s/git/ref/heads/main" % (OWNER, REPO), "--jq", ".object.sha"]).strip()
print("HEAD:", head)

# 2) blobs
blobs = []
for name in sorted(os.listdir(SRC_DIR)):
    if not name.startswith("avatar_") or not name.endswith(".png"):
        continue
    with open(os.path.join(SRC_DIR, name), "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    data = json.dumps({"content": b64, "encoding": "base64"})
    out = json.loads(gh(["-X", "POST",
                         "repos/%s/%s/git/blobs" % (OWNER, REPO),
                         "--input", "-"], stdin_bytes=data.encode("utf-8")))
    blobs.append({"path": "android-compose/app/src/main/res/drawable-nodpi/" + name,
                  "mode": "100644", "type": "blob", "sha": out["sha"]})
    print("blob ok:", name, out["sha"][:10])

# 3) tree
tree_payload = json.dumps({"base_tree": head, "tree": blobs})
out = json.loads(gh(["-X", "POST", "repos/%s/%s/git/trees" % (OWNER, REPO),
                     "--input", "-"], stdin_bytes=tree_payload.encode("utf-8")))
print("tree:", out["sha"])

# 4) commit
commit_payload = json.dumps({
    "message": "chore: 打包关于页 9 个作者/项目头像资源（二进制，gh api 推送）",
    "tree": out["sha"],
    "parents": [head],
})
out = json.loads(gh(["-X", "POST", "repos/%s/%s/git/commits" % (OWNER, REPO),
                     "--input", "-"], stdin_bytes=commit_payload.encode("utf-8")))
print("commit:", out["sha"])

# 5) update ref
ref_payload = json.dumps({"sha": out["sha"], "force": False})
out = json.loads(gh(["-X", "PATCH", "repos/%s/%s/git/refs/heads/main" % (OWNER, REPO),
                     "--input", "-"], stdin_bytes=ref_payload.encode("utf-8")))
print("ref updated:", out["object"]["sha"])
