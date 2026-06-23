using System;
using System.Threading;
using System.Threading.Tasks;
using SteamKit2;
using SteamKit2.Authentication;

namespace STS2Mobile.Steam;

public record AuthResult(string AccountName, string RefreshToken, string GuardData);

// Handles one-time interactive Steam login (password + 2FA). Creates a temporary
// SteamClient for the auth flow, returns credentials, then disposes. Does NOT
// call SteamUser.LogOn — callers use the returned refresh token with SteamConnection.
public class SteamAuth : IDisposable
{
    private readonly SteamClient _client;
    private readonly CallbackManager _callbackManager;
    private readonly SteamUser _steamUser;
    private Thread _callbackThread;
    private volatile bool _callbackRunning;

    private readonly ManualResetEventSlim _connectedGate = new(false);
    private bool _disposed;

    // Set by the caller before LoginWithCredentialsAsync. The bool indicates
    // whether the previous code was incorrect.
    public Func<bool, Task<string>> CodeProvider { get; set; }

    // Set when the WebSocket drops during 2FA wait (e.g. user backgrounds app).
    internal volatile bool NeedsReconnectForAuth;

    // Set once the device (mobile app) confirmation flow is entered. That flow
    // has no code submission to hang the reconnect off of, so a drop during the
    // poll wait must be recovered transparently inside the poll loop instead.
    internal volatile bool UsedDeviceConfirmation;

    public event Action<string> LogMessage;

    public SteamAuth()
    {
        var config = SteamConfiguration.Create(b => b.WithProtocolTypes(ProtocolTypes.WebSocket));
        _client = new SteamClient(config);
        _callbackManager = new CallbackManager(_client);
        _steamUser = _client.GetHandler<SteamUser>();

        _callbackManager.Subscribe<SteamClient.ConnectedCallback>(_ =>
        {
            Log("Connected to Steam");
            _connectedGate.Set();
        });

        _callbackManager.Subscribe<SteamClient.DisconnectedCallback>(cb =>
        {
            if (!cb.UserInitiated)
            {
                NeedsReconnectForAuth = true;
                Log("Connection lost during authentication — will reconnect on code submit");
            }
        });
    }

    public void Connect()
    {
        _connectedGate.Reset();
        StartCallbackThread();
        _client.Connect();
        Log("Connecting to Steam...");
    }

    public async Task<bool> WaitForConnectAsync(int timeoutMs = 10_000)
    {
        for (int i = 0; i < timeoutMs / 100; i++)
        {
            if (_connectedGate.IsSet)
                return true;
            await Task.Delay(100);
        }
        return _connectedGate.IsSet;
    }

    public async Task<AuthResult> LoginWithCredentialsAsync(
        string username,
        string password,
        string guardData
    )
    {
        if (!_connectedGate.IsSet)
        {
            Connect();
            if (!await WaitForConnectAsync())
                throw new TimeoutException(
                    "Could not connect to Steam. Check your internet connection."
                );
        }

        Log($"Authenticating as '{username}'...");

        var authSession = await _client.Authentication.BeginAuthSessionViaCredentialsAsync(
            new AuthSessionDetails
            {
                Username = username,
                Password = password,
                IsPersistentSession = true,
                GuardData = guardData,
                Authenticator = new AuthAuthenticator(this),
            }
        );

        var pollResponse = await PollUntilResultAsync(authSession);

        string newGuardData = pollResponse.NewGuardData ?? guardData;

        Log($"Authentication successful for '{pollResponse.AccountName}'");

        return new AuthResult(pollResponse.AccountName, pollResponse.RefreshToken, newGuardData);
    }

    // Drives SteamKit2's poll loop, but survives the WebSocket drop that is
    // effectively guaranteed during mobile-app (device) confirmation: approving
    // the request means switching to the Steam app, which backgrounds the
    // launcher and kills its (still unauthenticated, heartbeat-less) CM
    // connection. SteamKit2 then fails the in-flight PollAuthSessionStatus job
    // with AsyncJobFailedException and PollingWaitForResultAsync propagates it.
    //
    // Unlike the code-entry flow, device confirmation never invokes CodeProvider,
    // so there is no point to hang a reconnect off of. Instead we reconnect here
    // and resume polling the SAME auth session — its ClientID/RequestID stay valid
    // server-side, and PollAuthSessionStatusAsync rebuilds its request from them on
    // every call, so re-entering PollingWaitForResultAsync simply continues the
    // pending login until the user's approval is detected.
    private async Task<AuthPollResult> PollUntilResultAsync(AuthSession authSession)
    {
        const int maxReconnects = 12;
        int reconnects = 0;

        while (true)
        {
            try
            {
                return await authSession.PollingWaitForResultAsync();
            }
            catch (Exception ex)
                when (UsedDeviceConfirmation
                    && (ex is AsyncJobFailedException || NeedsReconnectForAuth))
            {
                if (++reconnects > maxReconnects)
                {
                    Log(
                        $"Gave up resuming mobile confirmation after {maxReconnects} reconnect attempts"
                    );
                    throw;
                }

                Log(
                    "Connection dropped while waiting for mobile confirmation — reconnecting and resuming..."
                );
                PatchHelper.Log(
                    $"[Auth] Resuming poll after disconnect ({ex.GetType().Name}), attempt {reconnects}"
                );
                await ReconnectForAuthAsync();
            }
        }
    }

    internal async Task ReconnectForAuthAsync()
    {
        Log("Reconnecting to Steam...");
        NeedsReconnectForAuth = false;
        _connectedGate.Reset();
        _client.Connect();

        if (!await WaitForConnectAsync())
            Log("Reconnect timed out — auth code submission may fail");
    }

    public void Dispose()
    {
        if (_disposed)
            return;
        _disposed = true;
        _callbackRunning = false;
        try
        {
            _client?.Disconnect();
        }
        catch { }
        _callbackThread?.Join(2000);
        _connectedGate.Dispose();
    }

    private void StartCallbackThread()
    {
        if (_callbackThread != null && _callbackThread.IsAlive)
            return;

        _callbackRunning = true;
        _callbackThread = new Thread(() =>
        {
            while (_callbackRunning)
                _callbackManager.RunWaitCallbacks(TimeSpan.FromSeconds(1));
        })
        {
            IsBackground = true,
            Name = "SteamAuthCallbacks",
        };
        _callbackThread.Start();
    }

    private void Log(string msg)
    {
        PatchHelper.Log($"[Auth] {msg}");
        LogMessage?.Invoke(msg);
    }

    private class AuthAuthenticator : IAuthenticator
    {
        private readonly SteamAuth _auth;

        public AuthAuthenticator(SteamAuth auth) => _auth = auth;

        public async Task<string> GetDeviceCodeAsync(bool previousCodeWasIncorrect)
        {
            _auth.Log(
                previousCodeWasIncorrect
                    ? "Previous 2FA code was incorrect, requesting new code"
                    : "Steam Guard 2FA code required"
            );

            if (_auth.CodeProvider == null)
                throw new AuthenticationException("No code provider configured");

            return await _auth.CodeProvider(previousCodeWasIncorrect);
        }

        public async Task<string> GetEmailCodeAsync(string email, bool previousCodeWasIncorrect)
        {
            _auth.Log(
                previousCodeWasIncorrect
                    ? "Previous email code was incorrect, requesting new code"
                    : $"Steam Guard email code sent to {email}"
            );

            if (_auth.CodeProvider == null)
                throw new AuthenticationException("No code provider configured");

            return await _auth.CodeProvider(previousCodeWasIncorrect);
        }

        public Task<bool> AcceptDeviceConfirmationAsync()
        {
            _auth.UsedDeviceConfirmation = true;
            _auth.Log("Waiting for Steam mobile app confirmation...");
            return Task.FromResult(true);
        }
    }
}
