using System.Diagnostics;
using System.Net.Sockets;
using System.Text;
using SeamlessClip.Net;
using SeamlessClip.Protocol;

namespace SeamlessClip;

/// <summary>The whole app: tray icon + clipboard monitor + sync server + discovery beacon.</summary>
internal sealed class TrayApplicationContext : ApplicationContext
{
    private readonly SynchronizationContext _ui;
    private readonly AppSettings _settings;
    private readonly NotifyIcon _tray;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ClipboardMonitor _monitor;
    private readonly System.Windows.Forms.Timer _debounce;
    private readonly SyncServer _server;
    private readonly DiscoveryBeacon _beacon;
    private PairingForm? _pairingForm;

    // Loop prevention: the last text we wrote because a phone sent it, and the last text we sent.
    private string? _lastAppliedFromPhone;
    private string? _lastSentToPhone;

    public TrayApplicationContext()
    {
        SynchronizationContext.SetSynchronizationContext(new WindowsFormsSynchronizationContext());
        _ui = SynchronizationContext.Current!;

        _settings = AppSettings.Load();
        _settings.Save();

        _statusItem = new ToolStripMenuItem("Starting…") { Enabled = false };
        _tray = new NotifyIcon
        {
            Icon = TrayIcons.Waiting,
            Text = "SeamlessClip",
            Visible = true,
            ContextMenuStrip = BuildMenu(),
        };
        _tray.DoubleClick += (_, _) => ShowPairing();

        _server = new SyncServer(_settings.Port, _settings.ServerId, () => _settings.PcName, _settings.GetPairingKey);
        _server.ClipReceived += (session, text) => _ui.Post(_ => OnClipFromPhone(session.DeviceName, text), null);
        _server.SessionsChanged += () => _ui.Post(_ => OnSessionsChanged(), null);
        try
        {
            _server.Start();
        }
        catch (SocketException ex)
        {
            Log.Error($"Could not listen on port {_settings.Port}", ex);
            MessageBox.Show(
                $"SeamlessClip could not listen on TCP port {_settings.Port}:\n{ex.Message}\n\n" +
                "Is another copy already running? You can change \"Port\" in settings.json (Open data folder).",
                "SeamlessClip", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }

        _beacon = new DiscoveryBeacon(() => new BeaconMessage
        {
            ServerId = _settings.ServerId,
            Name = _settings.PcName,
            Port = _settings.Port,
        });
        _beacon.Start();

        _debounce = new System.Windows.Forms.Timer { Interval = 150 };
        _debounce.Tick += (_, _) =>
        {
            _debounce.Stop();
            OnLocalClipboardChanged();
        };
        _monitor = new ClipboardMonitor();
        _monitor.ClipboardChanged += (_, _) =>
        {
            // Apps often fire several updates per copy; coalesce them.
            _debounce.Stop();
            _debounce.Start();
        };

        UpdateStatus();
        if (!_settings.HasPairedDevice)
            _ui.Post(_ => ShowPairing(), null);
    }

    private ContextMenuStrip BuildMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add(_statusItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Send clipboard to phone now", null, (_, _) => SendClipboardNow());
        menu.Items.Add("Pair a phone…", null, (_, _) => ShowPairing());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(Toggle("Automatically send PC copies to phone", () => _settings.AutoSendToPhone, v => _settings.AutoSendToPhone = v));
        menu.Items.Add(Toggle("Put phone copies on PC clipboard", () => _settings.ApplyFromPhone, v => _settings.ApplyFromPhone = v));
        menu.Items.Add(Toggle("Show notifications", () => _settings.ShowNotifications, v => _settings.ShowNotifications = v));

        var startup = new ToolStripMenuItem("Start with Windows") { CheckOnClick = true };
        startup.Checked = SafeIsStartupEnabled();
        startup.CheckedChanged += (_, _) =>
        {
            try { StartupRegistration.SetEnabled(startup.Checked); }
            catch (Exception ex) { Log.Error("Could not change startup registration", ex); }
        };
        menu.Items.Add(startup);

        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Allow through Windows Firewall (admin)…", null, (_, _) => AddFirewallRule());
        menu.Items.Add("Open data folder", null, (_, _) => OpenDataFolder());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => ExitThread());
        return menu;
    }

    private ToolStripMenuItem Toggle(string text, Func<bool> get, Action<bool> set)
    {
        var item = new ToolStripMenuItem(text) { CheckOnClick = true, Checked = get() };
        item.CheckedChanged += (_, _) =>
        {
            set(item.Checked);
            _settings.Save();
        };
        return item;
    }

    private static bool SafeIsStartupEnabled()
    {
        try { return StartupRegistration.IsEnabled; }
        catch { return false; }
    }

    // ---- Clipboard flow -------------------------------------------------

    private void OnLocalClipboardChanged()
    {
        if (!_settings.AutoSendToPhone || _server.Sessions.Count == 0) return;

        var text = ClipboardAccess.TryGetText();
        if (string.IsNullOrEmpty(text)) return;

        if (text == _lastAppliedFromPhone)
        {
            // This is the echo of us applying the phone's clip. Swallow it once.
            _lastAppliedFromPhone = null;
            _lastSentToPhone = text;
            return;
        }
        if (text == _lastSentToPhone) return;

        SendToPhones(text, manual: false);
    }

    private void SendClipboardNow()
    {
        var text = ClipboardAccess.TryGetText();
        if (string.IsNullOrEmpty(text))
        {
            Notify("Nothing to send", "The clipboard has no text (or the app that copied it marked it private).");
            return;
        }
        SendToPhones(text, manual: true);
    }

    private void SendToPhones(string text, bool manual)
    {
        if (Encoding.UTF8.GetByteCount(text) > ProtocolConstants.MaxTextBytes)
        {
            if (manual) Notify("Too large", "Clipboard text is over 4 MB and was not sent.");
            return;
        }
        if (_server.Sessions.Count == 0)
        {
            if (manual) Notify("No phone connected", "Open SeamlessClip on your phone and make sure it's on the same Wi-Fi.");
            return;
        }

        _lastSentToPhone = text;
        _server.BroadcastText(text);
        Log.Info($"Sent {text.Length} chars to {_server.Sessions.Count} phone(s)");
    }

    private void OnClipFromPhone(string device, string text)
    {
        Log.Info($"Received {text.Length} chars from {device}");
        if (!_settings.ApplyFromPhone) return;
        if (Encoding.UTF8.GetByteCount(text) > ProtocolConstants.MaxTextBytes) return;

        _lastAppliedFromPhone = text;
        if (ClipboardAccess.TrySetText(text))
            Notify($"Copied from {device}", Preview(text));
    }

    // ---- Status / UI ----------------------------------------------------

    private void OnSessionsChanged()
    {
        var sessions = _server.Sessions;
        if (sessions.Count > 0 && !_settings.HasPairedDevice)
        {
            _settings.HasPairedDevice = true;
            _settings.Save();
        }
        if (sessions.Count > 0 && _pairingForm is { IsDisposed: false })
        {
            _pairingForm.Close();
            Notify("Phone paired", $"{sessions[^1].DeviceName} is connected. Copy on one device, paste on the other.");
        }
        UpdateStatus();
    }

    private void UpdateStatus()
    {
        var sessions = _server.Sessions;
        string status = sessions.Count switch
        {
            0 => $"Waiting for phone (port {_settings.Port})",
            1 => $"Connected: {sessions[0].DeviceName}",
            _ => $"Connected: {string.Join(", ", sessions.Select(s => s.DeviceName))}",
        };
        _statusItem.Text = status;
        _tray.Icon = sessions.Count > 0 ? TrayIcons.Connected : TrayIcons.Waiting;
        var tip = $"SeamlessClip – {status}";
        _tray.Text = tip.Length > 63 ? tip[..63] : tip;
    }

    private void ShowPairing()
    {
        if (_pairingForm is { IsDisposed: false })
        {
            _pairingForm.Activate();
            return;
        }

        _pairingForm = new PairingForm(_settings, RotateKey);
        _pairingForm.FormClosed += (_, _) => _pairingForm = null;
        _pairingForm.Show();
        _pairingForm.Activate();
    }

    private void RotateKey()
    {
        _settings.RegenerateKey();
        _settings.Save();
        _server.DisconnectAll();
        Log.Info("Pairing key rotated");
    }

    private void Notify(string title, string body)
    {
        if (!_settings.ShowNotifications) return;
        _tray.ShowBalloonTip(2500, title, string.IsNullOrWhiteSpace(body) ? " " : body, ToolTipIcon.None);
    }

    private static string Preview(string text)
    {
        var singleLine = text.ReplaceLineEndings(" ").Trim();
        return singleLine.Length <= 120 ? singleLine : singleLine[..117] + "…";
    }

    private void AddFirewallRule()
    {
        // Program-scoped rule, only on Private/Domain networks and only from the local subnet,
        // so the port stays closed on public Wi-Fi.
        var exe = Environment.ProcessPath;
        var args = "advfirewall firewall add rule name=\"SeamlessClip\" dir=in action=allow " +
                   $"program=\"{exe}\" enable=yes profile=private,domain remoteip=localsubnet";
        try
        {
            using var process = Process.Start(new ProcessStartInfo("netsh", args)
            {
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            });
            process?.WaitForExit(15000);
            Notify("Firewall", process?.ExitCode == 0 ? "Rule added." : "netsh did not report success; see README for manual steps.");
        }
        catch (System.ComponentModel.Win32Exception)
        {
            // User cancelled the UAC prompt.
        }
    }

    private static void OpenDataFolder()
    {
        Directory.CreateDirectory(AppSettings.DataDirectory);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{AppSettings.DataDirectory}\"") { UseShellExecute = true });
    }

    protected override void ExitThreadCore()
    {
        _tray.Visible = false;
        _pairingForm?.Close();
        _monitor.Dispose();
        _debounce.Dispose();
        _beacon.Dispose();
        _server.Dispose();
        _tray.Dispose();
        base.ExitThreadCore();
    }
}
