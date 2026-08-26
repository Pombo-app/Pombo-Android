#!/usr/bin/env bash
#
# Refreshes app/src/main/assets/pombo-vendor.bundle.js from the web repo's
# webpack vendor build (entry src/streamr-bundle.js — the Streamr SDK plus
# ethers the WebView runs). The file used to be copied by hand, which left no
# record of what produced it; this builds it from source and prints the
# provenance line to put in the commit message.
#
# Usage:  scripts/update-vendor-bundle.sh [path-to-web-repo]
#         (default: ../Pombo Web)
set -euo pipefail

WEB="${1:-../Pombo Web}"
if [ ! -f "$WEB/webpack.config.js" ]; then
    echo "web repo not found at $WEB (pass its path as the first argument)" >&2
    exit 1
fi

cd "$(dirname "$0")/.."
DEST="app/src/main/assets/pombo-vendor.bundle.js"

echo "==> building the web vendor bundle"
( cd "$WEB" && npx webpack --mode production )

SRC="$WEB/js/vendor.bundle.js"
[ -f "$SRC" ] || { echo "vendor bundle not produced at $SRC" >&2; exit 1; }

if [ -n "$(git -C "$WEB" status --porcelain -- src/streamr-bundle.js package-lock.json 2>/dev/null)" ]; then
    echo "WARNING: the web repo has uncommitted changes to the bundle inputs —" >&2
    echo "the provenance commit below will not describe this build" >&2
fi

cp "$SRC" "$DEST"
WEB_COMMIT="$(git -C "$WEB" rev-parse --short HEAD)"
SHA256="$(sha256sum "$DEST" | awk '{print $1}')"

echo "==> done"
echo "    source:  web repo @ $WEB_COMMIT (webpack vendor entry, production)"
echo "    sha256:  $SHA256"
echo "Put the provenance in the commit message, e.g.:"
echo "    Update the vendor bundle (web @ $WEB_COMMIT, sha256 $SHA256)"
