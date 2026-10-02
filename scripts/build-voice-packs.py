#!/usr/bin/env python3
"""Split the full speech asset tree into what the APK bundles and downloadable voice packs.

    build-voice-packs.py <full-assets-dir> <output-dir>

Writes <output-dir>/bundled-assets (Matcha + its speech-assets.json) and
<output-dir>/voice-packs/<pack>-r<revision>.zip for every other voice. Each zip holds
pack.json (id, revision, per-file size and SHA-256) plus the files at their install paths.
Pack ids and revisions must match VoiceModel in the app.
"""
import hashlib
import json
import os
import shutil
import sys
import zipfile

PACKS = {  # pack id -> (revision, install path prefixes)
    "matcha-zh-en": (1, ["models/matcha-zh-en/"]),
    "melo-zh-en": (1, ["models/melo-zh-en/"]),
    "kokoro-zh-en": (1, ["models/kokoro-zh-en/"]),
    "zipvoice": (2, ["models/zipvoice/", "voices/"]),
}
BUNDLED = "matcha-zh-en"


def main(full: str, out: str) -> None:
    with open(os.path.join(full, "speech-assets.json"), encoding="utf-8") as fh:
        records = json.load(fh)["files"]

    def select(prefixes):
        return [r for r in records if any(r["path"].startswith(p) for p in prefixes)]

    bundled = os.path.join(out, "bundled-assets")
    shutil.rmtree(bundled, ignore_errors=True)
    chosen = select(PACKS[BUNDLED][1])
    for record in chosen:
        target = os.path.join(bundled, record["path"])
        os.makedirs(os.path.dirname(target), exist_ok=True)
        shutil.copy2(os.path.join(full, record["path"]), target)
    with open(os.path.join(bundled, "speech-assets.json"), "w", encoding="utf-8") as fh:
        json.dump({"schemaVersion": 1, "files": chosen}, fh, indent=4)
        fh.write("\n")
    print(f"bundled-assets: {len(chosen)} files ({BUNDLED})")

    packs_dir = os.path.join(out, "voice-packs")
    shutil.rmtree(packs_dir, ignore_errors=True)
    os.makedirs(packs_dir)
    for pack_id, (revision, prefixes) in PACKS.items():
        if pack_id == BUNDLED:
            continue
        files = select(prefixes)
        manifest = {"schemaVersion": 1, "id": pack_id, "revision": revision, "files": files}
        archive = os.path.join(packs_dir, f"{pack_id}-r{revision}.zip")
        # Stored, not deflated: models barely compress and phones extract faster.
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_STORED) as zf:
            zf.writestr("pack.json", json.dumps(manifest, indent=2))
            for record in files:
                zf.write(os.path.join(full, record["path"]), record["path"])
        digest = hashlib.sha256()
        with open(archive, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                digest.update(chunk)
        print(f"voice pack {os.path.basename(archive)}: {os.path.getsize(archive) // 1048576} MB "
              f"sha256={digest.hexdigest()[:16]}…")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
