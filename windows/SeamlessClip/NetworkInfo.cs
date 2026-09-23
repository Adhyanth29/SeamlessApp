using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace SeamlessClip;

internal static class NetworkInfo
{
    public sealed record LocalAddress(IPAddress Address, IPAddress Broadcast, string InterfaceName, bool LikelyVirtual, bool HasGateway);

    private static readonly string[] VirtualHints =
        ["virtual", "hyper-v", "vethernet", "vmware", "virtualbox", "wsl", "docker", "loopback", "vpn", "tap-", "wintun", "zerotier", "tailscale"];

    /// <summary>
    /// IPv4 addresses the phone could reach us on, best guess first
    /// (physical adapters with a default gateway, then everything else).
    /// </summary>
    public static IReadOnlyList<LocalAddress> GetLocalIPv4()
    {
        var result = new List<LocalAddress>();
        NetworkInterface[] nics;
        try
        {
            nics = NetworkInterface.GetAllNetworkInterfaces();
        }
        catch (NetworkInformationException ex)
        {
            Log.Error("Could not enumerate network interfaces", ex);
            return result;
        }

        foreach (var nic in nics)
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;

            IPInterfaceProperties props;
            try { props = nic.GetIPProperties(); }
            catch (NetworkInformationException) { continue; }

            bool hasGateway = props.GatewayAddresses.Any(g =>
                g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
            bool likelyVirtual = LooksVirtual(nic.Description) || LooksVirtual(nic.Name);

            foreach (var unicast in props.UnicastAddresses)
            {
                var address = unicast.Address;
                if (address.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(address)) continue;

                var bytes = address.GetAddressBytes();
                if (bytes[0] == 169 && bytes[1] == 254) continue; // APIPA / link-local

                var mask = unicast.IPv4Mask?.GetAddressBytes() ?? [255, 255, 255, 0];
                var broadcast = new byte[4];
                for (int i = 0; i < 4; i++)
                    broadcast[i] = (byte)(bytes[i] | ~mask[i]);

                result.Add(new LocalAddress(address, new IPAddress(broadcast), nic.Name, likelyVirtual, hasGateway));
            }
        }

        return result
            .OrderBy(a => a.LikelyVirtual)
            .ThenByDescending(a => a.HasGateway)
            .ToList();
    }

    private static bool LooksVirtual(string text)
    {
        var lower = text.ToLowerInvariant();
        return VirtualHints.Any(hint => lower.Contains(hint));
    }
}
