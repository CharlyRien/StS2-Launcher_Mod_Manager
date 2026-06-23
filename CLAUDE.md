# CLAUDE.md

Guidance for AI agents (and humans) working in this repo. Keep it short; deep detail
lives in [README.md](README.md) (quickstart) and [BUILD_NOTES.md](BUILD_NOTES.md) (internals).

## What this is

An Android launcher / mod manager for **Slay the Spire 2**, built on a custom Godot 4.5.1
engine with .NET/Mono and Harmony runtime patching. It is a community fork of Ekyso/StS2-Launcher.
Two moving parts:
- **`src/STS2Mobile/`** — a .NET 9 patcher (`STS2Mobile.dll`) that Harmony-patches the game at
  runtime. This is the only code we compile. `Patches/` (one Harmony patch per concern),
  `Launcher/` (programmatic Godot UI, MVC), `Steam/` (SteamKit2 auth / depot download / cloud saves).
- **`android/`** — a Godot Android gradle project that packages the patcher + harvested binaries
  into the APK.

## Build & run

```bash
bash scripts/setup-deps.sh      # one-time: provision deps (auto), detect sts2.dll, make a keystore
bash scripts/build.sh           # publish patcher + assemble signed APK  (--no-bump to keep version)
```
Output: `android/build/outputs/apk/mono/release/StS2Launcher-v<version>.apk`.

**Environment**: `dotnet` (9) on PATH, `JAVA_HOME` → **JDK 17** (not newer — Gradle/AGP break),
Android **platform 35** + build-tools 35.0.0. Set these before invoking gradle/build.sh.

## Architecture facts an agent must know

- **The base APK is a binary parts donor, not a build-from-scratch.** `setup-deps.sh` harvests the
  custom Godot engine `.so`, native libs (FMOD/Spine/Mono), and the .NET BCL from Ekyso's APK; gradle
  builds a fresh APK around them. Don't try to rebuild those from source.
- **Compile references**: `GodotSharp` (4.5.1) and `Lib.Harmony` (2.4.2) are **compile-only NuGet
  `PackageReference`s** in `STS2Mobile.csproj` (`<IncludeAssets>compile</IncludeAssets>`) — runtime
  copies come from the harvested BCL. Only **`sts2.dll`** is a local `HintPath`: it's the proprietary
  game assembly, auto-detected from the user's own Steam install. Never commit it (gitignored via `upstream/`).
- **Game-version compatibility is the main breakage source.** The patches reference `MegaCrit.Sts2.*`
  game types directly, so a game update can break compilation. After a game update: re-run
  `setup-deps.sh` (re-copies the new `sts2.dll`) and re-build; if it fails to compile, the game renamed
  types the patches touch (see the README "Fork changes" history for how past breaks were fixed).

## Gotchas

- **Scripts target macOS bash 3.2** (`/usr/bin/env bash`). Avoid constructs that break there:
  unguarded empty-array expansion under `set -u` (use `${arr[@]+"${arr[@]}"}`), `sed -i` without a
  suffix (use `sed -i.bak … && rm -f ….bak`). Both `setup-deps.sh` and `build.sh` pass `bash -n`.
- **Steam auth flow** lives in `src/STS2Mobile/Steam/SteamAuth.cs` (+ `LauncherModel.cs`). The mobile
  device-confirmation path needs reconnect-and-resume polling (`PollUntilResultAsync`) because
  backgrounding the app during approval drops the CM connection.
- **Secrets / large binaries are gitignored**: `*.keystore`, `android/keystore.properties`, `upstream/`,
  `android/{libs,assets/dotnet_bcl,build}`, `*.apk`/`*.so`/`*.aar`. `setup-deps.sh` regenerates them.
  The one committed jar exception is `android/gradle/wrapper/gradle-wrapper.jar`.
- **FMOD audio**: only `fmod.jar` is gated (FMOD account). Without it the build still works via a
  no-audio stub. **Keystore**: auto-generated dev keystore; the APK is signed and installable as-is.

## Conventions

- Match surrounding code style (4-space C#, the existing patch-file-per-concern layout). The repo
  formats C# with CSharpier (`scripts/build.sh` runs it best-effort).
- Commit messages: imperative subject; the changelog narrative lives in `README.md` "Fork changes".
