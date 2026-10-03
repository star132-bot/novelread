#!/usr/bin/env bash
# Copy the live APK and voice packs to the GitHub release "downloads", which the app server hands
# out through a mainland proxy (DOWNLOAD_MIRROR_URL, see docs/cloud-library.md). Run where gh is
# signed in and GitHub is fast; safe to re-run. Files are taken from dist/ (and
# $MKREAD_MIRROR_SOURCES) when their SHA-256 matches, otherwise downloaded from the library.
#
#   scripts/sync-github-mirror.sh
set -euo pipefail

library="${MKREAD_LIBRARY_URL:-https://books.mkauth.sbs}"
tag=downloads
cd "$(dirname "$0")/.."

if ! gh release view "$tag" >/dev/null 2>&1; then
  gh release create "$tag" --prerelease --latest=false --title "Download mirror" \
    --notes "APKs and voice packs for mainland downloads. Managed by scripts/sync-github-mirror.sh; do not edit."
fi
existing="$(gh release view "$tag" --json assets --jq '.assets[] | "\(.name) \(.size)"')"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

{
  curl -fsS "$library/api/v1/voices" \
    | python3 -c 'import json,sys; [print(v["packageUrl"], v["packageSize"], v["packageSha256"]) for v in json.load(sys.stdin)["voices"]]'
  curl -fsS "$library/api/v1/app/latest" \
    | python3 -c 'import json,sys; r=json.load(sys.stdin); r.get("apkUrl") and print(r["apkUrl"], r["apkSize"], r["apkSha256"])'
} | while read -r url size sha; do
  name="${url##*/}"  # the library and the mirror use the same immutable file name
  if grep -qx "$name $size" <<<"$existing"; then
    echo "present   $name"
    continue
  fi
  file="$work/$name"
  for candidate in dist/* ${MKREAD_MIRROR_SOURCES:+"$MKREAD_MIRROR_SOURCES"/*}; do
    if [ -f "$candidate" ] && [ "$(stat -f %z "$candidate" 2>/dev/null || stat -c %s "$candidate")" = "$size" ] \
      && [ "$(shasum -a 256 "$candidate" | cut -d' ' -f1)" = "$sha" ]; then
      cp "$candidate" "$file"
      break
    fi
  done
  [ -f "$file" ] || curl -fsS -o "$file" "$url" </dev/null
  if [ "$(shasum -a 256 "$file" | cut -d' ' -f1)" != "$sha" ]; then
    echo "checksum mismatch for $name" >&2
    exit 1
  fi
  gh release upload "$tag" "$file" --clobber </dev/null
  rm -f "$file"
  echo "uploaded  $name ($((size / 1048576)) MB)"
done
