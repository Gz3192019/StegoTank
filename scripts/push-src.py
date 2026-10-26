# -*- coding: utf-8 -*-
"""批量同步 android-compose 源码到远端 StegoTank main（gh api contents API）。
用法: python3 scripts/push-src.py
本地 android-compose/ 目录 = 远端仓库根目录（app/、build.gradle.kts 等在远端根）。"""
import base64, json, os, subprocess, sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"
OWNER, REPO, BRANCH = "Gz3192019", "StegoTank", "main"
BASE = r"D:\Github\StegoTank\android-compose"
SKIP_DIRS = {"build", ".gradle", ".idea"}
SKIP_FILES = {"local.properties", ".DS_Store"}


def collect():
    out = []
    for root, dirs, fs in os.walk(BASE):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in fs:
            if f in SKIP_FILES:
                continue
            full = os.path.join(root, f)
            rel = os.path.relpath(full, BASE).replace(os.sep, "/")
            out.append((rel, full))
    return out


def put(remote, local):
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
    payload = {
        "message": "sync: 图隐套件源码同步到远端",
        "content": b64,
        "branch": BRANCH,
    }
    if sha:
        payload["sha"] = sha
    p = subprocess.run([GH, "api", "-X", "PUT", "repos/%s/%s/contents/%s" % (OWNER, REPO, remote), "--input", "-"],
                       input=json.dumps(payload), capture_output=True, text=True, encoding="utf-8")
    if p.returncode != 0:
        return False, (p.stdout + p.stderr)
    return True, ""


def main():
    files = collect()
    print("待推送文件数: %d" % len(files))
    ok = fail = 0
    for i, (rel, full) in enumerate(files, 1):
        good, err = put(rel, full)
        if good:
            ok += 1
            print("[%02d/%02d] OK  %s" % (i, len(files), rel))
        else:
            fail += 1
            print("[%02d/%02d] FAIL %s\n%s" % (i, len(files), rel, err))
    print("\n完成: 成功 %d, 失败 %d" % (ok, fail))
    sys.exit(1 if fail else 0)


if __name__ == "__main__":
    main()
