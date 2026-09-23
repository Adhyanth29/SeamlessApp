using System.Net.Sockets;
using SeamlessClip.Protocol;

namespace SeamlessClip.Net;

/// <summary>One authenticated phone connection.</summary>
internal sealed class ClientSession : IDisposable
{
    private readonly TcpClient _tcp;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private int _disposed;

    public ClientSession(TcpClient tcp, NetworkStream stream, SessionCipher cipher, string deviceName, string remoteEndPoint)
    {
        _tcp = tcp;
        Stream = stream;
        Cipher = cipher;
        DeviceName = deviceName;
        RemoteEndPoint = remoteEndPoint;
    }

    public Guid Id { get; } = Guid.NewGuid();
    public string DeviceName { get; }
    public string RemoteEndPoint { get; }
    public DateTimeOffset ConnectedAt { get; } = DateTimeOffset.Now;

    internal NetworkStream Stream { get; }
    internal SessionCipher Cipher { get; }

    public async Task SendAsync(WireMessage message, CancellationToken ct)
    {
        var plaintext = WireJson.Serialize(message);
        await _writeLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            // Seal inside the lock so counter order always matches write order.
            var frame = Cipher.Seal(plaintext);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(ProtocolConstants.WriteTimeout);
            await Framing.WriteFrameAsync(Stream, frame, timeout.Token).ConfigureAwait(false);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 1) return;
        _tcp.Dispose();
        Cipher.Dispose();
    }
}
