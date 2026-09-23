using System.Runtime.InteropServices;

namespace SeamlessClip;

/// <summary>Clipboard helpers. Must be called on the STA UI thread.</summary>
internal static class ClipboardAccess
{
    // Formats that password managers and other privacy-aware apps set to say
    // "don't sync / don't record this". We honour them, and set them on secrets we copy.
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
                if (IsPrivate()) return null;
                // Only ever ask WinForms for the standard text format. Custom formats go through
                // raw Win32 reads (see ReadDword) because WinForms' GetData on arbitrary formats can
                // BinaryFormatter-deserialize data planted by another process.
                return Clipboard.ContainsText(TextDataFormat.UnicodeText)
                    ? Clipboard.GetText(TextDataFormat.UnicodeText)
                    : null;
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

    /// <summary>
    /// Copies a secret (e.g. the pairing link) while asking Windows not to keep it in
    /// clipboard history, not to sync it to the cloud clipboard, and asking monitors
    /// (including ourselves) not to forward it anywhere.
    /// </summary>
    public static bool TrySetSecretText(string text)
    {
        try
        {
            var data = new DataObject();
            data.SetData(DataFormats.UnicodeText, text);
            data.SetData(ExcludeFromMonitoringFormat, new MemoryStream(BitConverter.GetBytes(1)));
            data.SetData(CanIncludeInHistoryFormat, new MemoryStream(BitConverter.GetBytes(0)));
            data.SetData(CanUploadToCloudFormat, new MemoryStream(BitConverter.GetBytes(0)));
            Clipboard.SetDataObject(data, copy: true, retryTimes: 10, retryDelay: 100);
            return true;
        }
        catch (ExternalException ex)
        {
            Log.Warn($"Could not write clipboard: {ex.Message}");
            return false;
        }
    }

    private static bool IsPrivate()
    {
        if (IsFormatAvailable(ExcludeFromMonitoringFormat)) return true;
        if (ReadDword(CanIncludeInHistoryFormat) == 0) return true;
        if (ReadDword(CanUploadToCloudFormat) == 0) return true;
        return false;
    }

    private static bool IsFormatAvailable(string format) =>
        IsClipboardFormatAvailable(RegisterClipboardFormat(format));

    /// <summary>Reads a DWORD-valued clipboard format directly via Win32 (no .NET deserialization).</summary>
    private static int? ReadDword(string format)
    {
        uint id = RegisterClipboardFormat(format);
        if (id == 0 || !IsClipboardFormatAvailable(id)) return null;
        if (!OpenClipboard(IntPtr.Zero)) throw new ExternalException("Clipboard is busy");
        try
        {
            var handle = GetClipboardData(id);
            if (handle == IntPtr.Zero || (ulong)GlobalSize(handle) < 4) return null;
            var ptr = GlobalLock(handle);
            if (ptr == IntPtr.Zero) return null;
            try { return Marshal.ReadInt32(ptr); }
            finally { GlobalUnlock(handle); }
        }
        finally
        {
            CloseClipboard();
        }
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint RegisterClipboardFormat(string lpszFormat);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsClipboardFormatAvailable(uint format);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool OpenClipboard(IntPtr hWndNewOwner);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseClipboard();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr GetClipboardData(uint uFormat);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr GlobalLock(IntPtr hMem);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalUnlock(IntPtr hMem);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern UIntPtr GlobalSize(IntPtr hMem);
}
