#!/usr/bin/env bash
# Upload every live APK and voice pack to the download mirror (see server/app/mirror.py).
# Runs on the app server as /opt/mkread-library/sync-download-mirror.sh; safe to re-run.
#
# Needs DOWNLOAD_MIRROR_URL=https://<bucket>.oss-<region>.aliyuncs.com in .env and ossutil 2
# credentials from `ossutil config` (a RAM key that can only write this bucket). Uploads go through
# the region's internal endpoint, which is free and fast from an ECS in the same region.
set -euo pipefail
cd /opt/mkread-library

url="$(grep -E '^DOWNLOAD_MIRROR_URL=' .env | cut -d= -f2- | tr -d "\"' ")"
if [[ ! $url =~ ^https://([a-z0-9-]+)\.oss-([a-z0-9-]+)\.aliyuncs\.com/?$ ]]; then
  echo "DOWNLOAD_MIRROR_URL in .env must look like https://<bucket>.oss-<region>.aliyuncs.com" >&2
  exit 1
fi
bucket="${BASH_REMATCH[1]}"
region="${BASH_REMATCH[2]}"
oss=(--region "$region" -e "oss-$region-internal.aliyuncs.com")

docker compose exec -T api python -m app.mirror plan | while IFS=$'\t' read -r path key size; do
  if ossutil stat "oss://$bucket/$key" "${oss[@]}" </dev/null >/dev/null 2>&1; then
    echo "present   $key"
    continue
  fi
  case "$key" in
    *.apk) type=application/vnd.android.package-archive ;;
    *) type=application/zip ;;
  esac
  ossutil cp "/srv/mkread-library/$path" "oss://$bucket/$key" "${oss[@]}" \
    --content-type "$type" --cache-control "public, max-age=31536000, immutable" </dev/null >/dev/null
  echo "uploaded  $key ($((size / 1048576)) MB)"
done
