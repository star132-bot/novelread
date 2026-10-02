#!/usr/bin/env bash
# macOS/Linux port of fetch-speech-assets.ps1: downloads the locked speech assets,
# verifies them against speech-assets.lock.json, and stages .local-assets/debug-assets.
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
local_assets="$repo/.local-assets"
downloads="$local_assets/downloads"
extract="$local_assets/extracted/zipvoice"
debug_assets="$local_assets/debug-assets"
staging="$local_assets/debug-assets-staging"
lock="$repo/speech-assets.lock.json"

mkdir -p "$downloads" "$repo/app/libs"

# name|url|size|sha256 from the lock file
python3 - "$lock" >"$local_assets/lock.tsv" <<'PY'
import json, sys
for a in json.load(open(sys.argv[1], encoding="utf-8"))["assets"]:
    print("|".join([a["name"], a["url"], str(a["size"]), a["sha256"]]))
PY

while IFS='|' read -r name url size sha; do
  dest="$downloads/$name"
  if [[ ! -f "$dest" ]]; then
    echo "Downloading $name..."
    curl -fL --retry 3 -o "$dest.partial" "$url"
    mv -f "$dest.partial" "$dest"
  else
    echo "Reusing $dest"
  fi
  actual_size=$(stat -f%z "$dest" 2>/dev/null || stat -c%s "$dest")
  actual_sha=$(shasum -a 256 "$dest" | awk '{print $1}')
  if [[ "$actual_size" != "$size" || "$actual_sha" != "$sha" ]]; then
    echo "Downloaded asset '$name' does not match speech-assets.lock.json." >&2
    exit 1
  fi
  echo "$name size=$actual_size sha256=$actual_sha"
done <"$local_assets/lock.tsv"
rm -f "$local_assets/lock.tsv"

rm -rf "$extract" "$staging"
mkdir -p "$extract"
tar -xjf "$downloads/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2" -C "$extract"

unique() {
  local type="$1" name="$2" matches
  matches=$(find "$extract" -type "$type" -name "$name")
  if [[ $(printf '%s\n' "$matches" | grep -c .) -ne 1 ]]; then
    echo "Expected exactly one '$name' under '$extract'." >&2
    exit 1
  fi
  printf '%s' "$matches"
}

zv="$staging/models/zipvoice"
prompts="$staging/voices/builtin-dev/prompts"
mkdir -p "$zv" "$prompts"
cp "$(unique f encoder.int8.onnx)" "$zv/encoder.int8.onnx"
cp "$(unique f decoder.int8.onnx)" "$zv/decoder.int8.onnx"
cp "$(unique f tokens.txt)" "$zv/tokens.txt"
cp "$(unique f lexicon.txt)" "$zv/lexicon.txt"
cp -R "$(unique d espeak-ng-data)" "$zv/espeak-ng-data"
cp "$downloads/vocos_24khz.onnx" "$zv/vocos_24khz.onnx"
cp "$(unique f leijun-1.wav)" "$prompts/neutral.wav"
echo '6YKj6L+Y5piv5LiJ5Y2B5YWt5bm05YmNLCDkuIDkuZ3lhavkuIPlubQuIOaIkeWRouiAg+S4iuS6huatpuaxieWkp+WtpueahOiuoeeul+acuuezuy4=' \
  | base64 --decode >"$prompts/neutral.txt"
echo >>"$prompts/neutral.txt"

# Preset-speaker packs: Matcha (fast), MeloTTS (high quality), Kokoro (multi-speaker).
extract_pack() {
  local archive="$1" dir="$2"
  rm -rf "$local_assets/extracted/$dir"
  mkdir -p "$local_assets/extracted"
  tar -xjf "$downloads/$archive" -C "$local_assets/extracted"
}

extract_pack matcha-icefall-zh-en.tar.bz2 matcha-icefall-zh-en
src="$local_assets/extracted/matcha-icefall-zh-en"; dst="$staging/models/matcha-zh-en"
mkdir -p "$dst"
cp "$src"/{model-steps-3.onnx,tokens.txt,lexicon.txt,date-zh.fst,phone-zh.fst,number-zh.fst} "$dst/"
cp -R "$src/espeak-ng-data" "$dst/espeak-ng-data"
cp "$downloads/vocos-16khz-univ.onnx" "$dst/vocos-16khz-univ.onnx"

extract_pack vits-melo-tts-zh_en.tar.bz2 vits-melo-tts-zh_en
src="$local_assets/extracted/vits-melo-tts-zh_en"; dst="$staging/models/melo-zh-en"
mkdir -p "$dst"
cp "$src"/{model.onnx,tokens.txt,lexicon.txt,date.fst,phone.fst,number.fst,new_heteronym.fst} "$dst/"
cp -R "$src/dict" "$dst/dict"

extract_pack kokoro-multi-lang-v1_1.tar.bz2 kokoro-multi-lang-v1_1
src="$local_assets/extracted/kokoro-multi-lang-v1_1"; dst="$staging/models/kokoro-zh-en"
mkdir -p "$dst"
cp "$src"/{model.onnx,voices.bin,tokens.txt,lexicon-us-en.txt,lexicon-zh.txt,date-zh.fst,phone-zh.fst,number-zh.fst} "$dst/"
cp -R "$src/dict" "$dst/dict"
cp -R "$src/espeak-ng-data" "$dst/espeak-ng-data"

# Manifest records are sorted by full path, matching the PowerShell script.
python3 - "$staging" <<'PY'
import hashlib, json, os, sys
root = sys.argv[1]
paths = sorted(os.path.join(d, f) for d, _, fs in os.walk(root) for f in fs)
records = []
for p in paths:
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    records.append({"path": os.path.relpath(p, root).replace(os.sep, "/"),
                    "size": os.path.getsize(p), "sha256": h.hexdigest()})
with open(os.path.join(root, "speech-assets.json"), "w", encoding="utf-8") as out:
    json.dump({"schemaVersion": 1, "files": records}, out, indent=4)
    out.write("\n")
print(f"Wrote manifest with {len(records)} files")
PY

rm -rf "$debug_assets"
mv "$staging" "$debug_assets"
cp "$downloads/sherpa-onnx-1.13.4.aar" "$repo/app/libs/sherpa-onnx-1.13.4.aar"

echo "Speech assets ready under $debug_assets"
echo "sherpa-onnx AAR ready at $repo/app/libs/sherpa-onnx-1.13.4.aar"
