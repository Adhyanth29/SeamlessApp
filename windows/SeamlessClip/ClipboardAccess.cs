using System.Runtime.InteropServices;

namespace SeamlessClip;

/// <summary>Clipboard helpers. Must be called on the STA UI thread.</summary>
internal static class ClipboardAccess
{
    // Formats that password managers and other privacy-aware apps set to say
    // "don't sync / don't record this". We honour them.
    private const string ExcludeFromMonitoringFormat = "ExcludeClipboardContentFromMonitorProcessing";
    private const string CanIncludeInHistoryFormat = "CanIncludeInClipboardHistory";
    private const string CanUploadToCloudFormat = "CanUploadToCloudClipboard";

    /// <summary>Returns clipboard text, or null if there is none or the source asked not to be synced.</summary>
    public static string? TryGetText()
    {
        for (int attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                var data = Clipboard.GetDataObject();
                if (data is null || IsPrivate(data)) return null;
                if (!data.GetDataPresent(DataFormats.UnicodeText)) return null;
                return data.GetData(DataFormats.UnicodeText) as string;
            }
            catch (ExternalException)
            {
                // Another process has the clipboard open; retry shortly.
                Thread.Sleep(40);
            }
        }

        Log.Warn("Clipboard stayed locked, giving up on this change");
        return null;
    }

    public static bool TrySetText(string text)
    {
        try
        {
            Clipboard.SetDataObject(text, copy: true, retryTimes: 10, retryDelay: 100);
            return true;
        }
        catch (ExternalException ex)
        {
            Log.Warn($"Could not write clipboard: {ex.Message}");
            return false;
        }
    }

    private static bool IsPrivate(IDataObject data)
    {
        try
        {
            if (data.GetDataPresent(ExcludeFromMonitoringFormat)) return true;
            if (ReadDword(data, CanIncludeInHistoryFormat) == 0) return true;
            if (ReadDword(data, CanUploadToCloudFormat) == 0) return true;
        }
        catch (Exception ex)
        {
            Log.Warn($"Could not inspect clipboard privacy formats: {ex.Message}");
        }
        return false;
    }

    private static int? ReadDword(IDataObject data, string format)
    {
        if (!data.GetDataPresent(format)) return null;
        return data.GetData(format) is MemoryStream ms && ms.Length >= 4
            ? BitConverter.ToInt32(ms.ToArray(), 0)
            : null;
    }
}
