using QRCoder;
using SeamlessClip.Protocol;

namespace SeamlessClip;

/// <summary>Shows the pairing QR code for the phone to scan.</summary>
internal sealed class PairingForm : Form
{
    private readonly AppSettings _settings;
    private readonly Action _rotateKey;
    private readonly PictureBox _qr;
    private readonly Label _details;
    private readonly TextBox _link;

    public PairingForm(AppSettings settings, Action rotateKey)
    {
        _settings = settings;
        _rotateKey = rotateKey;

        Text = "Pair a phone – SeamlessClip";
        Icon = TrayIcons.Waiting;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Padding = new Padding(16);

        var layout = new TableLayoutPanel
        {
            ColumnCount = 1,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Dock = DockStyle.Fill,
        };

        var instructions = new Label
        {
            AutoSize = true,
            MaximumSize = new Size(380, 0),
            Text = "1. Install SeamlessClip on your Pixel.\n" +
                   "2. Connect the phone and this PC to the same Wi-Fi.\n" +
                   "3. Scan this code with the Pixel camera, or tap \"Scan pairing QR\" in the app.\n\n" +
                   "Anyone who can see this code can pair with this PC, so keep it private.",
            Margin = new Padding(0, 0, 0, 12),
        };

        _qr = new PictureBox
        {
            Size = new Size(320, 320),
            SizeMode = PictureBoxSizeMode.Zoom,
            Anchor = AnchorStyles.None,
            BackColor = Color.White,
        };

        _details = new Label { AutoSize = true, MaximumSize = new Size(380, 0), Margin = new Padding(0, 12, 0, 6) };

        _link = new TextBox { ReadOnly = true, Width = 380 };

        var buttons = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.LeftToRight, Margin = new Padding(0, 12, 0, 0) };
        var copy = new Button { Text = "Copy link", AutoSize = true };
        copy.Click += (_, _) => ClipboardAccess.TrySetText(_link.Text);
        var rotate = new Button { Text = "New key…", AutoSize = true };
        rotate.Click += (_, _) => RotateKey();
        var close = new Button { Text = "Close", AutoSize = true, DialogResult = DialogResult.Cancel };
        close.Click += (_, _) => Close();
        buttons.Controls.AddRange([copy, rotate, close]);
        CancelButton = close;

        layout.Controls.Add(instructions);
        layout.Controls.Add(_qr);
        layout.Controls.Add(_details);
        layout.Controls.Add(_link);
        layout.Controls.Add(buttons);
        Controls.Add(layout);

        Render();
    }

    private void Render()
    {
        var hosts = NetworkInfo.GetLocalIPv4()
            .Select(a => a.Address.ToString())
            .Distinct()
            .Take(4)
            .ToList();

        var key = _settings.GetPairingKey();
        string uri;
        try
        {
            uri = PairingUri.Build(_settings.ServerId, _settings.PcName, hosts, _settings.Port, key);
        }
        finally
        {
            System.Security.Cryptography.CryptographicOperations.ZeroMemory(key);
        }

        using (var generator = new QRCodeGenerator())
        using (var data = generator.CreateQrCode(uri, QRCodeGenerator.ECCLevel.M))
        {
            var png = new PngByteQRCode(data).GetGraphic(10);
            using var stream = new MemoryStream(png);
            using var decoded = Image.FromStream(stream);
            var old = _qr.Image;
            _qr.Image = new Bitmap(decoded);
            old?.Dispose();
        }

        _details.Text = hosts.Count == 0
            ? "⚠ No network connection found. Connect to Wi-Fi and reopen this window."
            : $"PC name: {_settings.PcName}\nAddress: {string.Join(", ", hosts)}   Port: {_settings.Port}";
        _link.Text = uri;
    }

    private void RotateKey()
    {
        var answer = MessageBox.Show(this,
            "Create a new pairing key?\n\nPhones paired with the old key will be disconnected and must scan the new code.",
            "SeamlessClip", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning);
        if (answer != DialogResult.OK) return;

        _rotateKey();
        Render();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
            _qr.Image?.Dispose();
        base.Dispose(disposing);
    }
}
