using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using SeamlessClip.Protocol;

namespace SeamlessClip.Net;

/// <summary>
/// TCP server the phone connects to. Performs the handshake from docs/PROTOCOL.md,
/// then relays clip messages in both directions. Events are raised on thread-pool threads.
/// </summary>
internal sealed class SyncServer : IDisposable
{
    private readonly int _port;
    private readonly string _serverId;
    private readonly Func<string> _pcName;
    private readonly Func<byte[]> _pairingKey;
    private readonly ConcurrentDictionary<Guid, ClientSession> _sessions = new();

    // Unauthenticated connections are cheap for an attacker on the LAN; cap them.
    private const int MaxPendingHandshakes = 16;
    private int _pendingHandshakes;
    private CancellationTokenSource? _cts;
    private TcpListener? _listener;

    public SyncServer(int port, string serverId, Func<string> pcName, Func<byte[]> pairingKey)
    {
        _port = port;
        _serverId = serverId;
        _pcName = pcName;
        _pairingKey = pairingKey;
    }

    /// <summary>A phone sent clipboard text: (session, text).</summary>
    public event Action<ClientSession, string>? ClipReceived;

    /// <summary>A phone connected or disconnected.</summary>
    public event Action? SessionsChanged;

    public IReadOnlyList<ClientSession> Sessions => _sessions.Values.OrderBy(s => s.ConnectedAt).ToList();

    /// <exception cref="SocketException">Port already in use, etc.</exception>
    public void Start()
    {
        _cts = new CancellationTokenSource();
        _listener = new TcpListener(IPAddress.Any, _port);
        _listener.Start();
        Log.Info($"Listening on TCP {_port}");
        _ = AcceptLoopAsync(_listener, _cts.Token);
    }

    /// <summary>Drops all phones, e.g. after the pairing key was rotated.</summary>
    public void DisconnectAll()
    {
        foreach (var session in _sessions.Values)
            session.Dispose();
    }

    public void BroadcastText(string text)
    {
        var message = new WireMessage
        {
            Type = MessageTypes.Clip,
            Id = Guid.NewGuid().ToString("N"),
            Mime = "text/plain",
            Text = text,
            Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Device = _pcName(),
        };

        foreach (var session in _sessions.Values)
            _ = SendOrDropAsync(session, message);
    }

    private static async Task SendOrDropAsync(ClientSession session, WireMessage message)
    {
        try
        {
            await session.SendAsync(message, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            Log.Warn($"Send to {session.DeviceName} failed, dropping connection: {ex.Message}");
            session.Dispose(); // the receive loop notices and cleans up
        }
    }

    private async Task AcceptLoopAsync(TcpListener listener, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException ex)
            {
                if (ct.IsCancellationRequested) break;
                Log.Warn($"Accept failed: {ex.Message}");
                await Task.Delay(500, CancellationToken.None).ConfigureAwait(false);
                continue;
            }

            if (Interlocked.Increment(ref _pendingHandshakes) > MaxPendingHandshakes)
            {
                Interlocked.Decrement(ref _pendingHandshakes);
                Log.Warn($"Too many pending handshakes, dropping {client.Client.RemoteEndPoint}");
                client.Dispose();
                continue;
            }

            _ = HandleClientAsync(client, ct);
        }
    }

    private async Task HandleClientAsync(TcpClient tcp, CancellationToken ct)
    {
        var remote = tcp.Client.RemoteEndPoint?.ToString() ?? "unknown";
        ClientSession? session = null;
        try
        {
            tcp.NoDelay = true;
            var stream = tcp.GetStream();

            SessionCipher cipher;
            string device;
            try
            {
                using var handshakeCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                handshakeCts.CancelAfter(ProtocolConstants.HandshakeTimeout);
                (cipher, device) = await HandshakeAsync(stream, handshakeCts.Token).ConfigureAwait(false);
            }
            finally
            {
                Interlocked.Decrement(ref _pendingHandshakes);
            }

            session = new ClientSession(tcp, stream, cipher, device, remote);
            await session.SendAsync(new WireMessage { Type = MessageTypes.AuthOk, Device = _pcName() }, ct).ConfigureAwait(false);
            _sessions[session.Id] = session;
            Log.Info($"Phone '{device}' connected from {remote}");
            SessionsChanged?.Invoke();

            await ReceiveLoopAsync(session, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            Log.Info($"Connection {remote} timed out or server stopping");
        }
        catch (CryptographicException)
        {
            Log.Warn($"Connection {remote} failed authentication (wrong or old pairing key?)");
        }
        catch (Exception ex) when (ex is IOException or SocketException or InvalidDataException
                                       or JsonException or FormatException or ObjectDisposedException)
        {
            Log.Info($"Connection {remote} closed: {ex.Message}");
        }
        catch (Exception ex)
        {
            Log.Error($"Unexpected error on connection {remote}", ex);
        }
        finally
        {
            if (session != null)
            {
                bool removed = _sessions.TryRemove(session.Id, out _);
                session.Dispose();
                if (removed)
                {
                    Log.Info($"Phone '{session.DeviceName}' disconnected");
                    SessionsChanged?.Invoke();
                }
            }
            else
            {
                tcp.Dispose();
            }
        }
    }

    private async Task<(SessionCipher Cipher, string Device)> HandshakeAsync(NetworkStream stream, CancellationToken ct)
    {
        var serverNonce = RandomNumberGenerator.GetBytes(ProtocolConstants.HandshakeNonceLength);
        var hello = new HelloMessage { ServerId = _serverId, Nonce = Convert.ToBase64String(serverNonce) };
        await Framing.WriteFrameAsync(stream, WireJson.Serialize(hello), ct).ConfigureAwait(false);

        var clientHello = WireJson.Deserialize<HelloMessage>(
            await Framing.ReadFrameAsync(stream, ProtocolConstants.MaxHandshakeFrame, ct).ConfigureAwait(false));
        if (clientHello.Proto != ProtocolConstants.ProtocolName || clientHello.V != ProtocolConstants.Version)
            throw new InvalidDataException($"Unsupported protocol {clientHello.Proto} v{clientHello.V}");

        var clientNonce = Convert.FromBase64String(clientHello.Nonce);
        if (clientNonce.Length != ProtocolConstants.HandshakeNonceLength)
            throw new InvalidDataException("Bad client nonce length");

        var key = _pairingKey();
        var cipher = new SessionCipher(key, serverNonce, clientNonce, isServer: true);
        CryptographicOperations.ZeroMemory(key);

        try
        {
            var authFrame = await Framing.ReadFrameAsync(stream, ProtocolConstants.MaxHandshakeFrame, ct).ConfigureAwait(false);
            var auth = WireJson.Deserialize<WireMessage>(cipher.Open(authFrame));
            if (auth.Type != MessageTypes.Auth)
                throw new InvalidDataException($"Expected auth, got '{auth.Type}'");

            return (cipher, SanitizeDeviceName(auth.Device));
        }
        catch
        {
            cipher.Dispose();
            throw;
        }
    }

    /// <summary>Device names end up in logs, menus and notifications: strip control characters and cap length.</summary>
    private static string SanitizeDeviceName(string? raw)
    {
        var cleaned = new string((raw ?? "").Where(c => !char.IsControl(c)).ToArray()).Trim();
        if (cleaned.Length == 0) return "Phone";
        return cleaned.Length > 64 ? cleaned[..64] : cleaned;
    }

    private async Task ReceiveLoopAsync(ClientSession session, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            byte[] frame;
            using (var idle = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                idle.CancelAfter(ProtocolConstants.IdleTimeout);
                try
                {
                    frame = await Framing.ReadFrameAsync(session.Stream, ProtocolConstants.MaxFrame, idle.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (!ct.IsCancellationRequested)
                {
                    throw new IOException("No traffic for 60 s, assuming the phone went away");
                }
            }

            var message = WireJson.Deserialize<WireMessage>(session.Cipher.Open(frame));
            switch (message.Type)
            {
                case MessageTypes.Ping:
                    await session.SendAsync(new WireMessage { Type = MessageTypes.Pong }, ct).ConfigureAwait(false);
                    break;
                case MessageTypes.Clip when !string.IsNullOrEmpty(message.Text):
                    ClipReceived?.Invoke(session, message.Text);
                    break;
                case MessageTypes.Pong:
                    break;
                default:
                    Log.Info($"Ignoring message type '{message.Type}' from {session.DeviceName}");
                    break;
            }
        }
    }

    public void Dispose()
    {
        _cts?.Cancel();
        _listener?.Stop();
        DisconnectAll();
        _cts?.Dispose();
    }
}
