using System;

namespace STS2Mobile.Steam;

// Extracts the launcher-dialog snippet from a GitHub release body. Convention:
// anything between <!-- launcher-dialog --> and <!-- /launcher-dialog --> is
// shown in the in-launcher update prompt. Everything else stays on the GitHub
// releases page and is not surfaced — the dialog is for tap-sized highlights,
// not the full changelog.
//
// Pure function: no IO, no dependencies. Verifiable via the debug-intent path
// that injects a fake body string into the dialog.
public static class ReleaseNotes
{
    private const string OpenMarker = "<!-- launcher-dialog -->";
    private const string CloseMarker = "<!-- /launcher-dialog -->";
    private const int MaxLength = 500;

    // Returns the trimmed body between the markers, or null when:
    //   - input is null/empty
    //   - either marker is missing
    //   - the marker pair is empty/whitespace-only
    // Multiple marker pairs: only the first is honored (later pairs are
    // ignored, not concatenated).
    public static string ExtractDialogBody(string releaseBody)
    {
        if (string.IsNullOrEmpty(releaseBody))
            return null;

        int openIdx = releaseBody.IndexOf(OpenMarker, StringComparison.Ordinal);
        if (openIdx < 0)
            return null;
        int contentStart = openIdx + OpenMarker.Length;

        int closeIdx = releaseBody.IndexOf(CloseMarker, contentStart, StringComparison.Ordinal);
        if (closeIdx < 0)
            return null;

        var content = releaseBody.Substring(contentStart, closeIdx - contentStart).Trim();
        if (content.Length == 0)
            return null;

        if (content.Length > MaxLength)
            content = content.Substring(0, MaxLength).TrimEnd() + "…";
        return content;
    }
}
