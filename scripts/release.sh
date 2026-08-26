#!/usr/bin/env bash
#
# Cuts a GitHub release from a tag: runs the tests, builds the signed APK, and
# refuses to publish anything not signed by the project key.
#
# The signing key stays on this machine, so releases are cut here rather than
# by CI. The fingerprint check is the point of the script: a release variant
# with no credentials falls back to the debug key and produces an APK that
# looks fine and cannot be installed over a real one. That has happened once,
# to v0.8.0.
#
# Usage:  scripts/release.sh v0.8.1
#
set -euo pipefail

TAG="${1:-}"
if [ -z "$TAG" ]; then
    echo "usage: scripts/release.sh <tag>   (e.g. v0.8.1)" >&2
    exit 1
fi

# Must match .well-known/assetlinks.json on app.pombo.cc, or Android App Links
# stop verifying for everyone who installs this build.
EXPECTED_SHA256="959f199faa930a0953592b67c42043782db9446e67a66948b0f1ba2295da9942"

cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain)" ]; then
    echo "working tree is dirty — commit or stash first" >&2
    exit 1
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "main" ]; then
    echo "releases are cut from main, not $BRANCH" >&2
    exit 1
fi

git fetch --tags --quiet
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "tag $TAG already exists" >&2
    exit 1
fi

# Gradle picks whatever JDK is on PATH, which on this machine is a JDK 25 the
# Android plugin does not support. Build with the Android Studio JBR unless the
# caller has already chosen a JDK.
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in \
        "/c/Program Files/Android/Android Studio/jbr" \
        "$HOME/AppData/Local/Programs/Android Studio/jbr"
    do
        if [ -x "$candidate/bin/java" ] || [ -x "$candidate/bin/java.exe" ]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "JAVA_HOME is unset and the Android Studio JBR was not found" >&2
    echo "set JAVA_HOME to a JDK 17 before running this" >&2
    exit 1
fi
echo "==> JAVA_HOME=$JAVA_HOME"

find_build_tool() {
    ls "$HOME/AppData/Local/Android/Sdk/build-tools/"*/"$1" 2>/dev/null | sort -V | tail -1
}
APKSIGNER="$(find_build_tool apksigner.bat)"
AAPT="$(find_build_tool aapt2.exe)"
if [ -z "$APKSIGNER" ]; then
    echo "apksigner not found in the Android SDK build-tools" >&2
    exit 1
fi

# Tag first: the version baked into the APK is derived from the tag, so a build
# made before tagging would ship the previous version's number.
echo "==> tagging $TAG"
git tag -a "$TAG" -m "$TAG"

echo "==> running tests"
./gradlew testDebugUnitTest

echo "==> building the signed release"
rm -rf app/build/outputs/apk/release
./gradlew assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }

echo "==> verifying the signature"
ACTUAL="$("$APKSIGNER" verify --print-certs "$APK" \
    | grep -i 'Signer #1 certificate SHA-256 digest' \
    | awk '{print $NF}')"

if [ "$ACTUAL" != "$EXPECTED_SHA256" ]; then
    echo >&2
    echo "REFUSING TO PUBLISH: wrong signing key." >&2
    echo "  expected $EXPECTED_SHA256" >&2
    echo "  got      $ACTUAL" >&2
    echo "The release credentials are missing; the build fell back to the debug key." >&2
    git tag -d "$TAG" >/dev/null
    exit 1
fi

if [ -n "$AAPT" ]; then
    echo "==> $("$AAPT" dump badging "$APK" | grep '^package')"
fi

ASSET="Pombo.$TAG.apk"
cp "$APK" "$ASSET"
APK_SHA256="$(sha256sum "$ASSET" | awk '{print $1}')"
echo "$APK_SHA256  $ASSET" > "$ASSET.sha256"
trap 'rm -f "$ASSET" "$ASSET.sha256"' EXIT
echo "==> APK sha256: $APK_SHA256"

echo "==> pushing the tag and creating the release"
git push origin "$TAG"
gh release create "$TAG" "$ASSET" "$ASSET.sha256" --title "$TAG" \
    --notes "APK SHA-256: \`$APK_SHA256\`" --generate-notes

echo "==> done: $TAG"
