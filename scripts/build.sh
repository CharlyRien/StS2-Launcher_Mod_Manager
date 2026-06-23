#!/usr/bin/env bash
set -euo pipefail

NO_BUMP=false
if [[ "${1:-}" == "--no-bump" ]]; then
    NO_BUMP=true
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PATCHER_DIR="$ROOT/src/STS2Mobile"
BUILD_DIR="$ROOT/android"
GRADLE_PROPS="$BUILD_DIR/gradle.properties"
APK_DIR="$BUILD_DIR/build/outputs/apk/mono/release"
BCL_DIR="$BUILD_DIR/assets/dotnet_bcl"

# 0. Guard: the harvested deps must exist (run setup-deps.sh first). Without them
# gradle would happily package an APK missing GodotSharp.dll / the engine AAR that
# then crashes on the device.
if [ ! -f "$BCL_DIR/GodotSharp.dll" ] || ! ls "$BUILD_DIR"/libs/release/*.aar >/dev/null 2>&1; then
    echo "ERROR: dependencies not provisioned (missing $BCL_DIR/GodotSharp.dll or libs/release/*.aar)." >&2
    echo "       Run 'bash scripts/setup-deps.sh' first." >&2
    exit 1
fi

# 1. Format (best-effort — never block the build on a missing/incompatible formatter)
if [ -x "$HOME/.dotnet/tools/csharpier" ]; then
    echo "Formatting C# code..."
    "$HOME/.dotnet/tools/csharpier" format "$PATCHER_DIR" || echo "csharpier failed — skipping format."
else
    echo "csharpier not installed — skipping format (install: dotnet tool install -g csharpier)."
fi

# 2. Build patcher
echo "Building patcher..."
cd "$PATCHER_DIR"
dotnet publish -c Release

PUBLISH_DIR="$PATCHER_DIR/bin/Release/net9.0/publish"
mkdir -p "$BCL_DIR"

cp "$PUBLISH_DIR"/STS2Mobile.dll "$PUBLISH_DIR"/SteamKit2.dll \
   "$PUBLISH_DIR"/protobuf-net.dll "$PUBLISH_DIR"/protobuf-net.Core.dll \
   "$PUBLISH_DIR"/System.IO.Hashing.dll "$PUBLISH_DIR"/ZstdSharp.dll \
   "$BCL_DIR/"

# GodotSharp.dll / 0Harmony.dll are NOT copied here — they are already present in
# $BCL_DIR from the Ekyso BCL harvest (scripts/setup-deps.sh). The csproj references
# them as compile-only NuGet packages, so they are not in the publish output.

CRYPTO_SO="$HOME/.nuget/packages/microsoft.netcore.app.runtime.mono.android-arm64/9.0.7/runtimes/android-arm64/native/libSystem.Security.Cryptography.Native.Android.so"
if [ -f "$CRYPTO_SO" ]; then
    cp "$CRYPTO_SO" "$BUILD_DIR/libs/release/arm64-v8a/"
fi

echo "Copied patcher + dependencies to android assets"

# 3. Bump version (skip with --no-bump)
CURRENT_NAME=$(grep '^export_version_name=' "$GRADLE_PROPS" | cut -d= -f2)
CURRENT_CODE=$(grep '^export_version_code=' "$GRADLE_PROPS" | cut -d= -f2)

if [ "$NO_BUMP" = true ]; then
    NEW_NAME="$CURRENT_NAME"
    NEW_CODE="$CURRENT_CODE"
    echo "Version: $NEW_NAME ($NEW_CODE) (no bump)"
else
    IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_NAME"
    PATCH=$((PATCH + 1))
    NEW_NAME="$MAJOR.$MINOR.$PATCH"
    NEW_CODE=$((CURRENT_CODE + 1))

    # sed -i.bak works on both GNU and BSD/macOS sed; remove the backup after.
    sed -i.bak "s/^export_version_name=.*/export_version_name=$NEW_NAME/" "$GRADLE_PROPS"
    sed -i.bak "s/^export_version_code=.*/export_version_code=$NEW_CODE/" "$GRADLE_PROPS"
    rm -f "$GRADLE_PROPS.bak"
    echo "Version: $CURRENT_NAME ($CURRENT_CODE) -> $NEW_NAME ($NEW_CODE)"
fi

# 4. Build APK
echo "Building APK..."
cd "$BUILD_DIR"

# Pass the local dev signing credentials if setup-deps.sh generated them (gitignored).
# Without these gradle would abort on the perform_signing=true config; with them the
# release APK is signed and directly installable via `adb install`.
GRADLE_SIGN_ARGS=()
if [ -f "$BUILD_DIR/keystore.properties" ]; then
    # Parse (do NOT `source`) — keystore.properties is a Java .properties file, not
    # shell. Sourcing it would break on / execute special chars in the password.
    KS_PW=$(sed -n 's/^release_keystore_password=//p' "$BUILD_DIR/keystore.properties")
    KS_ALIAS=$(sed -n 's/^release_keystore_alias=//p' "$BUILD_DIR/keystore.properties")
    GRADLE_SIGN_ARGS=(
        "-Prelease_keystore_password=${KS_PW}"
        "-Prelease_keystore_alias=${KS_ALIAS:-sts2}"
    )
else
    echo "Note: no android/keystore.properties — run scripts/setup-deps.sh to generate a dev keystore,"
    echo "      or pass -Pperform_signing=false for an unsigned APK."
fi

# Note: ${arr[@]+"${arr[@]}"} so an empty array doesn't trip `set -u` on bash 3.2 (macOS default).
./gradlew assembleMonoRelease ${GRADLE_SIGN_ARGS[@]+"${GRADLE_SIGN_ARGS[@]}"}

echo "Done: $APK_DIR/StS2Launcher-v$NEW_NAME.apk"
