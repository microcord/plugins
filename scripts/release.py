#!/usr/bin/env python3
"""Builds the plugins whose version changed and publishes them on the `builds` branch.

    release.py BUILDS_DIR

BUILDS_DIR is a checkout of the `builds` branch. Each plugin whose `versionCode` differs from the
one in BUILDS_DIR/plugins.json is built and its APK committed there; then plugins.json is rewritten
and committed. Microcord reads both through jsDelivr. APK links are pinned to the commit that added
the APK, so they never change and are never served stale; unchanged plugins keep their old link.
Entries from third-party.json are copied in as they are.
"""
import hashlib
import json
import os
import pathlib
import subprocess
import sys

CDN = "https://cdn.jsdelivr.net/gh/microcord/plugins"
ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLE = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
THIRD_PARTY_FIELDS = {"id", "packageName", "name", "versionCode", "pluginApiMinimum", "apkUrl", "sha256", "size",
                      "signerSha256"}


def run(*args, cwd=ROOT):
    return subprocess.run(args, cwd=cwd, check=True, stdout=subprocess.PIPE, text=True).stdout.strip()


def main(builds):
    builds = pathlib.Path(builds).resolve()
    run(GRADLE, "-q", "pluginMetadata")
    metadata = json.loads((ROOT / "build/plugins/metadata.json").read_text())
    index_file = builds / "plugins.json"
    previous = {p["id"]: p for p in json.loads(index_file.read_text())["plugins"]} if index_file.exists() else {}

    changed = [m for m in metadata if previous.get(m["id"], {}).get("versionCode") != m["versionCode"]]
    changed_ids = {m["id"] for m in changed}
    if changed:
        run(GRADLE, "-q", *[f":{m['module']}:assembleRelease" for m in changed])
        for plugin in changed:
            apk = next((ROOT / plugin["module"] / "build/outputs/apk/release").glob("*.apk"))
            (builds / f"{plugin['module']}.apk").write_bytes(apk.read_bytes())
        run("git", "add", "-A", cwd=builds)
        run("git", "commit", "-q", "-m", "build: " + ", ".join(f"{m['name']} {m['versionName']}" for m in changed),
            cwd=builds)
        commit = run("git", "rev-parse", "HEAD", cwd=builds)

    plugins = []
    for plugin in metadata:
        module = plugin.pop("module")
        if plugin["id"] in changed_ids:
            apk = (builds / f"{module}.apk").read_bytes()
            plugin.update(apkUrl=f"{CDN}@{commit}/{module}.apk", sha256=hashlib.sha256(apk).hexdigest(), size=len(apk))
        else:
            old = previous[plugin["id"]]
            plugin.update(apkUrl=old["apkUrl"], sha256=old["sha256"], size=old["size"])
        plugins.append(plugin)
    for entry in json.loads((ROOT / "third-party.json").read_text()):
        missing = THIRD_PARTY_FIELDS - entry.keys()
        if missing:
            sys.exit(f"third-party.json: {entry.get('id', '?')} is missing {sorted(missing)}")
        plugins.append(entry)

    index = json.dumps({"version": 1, "plugins": plugins}, indent=2) + "\n"
    if index_file.exists() and index_file.read_text() == index:
        print("nothing to publish")
        return
    index_file.write_text(index)
    run("git", "add", "plugins.json", cwd=builds)
    run("git", "commit", "-q", "-m", "index: " + (", ".join(m["name"] for m in changed) or "third-party plugins"),
        cwd=builds)
    print("published", ", ".join(m["name"] for m in changed) or "index only")


if __name__ == "__main__":
    main(sys.argv[1])
