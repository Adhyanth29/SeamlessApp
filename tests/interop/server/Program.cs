// Interop harness: runs the real SyncServer and scripts one exchange with the Kotlin client.
//
//   InteropServer <port> <keyHex> <serverId> <mode>
//     mode "roundtrip": expect phone clip, answer with PC clip, expect "ack:" + PC clip, exit 0.
//     mode "reject":    expect NO authenticated session within 8 s (client has wrong key), exit 0.
//     mode "abuse":     self-test: garbage/oversized/half-open connections must not break the server.
using System.Buffers.Binary;
using System.Net.Sockets;
using SeamlessClip.Net;

var port = int.Parse(args[0]);
var key = Convert.FromHexString(args[1]);
var serverId = args[2];
var mode = args[3];

const string PcClip = "hello from PC ✓ 🎉\r\nsecond line \"quoted\" <tag> \\ / \u0001";

using var server = new SyncServer(port, serverId, () => "InteropPC", () => (byte[])key.Clone());
var phoneClip = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
var ack = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
var connected = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);

server.SessionsChanged += () =>
{
    var s = server.Sessions;
    if (s.Count > 0) connected.TrySetResult(s[0].DeviceName);
};
server.ClipReceived += (session, text) =>
{
    Console.WriteLine($"[server] clip from {session.DeviceName}: {text.Length} chars");
    if (text.StartsWith("ack:")) ack.TrySetResult(text[4..]);
    else phoneClip.TrySetResult(text);
};
server.Start();
Console.WriteLine("READY");

static void Fail(string why) { Console.WriteLine($"[server] FAIL: {why}"); Environment.Exit(1); }

async Task<T> Within<T>(Task<T> task, int seconds, string what)
{
    if (await Task.WhenAny(task, Task.Delay(TimeSpan.FromSeconds(seconds))) != task) Fail($"timeout waiting for {what}");
    return await task;
}

switch (mode)
{
    case "roundtrip":
    {
        var device = await Within(connected.Task, 30, "phone to connect");
        Console.WriteLine($"[server] phone connected: {device}");
        var fromPhone = await Within(phoneClip.Task, 15, "phone clip");
        if (fromPhone != "hello from phone ✓ 🎉\nline2") Fail($"phone clip mismatch: {fromPhone}");
        server.BroadcastText(PcClip);
        var acked = await Within(ack.Task, 15, "phone ack");
        if (acked != PcClip) Fail("ack mismatch: PC clip was altered in transit");
        Console.WriteLine("[server] PASS roundtrip");
        break;
    }
    case "reject":
    {
        await Task.Delay(TimeSpan.FromSeconds(8));
        if (connected.Task.IsCompleted) Fail("phone with WRONG key was accepted");
        Console.WriteLine("[server] PASS reject");
        break;
    }
    case "abuse":
    {
        // 1. Oversized pre-auth frame length.
        await Poke(Frame(int.MaxValue));
        // 2. Garbage JSON hello.
        await Poke([.. Frame(5), .. "junk!"u8.ToArray()]);
        // 3. Valid-looking hello, then garbage "auth" frame.
        var hello = """{"proto":"seamless-clip","v":1,"clientId":"x","nonce":"AAAAAAAAAAAAAAAAAAAAAA=="}"""u8.ToArray();
        await Poke([.. Frame(hello.Length), .. hello, .. Frame(20), .. new byte[20]]);
        // 4. Many idle half-open connections.
        var idle = new List<TcpClient>();
        for (int i = 0; i < 50; i++) { var c = new TcpClient(); await c.ConnectAsync("127.0.0.1", port); idle.Add(c); }
        await Task.Delay(500);
        foreach (var c in idle) c.Dispose();
        if (server.Sessions.Count != 0) Fail("abuse produced an authenticated session");
        Console.WriteLine("[server] abuse connections handled; now expecting a legit phone");
        var device = await Within(connected.Task, 30, "legit phone after abuse");
        Console.WriteLine($"[server] PASS abuse (then {device} connected fine)");
        break;
    }
    default:
        Fail($"unknown mode {mode}");
        break;
}
return 0;

static byte[] Frame(int length)
{
    var b = new byte[4];
    BinaryPrimitives.WriteInt32BigEndian(b, length);
    return b;
}

async Task Poke(byte[] bytes)
{
    using var c = new TcpClient();
    await c.ConnectAsync("127.0.0.1", port);
    var s = c.GetStream();
    var buf = new byte[4096];
    await s.ReadAsync(buf); // server hello
    await s.WriteAsync(bytes);
    await Task.Delay(200);
}
