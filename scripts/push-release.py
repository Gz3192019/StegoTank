# -*- coding: utf-8 -*-
"""一次性推送 v1.0.0 发布相关全部变更到 Tuyin-Suite main：
- README.md（重写：修正 rac-hide 链接）
- android-compose/app/build.gradle.kts（v1.0.0 + tuyin 签名）
- .github/workflows/build-apk.yml（新建）
- gradle wrapper 系（gradlew 100755 / gradlew.bat / gradle-wrapper.jar / gradle-wrapper.properties）
- keystore/debug.keystore（统一签名）
- drawable-nodpi 9 个重存 PNG（更新字节）
"""
import base64
import json
import subprocess
import sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"
OWNER = "Gz3192019"
REPO = "StegoTank"
ROOT = r"D:\files\Desk\work\Github\Tuyin-Suite"

FILES = [
    # (本地路径, 远端路径, mode)
    (ROOT + r"\README.md", "README.md", "100644"),
    (ROOT + r"\.github\workflows\build-apk.yml", ".github/workflows/build-apk.yml", "100644"),
    (ROOT + r"\android-compose\app\build.gradle.kts", "android-compose/app/build.gradle.kts", "100644"),
    (ROOT + r"\android-compose\gradlew", "android-compose/gradlew", "100755"),
    (ROOT + r"\android-compose\gradlew.bat", "android-compose/gradlew.bat", "100644"),
    (ROOT + r"\android-compose\gradle\wrapper\gradle-wrapper.jar", "android-compose/gradle/wrapper/gradle-wrapper.jar", "100644"),
    (ROOT + r"\android-compose\gradle\wrapper\gradle-wrapper.properties", "android-compose/gradle/wrapper/gradle-wrapper.properties", "100644"),
    (ROOT + r"\android-compose\keystore\debug.keystore", "android-compose/keystore/debug.keystore", "100644"),
]

PNG_DIR = ROOT + r"\android-compose\app\src\main\res\drawable-nodpi"
import os
for name in sorted(os.listdir(PNG_DIR)):
    if name.startswith("avatar_") and name.endswith(".png"):
        FILES.append((os.path.join(PNG_DIR, name),
                      "android-compose/app/src/main/res/drawable-nodpi/" + name, "100644"))

def gh(args, stdin_bytes=None):
    p = subprocess.run([GH, "api", *args], capture_output=True, text=True,
                       encoding="utf-8", input=stdin_bytes.decode("utf-8") if stdin_bytes else None)
    if p.returncode != 0:
        sys.exit("gh api failed: %s\n%s" % (p.stdout, p.stderr))
    return p.stdout

head = gh(["repos/%s/%s/git/ref/heads/main" % (OWNER, REPO), "--jq", ".object.sha"]).strip()
print("HEAD:", head)

blobs = []
for local, remote, mode in FILES:
    with open(local, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    data = json.dumps({"content": b64, "encoding": "base64"})
    out = json.loads(gh(["-X", "POST", "repos/%s/%s/git/blobs" % (OWNER, REPO),
                         "--input", "-"], stdin_bytes=data.encode("utf-8")))
    blobs.append({"path": remote, "mode": mode, "type": "blob", "sha": out["sha"]})
    print("blob ok:", remote)

tree_payload = json.dumps({"base_tree": head, "tree": blobs})
out = json.loads(gh(["-X", "POST", "repos/%s/%s/git/trees" % (OWNER, REPO),
                     "--input", "-"], stdin_bytes=tree_payload.encode("utf-8")))
print("tree:", out["sha"])

commit_payload = json.dumps({
    "message": "release: v1.0.0 正式版\n\n- README：修正 rac-hide 上游链接为 tuoPzf/rac-hide，规范化文档\n- 版本切至 v1.0.0（versionCode 43）\n- 统一签名：keystore/debug.keystore（与历史 beat 版一致，可覆盖安装）\n- 新增 GitHub Actions：build-apk.yml（打 tag v* 或手动触发 → 构建并发布 Release）\n- 加入 gradle wrapper 9.6.0（CI 可 ./gradlew 构建）\n- 重存头像 PNG 以通过 AAPT 资源编译",
    "tree": out["sha"],
    "parents": [head],
})
out = json.loads(gh(["-X", "POST", "repos/%s/%s/git/commits" % (OWNER, REPO),
                     "--input", "-"], stdin_bytes=commit_payload.encode("utf-8")))
print("commit:", out["sha"])

ref_payload = json.dumps({"sha": out["sha"], "force": False})
out = json.loads(gh(["-X", "PATCH", "repos/%s/%s/git/refs/heads/main" % (OWNER, REPO),
                     "--input", "-"], stdin_bytes=ref_payload.encode("utf-8")))
print("ref updated:", out["object"]["sha"])
