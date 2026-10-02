#!/usr/bin/env bash
# Build a signed release, attach it to a GitHub release, and publish it to the cloud library so
# installed apps offer the update.
#
#   MKREAD_APP_SERVER=<ssh host of the app server> scripts/publish-release.sh 0.3.0 3 "更新说明"
#
# Signing comes from ~/.gradle/gradle.properties (see docs/cloud-library.md). The app server pulls
# the APK from GitHub through a mirror and checks its SHA-256, so nothing large is pushed to it.
set -euo pipefail

version_name="${1:?version name, e.g. 0.3.0}"
version_code="${2:?version code, e.g. 3}"
notes="${3:-}"
server="${MKREAD_APP_SERVER:?set MKREAD_APP_SERVER to the app server ssh host}"
mirror="${MKREAD_GITHUB_MIRROR:-https://ghfast.top/}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

grep -q '^mkread.signing.storeFile=' ~/.gradle/gradle.properties || {
  echo "Release signing is not configured in ~/.gradle/gradle.properties" >&2
  exit 1
}

./gradlew -q :app:assembleRelease -Pmkread.versionCode="$version_code" -Pmkread.versionName="$version_name"
apk="app/build/outputs/apk/release/app-release.apk"
apksigner="$(ls -d "${ANDROID_HOME:?}"/build-tools/*/apksigner | sort -V | tail -1)"
"$apksigner" verify --print-certs "$apk" | grep 'SHA-256 digest'

mkdir -p dist
asset="dist/mkread-$version_name.apk"
cp "$apk" "$asset"
sha="$(shasum -a 256 "$asset" | cut -d' ' -f1)"
echo "$asset sha256=$sha"

tag="v$version_name"
gh release create "$tag" "$asset" --title "MKread $version_name" --notes "${notes:-MKread $version_name}" \
  --target "$(git rev-parse HEAD)"
owner_repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
url="${mirror}https://github.com/$owner_repo/releases/download/$tag/$(basename "$asset")"

# shellcheck disable=SC2029
ssh "$server" "cd /opt/mkread-library && docker compose exec -T api python -m app.releases publish \
  --url '$url' --version-code $version_code --version-name '$version_name' --sha256 $sha \
  --notes $(printf %q "$notes")"
echo "Published $version_name ($version_code)"
