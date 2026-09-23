using System.Drawing.Drawing2D;

namespace SeamlessClip;

/// <summary>Draws the tray icons at runtime so the repo needs no binary .ico assets.</summary>
internal static class TrayIcons
{
    public static Icon Connected { get; } = Create(Color.FromArgb(46, 160, 67));
    public static Icon Waiting { get; } = Create(Color.FromArgb(140, 140, 140));

    private static Icon Create(Color statusColor)
    {
        using var bitmap = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);

            // Two overlapping "documents" = copy/paste.
            using var back = new SolidBrush(Color.FromArgb(90, 120, 200));
            using var front = new SolidBrush(Color.FromArgb(66, 133, 244));
            using var outline = new Pen(Color.White, 1.5f);
            FillRounded(g, back, new RectangleF(3, 2, 17, 21), 3);
            FillRounded(g, front, new RectangleF(10, 8, 17, 21), 3);
            g.DrawLine(outline, 14, 15, 23, 15);
            g.DrawLine(outline, 14, 19, 23, 19);
            g.DrawLine(outline, 14, 23, 20, 23);

            // Status dot.
            using var dot = new SolidBrush(statusColor);
            using var ring = new Pen(Color.White, 1.5f);
            g.FillEllipse(dot, 19, 19, 12, 12);
            g.DrawEllipse(ring, 19, 19, 12, 12);
        }

        // GetHicon handles are intentionally never destroyed: two icons for the process lifetime.
        return Icon.FromHandle(bitmap.GetHicon());
    }

    private static void FillRounded(Graphics g, Brush brush, RectangleF rect, float radius)
    {
        using var path = new GraphicsPath();
        float d = radius * 2;
        path.AddArc(rect.X, rect.Y, d, d, 180, 90);
        path.AddArc(rect.Right - d, rect.Y, d, d, 270, 90);
        path.AddArc(rect.Right - d, rect.Bottom - d, d, d, 0, 90);
        path.AddArc(rect.X, rect.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        g.FillPath(brush, path);
    }
}
