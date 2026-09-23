using System.Net;
using System.Net.Sockets;
using SeamlessClip.Protocol;

namespace SeamlessClip.Net;

/// <summary>
/// Periodically broadcasts a small UDP beacon so the phone can find the PC again
/// after its IP address changes. Contains no secrets.
/// </summary>
internal sealed class DiscoveryBeacon : IDisposable
{
    private readonly Func<BeaconMessage> _messageFactory;
    private readonly CancellationTokenSource _cts = new();

    public DiscoveryBeacon(Func<BeaconMessage> messageFactory)
    {
        _messageFactory = messageFactory;
    }

    public void Start() => _ = RunAsync(_cts.Token);

    private async Task RunAsync(CancellationToken ct)
    {
        using var udp = new UdpClient(AddressFamily.InterNetwork) { EnableBroadcast = true };
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var payload = WireJson.Serialize(_messageFactory());
                var targets = NetworkInfo.GetLocalIPv4()
                    .Select(a => a.Broadcast)
                    .Append(IPAddress.Broadcast)
                    .Distinct();

                foreach (var target in targets)
                {
                    try
                    {
                        await udp.SendAsync(payload, new IPEndPoint(target, ProtocolConstants.BeaconPort), ct).ConfigureAwait(false);
                    }
                    catch (SocketException)
                    {
                        // Interface went away or broadcast not permitted on it; try the rest.
                    }
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                Log.Warn($"Beacon failed: {ex.Message}");
            }

            try
            {
                await Task.Delay(ProtocolConstants.BeaconInterval, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _cts.Dispose();
    }
}
