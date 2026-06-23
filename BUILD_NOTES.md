# Build internals & troubleshooting

Companion to the README's *Building* section. The README has the quickstart
(`setup-deps.sh` → `build.sh`); this file explains *why* the build is shaped the way
it is, what each dependency is, and how to debug a broken build.

---

## Why a base (Ekyso) APK? Aren't we building from scratch?

We *do* build a fresh APK — gradle reassembles it from the `android/` project source
(`GodotApp.java`, manifest, resources) on every run. What we **don't** rebuild are
compiled artifacts the fork has no source for and can't reproduce locally:

1. **The custom Godot engine** (`libgodot_android.so`, ~70 MB) — Ekyso's fork of the
   Godot 4.5.1 engine. Rebuilding needs the engine fork source + SCons + the FMOD/Spine
   modules (`scripts/build-godot.sh`). The compiled `.so` is lifted from the base APK.
2. **The native runtime libs** — FMOD (`libfmod.so`, `libfmodstudio.so`,
   `libGodotFmod.*.so`), Spine, Mono (`libmonosgen-2.0.so`, components), the
   Steamworks/Sentry stubs. All prebuilt, all harvested from the base APK.
3. **The .NET BCL for mono-android** (`assets/dotnet_bcl/*.dll`) — the base class
   library the launcher runs against on device, including **GodotSharp.dll** and
   **Ekyso's custom `0Harmony.dll`** (runtime versions).

So the base APK is a **binary parts donor**, not an inject-into target. `setup-deps.sh`
unzips it, copies those `.so` + BCL into `android/libs/` and `android/assets/`, patches
the stock Godot AAR to embed Ekyso's engine `.so`, and gradle then builds a new APK
around them. The fork's *own* output is just the C# patcher (`STS2Mobile.dll`) + the
Android gradle project.

---

## Dependency map (what comes from where)

| Dependency | Source | Provisioned by |
|------------|--------|----------------|
| Ekyso base APK `StS2Launcher-v0.2.0.apk` | github.com/Ekyso/StS2-Launcher release | **auto-downloaded** by setup-deps.sh |
| Godot 4.5.1 mono export templates (`.tpz`, ~1.2 GB) | github.com/godotengine/godot-builds release | **auto-downloaded** by setup-deps.sh |
| Mono crypto JAR | godot 4.5.1 raw GitHub | **auto-downloaded** by setup-deps.sh |
| `sts2.dll` (game assembly, compile ref) | **your** Slay the Spire 2 install | **auto-detected** from the local Steam install (`STS2_GAME_DIR` to override) |
| `GodotSharp.dll`, `0Harmony.dll` (compile refs) | NuGet (`GodotSharp` 4.5.1, `Lib.Harmony` 2.4.2) | NuGet restore — **compile-only** `PackageReference`s in the `.csproj` |
| `fmod.jar` (FMOD Java bindings) | FMOD SDK (free account, **gated**) | located if present, else a **no-audio stub** is generated (see below) |
| dev signing keystore | generated | **auto-created** by setup-deps.sh |
| `gradle-wrapper.jar` | committed in-repo | already present (whitelisted in `.gitignore`) |

Only `sts2.dll` is a local `HintPath` reference in `STS2Mobile.csproj`. `GodotSharp` and
`Lib.Harmony` are compile-only NuGet `PackageReference`s (`<IncludeAssets>compile</IncludeAssets>`),
so they compile the patcher but never land in the publish output — at runtime the device
uses the versions baked into the harvested BCL. (Harmony 2.4.2 matches Ekyso's runtime
`0Harmony.dll`, which is not strong-named, so the exact version isn't even load-bearing.)

---

## Getting `sts2.dll` (the one piece from your own game)

`setup-deps.sh` finds it automatically; this is only for reference / non-standard installs.
It's the game's managed .NET assembly — the C# compiles to a `.dll` on every OS, so any
platform's copy works as a compile reference. **Do not** get it from a third-party source.

Known locations (auto-probed, in order; override with `STS2_GAME_DIR=/path/to/dir`):
```
# macOS (verified)
~/Library/Application Support/Steam/steamapps/common/Slay the Spire 2/SlayTheSpire2.app/Contents/Resources/data_sts2_macos_arm64/sts2.dll
# Linux
~/.steam/steam/steamapps/common/Slay the Spire 2/data_sts2_linux_x86_64/sts2.dll
# Windows: Steam → right-click → Manage → Browse local files → data_sts2_windows_x86_64\sts2.dll
```

---

## Toolchain

| Tool | Version | Notes |
|------|---------|-------|
| .NET SDK | 9.0 | `dotnet` on PATH |
| JDK | **17** | `JAVA_HOME` must point at it (newer JDKs break Gradle/AGP); ships `keytool`/`javac`/`jar` |
| Android SDK | platform **35**, build-tools **35.0.0** | `sdkmanager "platforms;android-35" "build-tools;35.0.0"` |
| Android NDK | optional | `config.gradle` pins `28.1.13356709`, but there is **no** native compilation (no CMake/externalNativeBuild), so any installed NDK works — only a `[CXX1104]` warning + skipped `.so` stripping |
| Gradle | 8.13 | via the committed `./gradlew` wrapper |
| Python 3, curl, unzip, tar | any | used by setup-deps.sh |

---

## FMOD `fmod.jar`

The FMOD *native* libraries come from the base APK, so the FMOD SDK is **not** needed for
them. The SDK provides exactly one thing: `fmod.jar` (the `org.fmod.FMOD` Java bindings
that `GodotApp.java` calls at startup — `FMOD.init(this)` / `FMOD.close()`).

`setup-deps.sh` looks for a real `fmod.jar` via `$FMOD_JAR`, a `req_files/fmodstudioapi*android.tar.gz`,
or `~/Downloads`. If none is found it generates a **compile-only stub** (`org.fmod.FMOD`
with no-op `init`/`close`) so the build still completes — the APK installs and runs, but
in-game audio is silent/unstable. For real audio: make a free account at fmod.com, download
"FMOD Engine" for Android, and re-run with `FMOD_JAR=/path/to/api/core/lib/fmod.jar`.
Set `FMOD_REQUIRE_REAL=1` to make a missing jar a hard error instead of stubbing.

## Signing keystore

Android won't install an unsigned APK. `setup-deps.sh` generates a throwaway dev keystore
(`android/sts2.keystore`, alias `sts2`, gitignored) and writes its password to the gitignored
`android/keystore.properties`; `build.sh` reads that and signs automatically. Override the
default password with `STS2_KEYSTORE_PASSWORD=…` (use alphanumerics). A stable, backed-up
keystore only matters for Play Store distribution, which this sideload-only project doesn't do.

---

## Verified build result

The full `setup-deps.sh` → `build.sh` flow was run end-to-end on macOS and produced a
signed, installable APK (game **v0.107.1**):
```
android/build/outputs/apk/mono/release/StS2Launcher-v<version>.apk
  assets/dotnet_bcl/STS2Mobile.dll      ← the patcher (incl. the Steam mobile-auth fix)
  assets/dotnet_bcl/{GodotSharp,0Harmony}.dll   ← runtime versions, from the BCL harvest
  lib/arm64-v8a/libgodot_android.so     ← Ekyso's custom engine (70 MB)
  lib/arm64-v8a/{libfmod,libfmodstudio}.so
apksigner verify → Verifies (v2 scheme: true)
```

## Install

```bash
adb install -r android/build/outputs/apk/mono/release/StS2Launcher-v*.apk
# fresh install (clears saved Steam credentials + cached assemblies):
adb shell pm clear com.game.sts2launcher.modmanager
```

## Troubleshooting

- **`./gradlew` fails finding `sts2.dll` / `MegaCrit.*` types** — run `setup-deps.sh` first;
  if you have a non-standard game install, set `STS2_GAME_DIR`.
- **`ERROR: dependencies not provisioned`** from build.sh — the BCL/AAR weren't harvested;
  run `setup-deps.sh`.
- **`SigningConfig "release" is missing required property storeFile`** — no keystore; re-run
  `setup-deps.sh` (generates one) or pass `-Pperform_signing=false` for an unsigned APK.
- **`[CXX1104] NDK ... disagrees`** — harmless warning; install `ndk;28.1.13356709` to silence.
- **JDK errors / Unsupported class version** — you're not on JDK 17; point `JAVA_HOME` at it.
