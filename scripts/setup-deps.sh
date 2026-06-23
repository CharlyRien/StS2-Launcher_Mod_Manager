#!/usr/bin/env bash
# One-shot bootstrap: provisions every build-time + run-time binary this repo needs
# and drops them at the paths the build expects. Idempotent — re-running just overwrites.
#
# What it does for you automatically now:
#   • downloads the Ekyso base APK + Godot 4.5.1 mono export templates if absent
#   • auto-detects sts2.dll from your local Steam install (macOS/Linux/Windows)
#   • generates a local dev signing keystore so release builds are installable
#   • resolves python3, the Android SDK path, and an installed NDK per-OS
# The only thing it can't fetch for you is the FMOD SDK's fmod.jar (gated behind a
# free FMOD account) — see the FMOD section below; without it a no-audio stub is used.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_PARENT="$(cd "$ROOT/.." && pwd)"
DEPS_DIR="${DEPS_DIR:-$REPO_PARENT/req_files}"

# ---- Pinned upstream artifacts (public, auto-downloadable) -------------------
EKYSO_APK_URL="https://github.com/Ekyso/StS2-Launcher/releases/download/0.2.0/StS2Launcher-v0.2.0.apk"
GODOT_TPZ_URL="https://github.com/godotengine/godot-builds/releases/download/4.5.1-stable/Godot_v4.5.1-stable_mono_export_templates.tpz"
GODOT_VER="4.5.1-stable"

EKYSO_APK="$DEPS_DIR/StS2Launcher-v0.2.0.apk"
GODOT_TPZ="$DEPS_DIR/Godot_v4.5.1-stable_mono_export_templates.tpz"

# csproj references `../../upstream/...` relative to src/STS2Mobile/ → repo root.
UPSTREAM_PUBLISH="$ROOT/upstream/godot-export/.godot/mono/publish/arm64"
LIBS_RELEASE="$ROOT/android/libs/release"
LIBS_DEBUG="$ROOT/android/libs/debug"
BCL_DIR="$ROOT/android/assets/dotnet_bcl"
WORK="$ROOT/.setup-work"

log()  { echo "==> $*"; }
warn() { echo "WARNING: $*" >&2; }
fail() { echo "ERROR: $*" >&2; exit 1; }

# ---- 0. Tool resolution -----------------------------------------------------
PYTHON="$(command -v python3 || command -v python || true)"
[ -n "$PYTHON" ] || fail "Need python3 (or python) on PATH for the AAR patch step."

KEYTOOL="$(command -v keytool || true)"
JAVAC="$(command -v javac || true)"

mkdir -p "$DEPS_DIR"

# ---- 1. Auto-download public inputs (skip if already present) ---------------
fetch() { # url dest human-name
    local url="$1" dest="$2" name="$3"
    if [ -s "$dest" ]; then
        log "$name already present ($(du -h "$dest" | cut -f1)) — skipping download."
        return
    fi
    # Download to a .part file and rename on success, so an interrupted download is
    # never mistaken for a complete one; curl -C - resumes the partial on re-run.
    log "Downloading $name ..."
    curl -fL -C - -o "$dest.part" "$url" || fail "Download failed: $url"
    mv -f "$dest.part" "$dest"
    log "$name downloaded ($(du -h "$dest" | cut -f1))."
}
fetch "$EKYSO_APK_URL" "$EKYSO_APK" "Ekyso base APK (v0.2.0)"
fetch "$GODOT_TPZ_URL" "$GODOT_TPZ" "Godot $GODOT_VER mono export templates (~1.2 GB)"

# ---- 2. Locate sts2.dll from the user's own game install --------------------
# The managed game assembly is identical IL across platforms, so any copy works as
# a compile reference. Probe (in order): explicit override, macOS, Linux, Windows,
# then the legacy req_files folder.
STEAM_GAME_DIR=""
sts2_candidates=()
[ -n "${STS2_GAME_DIR:-}" ] && sts2_candidates+=("$STS2_GAME_DIR")
sts2_candidates+=(
    "$HOME/Library/Application Support/Steam/steamapps/common/Slay the Spire 2/SlayTheSpire2.app/Contents/Resources/data_sts2_macos_arm64"
    "$HOME/Library/Application Support/Steam/steamapps/common/Slay the Spire 2/SlayTheSpire2.app/Contents/Resources/data_sts2_macos_x86_64"
    "$HOME/.steam/steam/steamapps/common/Slay the Spire 2/data_sts2_linux_x86_64"
    "$HOME/.local/share/Steam/steamapps/common/Slay the Spire 2/data_sts2_linux_x86_64"
    "$HOME/.steam/steam/steamapps/common/Slay the Spire 2/data_sts2_windows_x86_64"
    "$DEPS_DIR/data_sts2_windows_x86_64"
)
for cand in "${sts2_candidates[@]}"; do
    if [ -f "$cand/sts2.dll" ]; then STEAM_GAME_DIR="$cand"; break; fi
done
[ -n "$STEAM_GAME_DIR" ] || fail "Could not find sts2.dll. Install Slay the Spire 2, or set STS2_GAME_DIR=/path/to/folder/containing/sts2.dll"
log "Game assembly: $STEAM_GAME_DIR/sts2.dll"

# ---- 3. Preflight -----------------------------------------------------------
log "Preflight checks..."
[ -f "$EKYSO_APK" ] || fail "Missing $EKYSO_APK"
[ -f "$GODOT_TPZ" ] || fail "Missing $GODOT_TPZ"

# ---- 4. Scratch workspace ---------------------------------------------------
log "Preparing scratch workspace at $WORK"
rm -rf "$WORK"
mkdir -p "$WORK/ekyso_apk" "$WORK/tpz" "$WORK/android_source"

log "Extracting Ekyso APK..."
unzip -oq "$EKYSO_APK" -d "$WORK/ekyso_apk"

# ---- 5. BCL → android/assets/dotnet_bcl/ ------------------------------------
# Full launcher BCL (incl. GodotSharp.dll + Ekyso's custom 0Harmony.dll, used at
# RUNTIME). dotnet publish overlays STS2Mobile.dll + its deps later.
log "Populating $BCL_DIR with BCL from APK..."
mkdir -p "$BCL_DIR"
cp -f "$WORK/ekyso_apk/assets/dotnet_bcl/"*.dll "$BCL_DIR/"

# ---- 6. Native .so → android/libs/release/arm64-v8a/ ------------------------
# Skip libgodot_android.so + libc++_shared.so — those live inside the AAR.
log "Populating $LIBS_RELEASE/arm64-v8a/ with native libs..."
mkdir -p "$LIBS_RELEASE/arm64-v8a"
for so in "$WORK/ekyso_apk/lib/arm64-v8a/"*.so; do
    name="$(basename "$so")"
    case "$name" in
        libgodot_android.so|libc++_shared.so) continue ;;
    esac
    cp -f "$so" "$LIBS_RELEASE/arm64-v8a/$name"
done
log "Mirroring natives into $LIBS_DEBUG/arm64-v8a/"
mkdir -p "$LIBS_DEBUG/arm64-v8a"
cp -f "$LIBS_RELEASE/arm64-v8a/"*.so "$LIBS_DEBUG/arm64-v8a/"

# ---- 7. Godot AAR (engine template) -----------------------------------------
log "Extracting Godot export templates..."
unzip -oq "$GODOT_TPZ" "templates/android_source.zip" -d "$WORK/tpz"
[ -f "$WORK/tpz/templates/android_source.zip" ] || fail "android_source.zip not found inside tpz"
unzip -oq "$WORK/tpz/templates/android_source.zip" -d "$WORK/android_source"
[ -f "$WORK/android_source/libs/release/godot-lib.template_release.aar" ] || fail "Release AAR not found in android_source"

log "Copying AAR → $LIBS_RELEASE/"
cp -f "$WORK/android_source/libs/release/godot-lib.template_release.aar" "$LIBS_RELEASE/"
if [ -f "$WORK/android_source/libs/debug/godot-lib.template_debug.aar" ]; then
    log "Copying debug AAR → $LIBS_DEBUG/"
    cp -f "$WORK/android_source/libs/debug/godot-lib.template_debug.aar" "$LIBS_DEBUG/"
fi

# ---- 8. Patch AAR so its libgodot_android.so is Ekyso's custom engine build --
log "Patching AAR with Ekyso's libgodot_android.so..."
EKYSO_GODOT_SO="$WORK/ekyso_apk/lib/arm64-v8a/libgodot_android.so"
[ -f "$EKYSO_GODOT_SO" ] || fail "Ekyso libgodot_android.so not found"
patch_aar() {
    "$PYTHON" - "$1" "$2" <<'PYEOF'
import os, sys, zipfile
aar, so = sys.argv[1], sys.argv[2]
tmp = aar + ".new"
entry_name = "jni/arm64-v8a/libgodot_android.so"
with zipfile.ZipFile(aar, 'r') as src, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as dst:
    for item in src.infolist():
        if item.filename == entry_name:
            continue
        dst.writestr(item, src.read(item.filename))
    dst.write(so, entry_name)
os.replace(tmp, aar)
print(f"  patched: {os.path.basename(aar)}")
PYEOF
}
patch_aar "$LIBS_RELEASE/godot-lib.template_release.aar" "$EKYSO_GODOT_SO"
[ -f "$LIBS_DEBUG/godot-lib.template_debug.aar" ] && patch_aar "$LIBS_DEBUG/godot-lib.template_debug.aar" "$EKYSO_GODOT_SO"
log "AAR patched."

# ---- 9. bootstrap.pck (minimal Godot project so the engine can initialize) --
# Gitignored (*.pck) and required at runtime — without it the Godot engine aborts
# with "Couldn't load project data ... Is the .pck file missing?". Regenerated
# deterministically from project.godot by the bundled pure-Python script.
log "Generating bootstrap.pck..."
"$PYTHON" "$ROOT/scripts/make-bootstrap-pck.py" >/dev/null \
    && log "  bootstrap.pck -> android/assets/" \
    || fail "make-bootstrap-pck.py failed"

# ---- 10. FMOD fmod.jar (org.fmod Java bindings) -----------------------------
# The FMOD *native* libs (.so) already came from the base APK in step 6. The
# org.fmod Java bindings (fmod.jar) are needed at compile time AND are loaded by
# the native libs at startup — a partial stub CRASHES the launcher on launch.
# Resolution order: a real SDK jar you point at; else extract the REAL bindings
# from the base APK (no FMOD account needed); else (opt-in) a crash-prone stub.
log "Resolving fmod.jar..."
FMOD_JAR_FOUND=""
fmod_candidates=()
[ -n "${FMOD_JAR:-}" ] && fmod_candidates+=("$FMOD_JAR")
for tgz in "$DEPS_DIR"/fmodstudioapi*android.tar.gz; do
    [ -f "$tgz" ] || continue
    tar -xzf "$tgz" -C "$WORK" 2>/dev/null || true
    found="$(find "$WORK" -name fmod.jar -path '*api/core/lib/*' 2>/dev/null | head -1)"
    [ -n "$found" ] && fmod_candidates+=("$found")
done
while IFS= read -r f; do fmod_candidates+=("$f"); done < <(
    find "$HOME/Downloads" "$DEPS_DIR" -maxdepth 5 -name fmod.jar -path '*api/core/lib/*' 2>/dev/null | head -4
)
for cand in "${fmod_candidates[@]:-}"; do
    [ -n "$cand" ] && [ -f "$cand" ] && { FMOD_JAR_FOUND="$cand"; break; }
done

place_fmod() { cp -f "$1" "$LIBS_RELEASE/fmod.jar"; cp -f "$1" "$LIBS_DEBUG/fmod.jar"; }

if [ -n "$FMOD_JAR_FOUND" ]; then
    log "Using real fmod.jar: $FMOD_JAR_FOUND"
    place_fmod "$FMOD_JAR_FOUND"
else
    # Extract the real org.fmod bindings from the base APK (it's a working launcher).
    D2J="$(command -v d2j-dex2jar || command -v dex2jar || true)"
    if [ -n "$D2J" ]; then
        log "Extracting real FMOD bindings from the base APK (via dex2jar)..."
        rm -rf "$WORK/fmodx"; mkdir -p "$WORK/fmodx"
        # NB: verify with a file test, not `unzip -l | grep -q` — under `set -o pipefail`
        # grep -q closes the pipe early and unzip's SIGPIPE (141) fails the whole chain.
        if "$D2J" "$EKYSO_APK" -o "$WORK/fmodx/all.jar" -f >/dev/null 2>&1 \
           && ( cd "$WORK/fmodx" && unzip -oq all.jar "org/fmod/*" && jar cf fmod.jar org ) \
           && [ -f "$WORK/fmodx/org/fmod/FMOD.class" ]; then
            place_fmod "$WORK/fmodx/fmod.jar"
            FMOD_JAR_FOUND="base-apk"
            log "  real FMOD bindings extracted ($(find "$WORK/fmodx/org/fmod" -name '*.class' | wc -l | tr -d ' ') classes)."
        else
            warn "dex2jar extraction failed."
        fi
    fi
fi

if [ -z "$FMOD_JAR_FOUND" ]; then
    if [ "${FMOD_ALLOW_STUB:-0}" != "1" ]; then
        fail "No FMOD bindings available. The native FMOD libs need the real org.fmod classes;
       a stub CRASHES the launcher at startup. Fix one of:
         - install dex2jar so they can be extracted from the base APK:  brew install dex2jar
         - or download the FMOD SDK (free account, fmod.com) and:  FMOD_JAR=/path/to/api/core/lib/fmod.jar
       (FMOD_ALLOW_STUB=1 forces a stub for a compile-only test — the resulting APK will crash on a device.)"
    fi
    warn "FMOD_ALLOW_STUB=1 — generating a stub. The APK will CRASH on launch (native FMOD"
    warn "  calls into org.fmod methods the stub lacks). Compile-only test, not a usable build."
    [ -n "$JAVAC" ] || fail "No javac to build the stub. Install a JDK or supply fmod.jar."
    ANDROID_JAR_FOR_STUB="$(find "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platforms" -name android.jar 2>/dev/null | sort -V | tail -1)"
    [ -n "$ANDROID_JAR_FOR_STUB" ] || fail "android.jar not found to compile the FMOD stub (install an Android platform)."
    STUB="$WORK/fmodstub"; mkdir -p "$STUB/org/fmod"
    cat > "$STUB/org/fmod/FMOD.java" <<'JAVA'
package org.fmod;
import android.content.Context;
/* COMPILE-ONLY STUB — not the real FMOD SDK and NOT runtime-safe (crashes). */
public class FMOD {
    public static boolean init(Context c) { return true; }
    public static void close() {}
}
JAVA
    ( cd "$STUB" && "$JAVAC" -cp "$ANDROID_JAR_FOR_STUB" org/fmod/FMOD.java && jar cf fmod.jar org/fmod/FMOD.class )
    place_fmod "$STUB/fmod.jar"
    log "FMOD stub placed (crashes at runtime)."
fi

# Crypto JAR: build.gradle references it for the Mono TLS native lib.
CRYPTO_JAR_DIR="$ROOT/vendor/godot/modules/mono/thirdparty"
CRYPTO_JAR="$CRYPTO_JAR_DIR/libSystem.Security.Cryptography.Native.Android.jar"
if [ ! -f "$CRYPTO_JAR" ]; then
    log "Fetching Mono crypto JAR from godot $GODOT_VER..."
    mkdir -p "$CRYPTO_JAR_DIR"
    curl -fsSL -o "$CRYPTO_JAR" \
        "https://raw.githubusercontent.com/godotengine/godot/$GODOT_VER/modules/mono/thirdparty/libSystem.Security.Cryptography.Native.Android.jar"
fi
[ -f "$CRYPTO_JAR" ] || fail "Crypto JAR missing"

# ---- 10. Compile-time reference for the csproj ------------------------------
# Only sts2.dll now: GodotSharp + Harmony are compile-only NuGet PackageReferences
# (see STS2Mobile.csproj); their runtime copies live in the harvested BCL.
log "Placing sts2.dll compile reference at $UPSTREAM_PUBLISH/"
mkdir -p "$UPSTREAM_PUBLISH"
cp -f "$STEAM_GAME_DIR/sts2.dll" "$UPSTREAM_PUBLISH/"
for extra in sts2.pdb sts2.deps.json; do
    [ -f "$STEAM_GAME_DIR/$extra" ] && cp -f "$STEAM_GAME_DIR/$extra" "$UPSTREAM_PUBLISH/" || true
done

# ---- 11. android/local.properties (OS-aware SDK + installed NDK) ------------
LOCAL_PROPS="$ROOT/android/local.properties"
if [ ! -f "$LOCAL_PROPS" ]; then
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$SDK" ]; then
        case "$(uname -s)" in
            Darwin) SDK="$HOME/Library/Android/sdk" ;;
            *)      SDK="$HOME/Android/Sdk" ;;
        esac
    fi
    log "Writing $LOCAL_PROPS (sdk.dir=$SDK)"
    printf 'sdk.dir=%s\n' "$SDK" > "$LOCAL_PROPS"
    NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
    if [ -n "$NDK" ]; then
        printf 'ndk.dir=%s\n' "$NDK" >> "$LOCAL_PROPS"
    else
        warn "No NDK found under $SDK/ndk — gradle may warn (no native compilation happens, so any NDK works)."
    fi
else
    log "$LOCAL_PROPS already exists — leaving it as-is."
fi

# ---- 12. Dev signing keystore (so release APKs are installable) -------------
# Sideload-only project: a personal throwaway keystore is fine. Password defaults
# to STS2_KEYSTORE_PASSWORD or 'sts2android'. keystore.properties is gitignored.
KEYSTORE="$ROOT/android/sts2.keystore"
KEYSTORE_PROPS="$ROOT/android/keystore.properties"
KS_PASS="${STS2_KEYSTORE_PASSWORD:-sts2android}"
if [ ! -f "$KEYSTORE" ]; then
    if [ -n "$KEYTOOL" ]; then
        log "Generating dev signing keystore $KEYSTORE (alias sts2)"
        "$KEYTOOL" -genkeypair -v -keystore "$KEYSTORE" -alias sts2 -keyalg RSA -keysize 2048 \
            -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
            -dname "CN=StS2 Launcher Dev, O=local, C=US" >/dev/null 2>&1 \
            && log "Keystore created." || warn "keytool failed — release builds will be unsigned."
    else
        warn "keytool not found — skipping keystore generation (release builds will need signing)."
    fi
fi
if [ -f "$KEYSTORE" ] && [ ! -f "$KEYSTORE_PROPS" ]; then
    printf 'release_keystore_password=%s\nrelease_keystore_alias=sts2\n' "$KS_PASS" > "$KEYSTORE_PROPS"
    log "Wrote $KEYSTORE_PROPS (gitignored)."
fi

# ---- 13. Clean up + report --------------------------------------------------
log "Cleaning scratch workspace..."
rm -rf "$WORK"

log "Done."
echo ""
echo "Next step:"
echo "    bash scripts/build.sh        # builds patcher + APK"
echo "  or manually:"
echo "    ( cd '$ROOT/src/STS2Mobile' && dotnet publish -c Release )"
echo "    ( cd '$ROOT/android' && ./gradlew assembleMonoRelease )"
