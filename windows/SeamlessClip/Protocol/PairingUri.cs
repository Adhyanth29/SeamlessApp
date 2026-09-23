namespace SeamlessClip.Protocol;

/// <summary>Builds the <c>seamlessclip://pair?...</c> link encoded in the pairing QR code.</summary>
public static class PairingUri
{
    public static string Build(string serverId, string pcName, IEnumerable<string> hosts, int port, byte[] key)
    {
        return "seamlessclip://pair" +
               $"?v={ProtocolConstants.Version}" +
               $"&id={Uri.EscapeDataString(serverId)}" +
               $"&n={Uri.EscapeDataString(pcName)}" +
               $"&h={Uri.EscapeDataString(string.Join(',', hosts))}" +
               $"&p={port}" +
               $"&k={ToBase64Url(key)}";
    }

    private static string ToBase64Url(byte[] data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
