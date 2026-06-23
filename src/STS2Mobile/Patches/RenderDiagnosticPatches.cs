using System;
using Godot;
using HarmonyLib;

namespace STS2Mobile.Patches;

// Issue #11 — On Fold6 unfolded fullscreen, horizontal tear stripes appear over the black
// background, but this isn't reproducible on the dev-side device (Fold7). One-shot diagnostic
// patch to capture the reporter's device surface/swapchain/display metadata via logcat.
//
// 1) Once right after boot: device GPU/renderer/display info
// 2) On every Window.SizeChanged: viewport / content scale / ratio
//
// Both are low-frequency events (once on boot + when the user folds/unfolds/rotates/enters split
// view), and they only run outside the main loop, so there's no impact on game FPS.
public static class RenderDiagnosticPatches
{
    private const string Tag = "[Diag/Fold]";
    private static bool _bootLogged;
    private static bool _sizeHookConnected;

    public static void Apply(Harmony _)
    {
        // The SceneTree may not have been created yet, so defer the diagnostic.
        // Same self-retry pattern as ModEntry.ScheduleStandaloneLauncher.
        Callable.From(RunWhenReady).CallDeferred();
    }

    private static void RunWhenReady()
    {
        if (Engine.GetMainLoop() is not SceneTree tree)
        {
            Callable.From(RunWhenReady).CallDeferred();
            return;
        }

        try
        {
            LogBootDiagnostic();
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} boot diag failed: {ex.Message}");
        }

        try
        {
            var window = tree.Root;
            if (window != null && !_sizeHookConnected)
            {
                window.SizeChanged += OnWindowSizeChanged;
                _sizeHookConnected = true;
                LogSize("initial");
            }
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} size-hook failed: {ex.Message}");
        }
    }

    private static void LogBootDiagnostic()
    {
        if (_bootLogged)
            return;
        _bootLogged = true;

        // OS / device
        PatchHelper.Log(
            $"{Tag} OS={OS.GetName()} model={OS.GetModelName()} "
                + $"distro={OS.GetDistributionName()} version={OS.GetVersion()}"
        );

        // GPU / renderer
        try
        {
            PatchHelper.Log(
                $"{Tag} adapter={RenderingServer.GetVideoAdapterName()} "
                    + $"vendor={RenderingServer.GetVideoAdapterVendor()} "
                    + $"type={RenderingServer.GetVideoAdapterType()} "
                    + $"api={RenderingServer.GetVideoAdapterApiVersion()}"
            );
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} adapter info unavailable: {ex.Message}");
        }

        // Rendering backend (Forward+ / Mobile / Compatibility)
        try
        {
            var method = ProjectSettings.GetSetting("rendering/renderer/rendering_method");
            var methodMobile = ProjectSettings.GetSetting(
                "rendering/renderer/rendering_method.mobile"
            );
            var driver = ProjectSettings.GetSetting("rendering/rendering_device/driver");
            PatchHelper.Log(
                $"{Tag} rendering_method={method} mobile={methodMobile} driver={driver}"
            );
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} project rendering settings unavailable: {ex.Message}");
        }

        // Display / DPI
        try
        {
            var screen = DisplayServer.WindowGetCurrentScreen();
            var screenSize = DisplayServer.ScreenGetSize(screen);
            var dpi = DisplayServer.ScreenGetDpi(screen);
            var refresh = DisplayServer.ScreenGetRefreshRate(screen);
            PatchHelper.Log(
                $"{Tag} screen[{screen}] size={screenSize.X}x{screenSize.Y} dpi={dpi} "
                    + $"refresh={refresh:F1}Hz"
            );
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} screen info unavailable: {ex.Message}");
        }
    }

    private static void OnWindowSizeChanged() => LogSize("changed");

    private static void LogSize(string reason)
    {
        try
        {
            if (Engine.GetMainLoop() is not SceneTree tree)
                return;
            var window = tree.Root;
            if (window == null)
                return;

            var winSize = DisplayServer.WindowGetSize();
            var visible = window.GetVisibleRect().Size;
            var contentScale = window.ContentScaleSize;
            float ratio = winSize.Y > 0 ? (float)winSize.X / winSize.Y : 0f;

            PatchHelper.Log(
                $"{Tag} size({reason}) win={winSize.X}x{winSize.Y} "
                    + $"visible={visible.X}x{visible.Y} "
                    + $"contentScale={contentScale.X}x{contentScale.Y} "
                    + $"mode={window.ContentScaleMode} aspect={window.ContentScaleAspect} "
                    + $"ratio={ratio:F4}"
            );
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} size log failed: {ex.Message}");
        }
    }
}
