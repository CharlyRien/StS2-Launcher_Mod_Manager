using System;
using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using Godot;
using STS2Mobile.Patches;

namespace STS2Mobile.Launcher;

// Stamps the downloaded game files with the branch + build identifiers they
// came from, so the next launch can detect cache/PCK mismatches that would
// otherwise corrupt card/relic image indices (see issue #5).
//
// Written by LauncherModel after a successful download. Read by
// LauncherController on PLAY to decide whether to prompt for cache rebuild.
// Cleared by WipeGameFiles so the next download writes a fresh stamp.
public class CacheStamp
{
    [JsonPropertyName("branch")]
    public string Branch { get; set; }

    [JsonPropertyName("buildId")]
    public string BuildId { get; set; }

    [JsonPropertyName("commit")]
    public string Commit { get; set; }

    [JsonPropertyName("version")]
    public string Version { get; set; }

    [JsonPropertyName("recordedAt")]
    public string RecordedAt { get; set; }

    private static string StampPath => Path.Combine(OS.GetDataDir(), ".cache_stamp");
    private static string SentinelPath =>
        Path.Combine(OS.GetDataDir(), ".cache_rebuild_sentinel");

    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        WriteIndented = false,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public static CacheStamp Read()
    {
        try
        {
            if (!File.Exists(StampPath))
                return null;
            var text = File.ReadAllText(StampPath);
            if (string.IsNullOrWhiteSpace(text))
                return null;
            return JsonSerializer.Deserialize<CacheStamp>(text, SerializerOptions);
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"[CacheStamp] Read failed: {ex.Message}");
            return null;
        }
    }

    public void Write()
    {
        try
        {
            RecordedAt = DateTime.UtcNow.ToString("o");
            var text = JsonSerializer.Serialize(this, SerializerOptions);
            // Write to .tmp first, then replace, so a process kill mid-write
            // can't leave a half-truncated stamp that fails to deserialize.
            var tmpPath = StampPath + ".tmp";
            File.WriteAllText(tmpPath, text);
            if (File.Exists(StampPath))
                File.Delete(StampPath);
            File.Move(tmpPath, StampPath);
            PatchHelper.Log(
                $"[CacheStamp] Wrote stamp: branch={Branch} buildId={BuildId} commit={Commit} version={Version}"
            );
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"[CacheStamp] Write failed: {ex.Message}");
        }
    }

    public static void Delete()
    {
        try
        {
            if (File.Exists(StampPath))
            {
                File.Delete(StampPath);
                PatchHelper.Log("[CacheStamp] Stamp deleted");
            }
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"[CacheStamp] Delete failed: {ex.Message}");
        }
    }

    // Branch + commit must match. BuildId is informational only — at PLAY time
    // we have no Steam connection to read it, so PLAY-time comparison falls back
    // to release_info.json (commit). A null `current` (no PCK / no
    // release_info.json yet) is treated as a match; the legacy prompt path
    // handles missing-stamp cases via IsLegacyState().
    public bool MatchesCurrent(CacheStamp current)
    {
        if (current == null)
            return true;
        return string.Equals(Branch, current.Branch, StringComparison.OrdinalIgnoreCase)
            && string.Equals(Commit, current.Commit, StringComparison.OrdinalIgnoreCase);
    }

    // Reads selected_branch + game/release_info.json to construct the "current"
    // stamp at PLAY time. Returns null if release_info.json is missing — in
    // that case the caller should not prompt (no PCK to compare against).
    public static CacheStamp BuildCurrent()
    {
        try
        {
            var dataDir = OS.GetDataDir();
            var releaseInfoPath = Path.Combine(dataDir, "game", "release_info.json");
            if (!File.Exists(releaseInfoPath))
                return null;

            string commit = "";
            string version = "";
            using (var doc = JsonDocument.Parse(File.ReadAllText(releaseInfoPath)))
            {
                var root = doc.RootElement;
                if (root.TryGetProperty("commit", out var c))
                    commit = c.GetString() ?? "";
                if (root.TryGetProperty("version", out var v))
                    version = v.GetString() ?? "";
            }

            var branchPath = Path.Combine(dataDir, "selected_branch");
            string branch = "public";
            if (File.Exists(branchPath))
            {
                var raw = File.ReadAllText(branchPath).Trim();
                if (!string.IsNullOrEmpty(raw))
                    branch = raw;
            }

            return new CacheStamp
            {
                Branch = branch,
                Commit = commit,
                Version = version,
            };
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"[CacheStamp] BuildCurrent failed: {ex.Message}");
            return null;
        }
    }

    // Sentinel: tells GodotApp.onCreate (Java) to wipe the .godot/ Godot cache
    // (preserving mono/) on the next boot. Created by WipeGameFiles or by the
    // mismatch dialog Yes-handler. Cleared by the Java handler after wiping.
    public static void RequestRebuild()
    {
        try
        {
            File.WriteAllText(SentinelPath, DateTime.UtcNow.ToString("o"));
            PatchHelper.Log("[CacheStamp] Cache rebuild sentinel written");
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"[CacheStamp] RequestRebuild failed: {ex.Message}");
        }
    }

    public static bool IsRebuildRequested() => File.Exists(SentinelPath);

    // Detects users who upgraded into v0.3.12 carrying a corrupted .godot cache
    // from earlier in-place branch switches: stamp absent but PCK + Godot
    // import cache both already exist.
    public static bool IsLegacyState()
    {
        try
        {
            var dataDir = OS.GetDataDir();
            var stampExists = File.Exists(Path.Combine(dataDir, ".cache_stamp"));
            var imported = Directory.Exists(Path.Combine(dataDir, ".godot", "imported"));
            var gamePck = File.Exists(Path.Combine(dataDir, "game", "SlayTheSpire2.pck"));
            return !stampExists && imported && gamePck;
        }
        catch
        {
            return false;
        }
    }
}
