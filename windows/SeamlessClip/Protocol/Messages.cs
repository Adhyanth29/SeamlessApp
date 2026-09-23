using System.Text.Json;
using System.Text.Json.Serialization;

namespace SeamlessClip.Protocol;

/// <summary>Constants shared with the Android client. See docs/PROTOCOL.md.</summary>
public static class ProtocolConstants
{
    public const string ProtocolName = "seamless-clip";
    public const int Version = 1;
    public const int DefaultPort = 45700;
    public const int BeaconPort = 45701;
    public const int HandshakeNonceLength = 16;
    public const int PairingKeyLength = 32;
    public const int MaxHandshakeFrame = 4 * 1024;
    public const int MaxFrame = 8 * 1024 * 1024;
    public const int MaxTextBytes = 4 * 1024 * 1024;

    public static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(10);
    public static readonly TimeSpan IdleTimeout = TimeSpan.FromSeconds(60);
    public static readonly TimeSpan WriteTimeout = TimeSpan.FromSeconds(15);
    public static readonly TimeSpan BeaconInterval = TimeSpan.FromSeconds(3);
}

public static class MessageTypes
{
    public const string Auth = "auth";
    public const string AuthOk = "auth_ok";
    public const string Clip = "clip";
    public const string Ping = "ping";
    public const string Pong = "pong";
}

/// <summary>Plaintext handshake frame sent by both sides before encryption starts.</summary>
public sealed class HelloMessage
{
    public string Proto { get; set; } = ProtocolConstants.ProtocolName;
    public int V { get; set; } = ProtocolConstants.Version;
    public string? ServerId { get; set; }
    public string? ClientId { get; set; }
    public string Nonce { get; set; } = "";
}

/// <summary>Encrypted application message. Unused fields are omitted on the wire.</summary>
public sealed class WireMessage
{
    public string Type { get; set; } = "";
    public string? Id { get; set; }
    public string? Device { get; set; }
    public string? Mime { get; set; }
    public string? Text { get; set; }
    public long? Ts { get; set; }
}

/// <summary>UDP discovery beacon. Unauthenticated; only used to learn the PC's current IP.</summary>
public sealed class BeaconMessage
{
    public string Proto { get; set; } = ProtocolConstants.ProtocolName;
    public int V { get; set; } = ProtocolConstants.Version;
    public string ServerId { get; set; } = "";
    public string Name { get; set; } = "";
    public int Port { get; set; }
}

public static class WireJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public static byte[] Serialize<T>(T value) => JsonSerializer.SerializeToUtf8Bytes(value, Options);

    public static T Deserialize<T>(byte[] data) =>
        JsonSerializer.Deserialize<T>(data, Options) ?? throw new InvalidDataException("Empty JSON message");
}
