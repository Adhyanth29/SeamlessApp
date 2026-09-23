using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace SeamlessClip.Protocol;

/// <summary>
/// Per-connection AES-256-GCM with counter nonces. The session key is derived with
/// HKDF-SHA256 from the pairing key and both handshake nonces. See docs/PROTOCOL.md.
/// Not thread-safe: callers serialize <see cref="Seal"/> (write lock) and <see cref="Open"/> (single reader).
/// </summary>
public sealed class SessionCipher : IDisposable
{
    public const string HkdfInfo = "seamless-clip v1 session";
    public const int TagLength = 16;
    private const int NonceLength = 12;
    private const uint ServerToClient = 1;
    private const uint ClientToServer = 2;

    private readonly AesGcm _aes;
    private readonly uint _sendDirection;
    private readonly uint _receiveDirection;
    private ulong _sendCounter;
    private ulong _receiveCounter;

    public SessionCipher(byte[] pairingKey, byte[] serverNonce, byte[] clientNonce, bool isServer)
    {
        byte[] salt = [.. serverNonce, .. clientNonce];
        byte[] key = HKDF.DeriveKey(HashAlgorithmName.SHA256, pairingKey, 32, salt, Encoding.ASCII.GetBytes(HkdfInfo));
        _aes = new AesGcm(key, TagLength);
        CryptographicOperations.ZeroMemory(key);

        _sendDirection = isServer ? ServerToClient : ClientToServer;
        _receiveDirection = isServer ? ClientToServer : ServerToClient;
    }

    public byte[] Seal(ReadOnlySpan<byte> plaintext)
    {
        Span<byte> nonce = stackalloc byte[NonceLength];
        BuildNonce(nonce, _sendDirection, _sendCounter++);

        var output = new byte[plaintext.Length + TagLength];
        _aes.Encrypt(nonce, plaintext, output.AsSpan(0, plaintext.Length), output.AsSpan(plaintext.Length));
        return output;
    }

    /// <exception cref="CryptographicException">The frame was not produced with this session key/counter.</exception>
    public byte[] Open(ReadOnlySpan<byte> frame)
    {
        if (frame.Length < TagLength)
            throw new CryptographicException("Encrypted frame is shorter than the GCM tag");

        Span<byte> nonce = stackalloc byte[NonceLength];
        BuildNonce(nonce, _receiveDirection, _receiveCounter);

        var plaintext = new byte[frame.Length - TagLength];
        _aes.Decrypt(nonce, frame[..^TagLength], frame[^TagLength..], plaintext);
        _receiveCounter++;
        return plaintext;
    }

    private static void BuildNonce(Span<byte> nonce, uint direction, ulong counter)
    {
        BinaryPrimitives.WriteUInt32BigEndian(nonce, direction);
        BinaryPrimitives.WriteUInt64BigEndian(nonce[4..], counter);
    }

    public void Dispose() => _aes.Dispose();
}
