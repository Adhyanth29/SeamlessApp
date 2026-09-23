using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using SeamlessClip.Protocol;

namespace SeamlessClip;

/// <summary>Persisted in %APPDATA%\SeamlessClip\settings.json. The pairing key is DPAPI-protected (current user).</summary>
internal sealed class AppSettings
{
    private static readonly byte[] DpapiEntropy = Encoding.UTF8.GetBytes("SeamlessClip pairing key v1");
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    public static string DataDirectory { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SeamlessClip");

    private static string FilePath => Path.Combine(DataDirectory, "settings.json");

    public string ServerId { get; set; } = "";
    public string PcName { get; set; } = Environment.MachineName;
    public int Port { get; set; } = ProtocolConstants.DefaultPort;
    public string? ProtectedPairingKey { get; set; }

    /// <summary>Send PC clipboard changes to connected phones automatically.</summary>
    public bool AutoSendToPhone { get; set; } = true;

    /// <summary>Write text received from the phone into the Windows clipboard.</summary>
    public bool ApplyFromPhone { get; set; } = true;

    public bool ShowNotifications { get; set; } = true;

    /// <summary>Set once a phone has authenticated with the current key; controls first-run pairing prompt.</summary>
    public bool HasPairedDevice { get; set; }

    public static AppSettings Load()
    {
        AppSettings? settings = null;
        try
        {
            if (File.Exists(FilePath))
                settings = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath), JsonOptions);
        }
        catch (Exception ex)
        {
            Log.Error("Failed to read settings, starting fresh", ex);
        }

        settings ??= new AppSettings();
        settings.EnsureInitialized();
        return settings;
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(DataDirectory);
            var tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(this, JsonOptions));
            File.Move(tmp, FilePath, overwrite: true);
        }
        catch (Exception ex)
        {
            Log.Error("Failed to save settings", ex);
        }
    }

    private void EnsureInitialized()
    {
        if (string.IsNullOrWhiteSpace(ServerId))
            ServerId = Convert.ToHexString(RandomNumberGenerator.GetBytes(8)).ToLowerInvariant();
        if (string.IsNullOrWhiteSpace(PcName))
            PcName = Environment.MachineName;
        if (Port is <= 0 or > 65535)
            Port = ProtocolConstants.DefaultPort;

        bool keyValid;
        try
        {
            keyValid = ProtectedPairingKey != null && GetPairingKey().Length == ProtocolConstants.PairingKeyLength;
        }
        catch (CryptographicException)
        {
            keyValid = false; // e.g. settings copied from another Windows user
        }
        if (!keyValid)
            RegenerateKey();
    }

    public byte[] GetPairingKey() =>
        ProtectedData.Unprotect(Convert.FromBase64String(ProtectedPairingKey!), DpapiEntropy, DataProtectionScope.CurrentUser);

    /// <summary>Creates a new pairing key. Previously paired phones will no longer be able to connect.</summary>
    public void RegenerateKey()
    {
        var key = RandomNumberGenerator.GetBytes(ProtocolConstants.PairingKeyLength);
        ProtectedPairingKey = Convert.ToBase64String(ProtectedData.Protect(key, DpapiEntropy, DataProtectionScope.CurrentUser));
        CryptographicOperations.ZeroMemory(key);
        HasPairedDevice = false;
    }
}
