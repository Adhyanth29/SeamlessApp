# SeamlessClip wire protocol — v1

Both apps implement this spec. If you change it, change **both**
`windows/SeamlessClip/Protocol/*` and `android/app/src/main/java/app/seamlessclip/protocol/*`
and bump `v`.

## Roles and ports

| Role   | Who                  | Transport                                   |
|--------|----------------------|---------------------------------------------|
| Server | Windows tray app     | TCP listen on `0.0.0.0:45700` (configurable) |
| Client | Android app          | Connects to the PC over the LAN              |
| Beacon | Windows tray app     | UDP broadcast to port `45701` every 3 s       |

The phone always dials the PC. This avoids needing the phone to accept inbound
connections (which Android makes hard in the background).

## Pairing

The PC generates a random 32-byte **pairing key** (stored with DPAPI on
Windows, and with an Android Keystore–wrapped key on the phone). The PC shows a
QR code containing:

```
seamlessclip://pair?v=1&id=<serverId>&n=<pcName>&h=<ip1,ip2,...>&p=<port>&k=<key>
```

- `id`   – random 16 hex chars identifying this PC install
- `n`    – PC display name (percent-encoded)
- `h`    – comma-separated IPv4 addresses of the PC (best guess first)
- `p`    – TCP port
- `k`    – pairing key, base64url **without** padding

Anyone who sees the QR code can pair, so treat it like a password. "New key" in
the PC's pairing window rotates the key and disconnects every paired phone.

## Framing

Every message on the TCP stream is a frame:

```
uint32 big-endian length N  (N ≤ 4 KiB during handshake, ≤ 8 MiB afterwards)
N bytes payload
```

## Handshake

1. **Server → client, plaintext frame**
   ```json
   {"proto":"seamless-clip","v":1,"serverId":"<id>","nonce":"<base64 16 bytes>"}
   ```
2. **Client → server, plaintext frame**
   ```json
   {"proto":"seamless-clip","v":1,"clientId":"<uuid>","nonce":"<base64 16 bytes>"}
   ```
   The client aborts if `serverId` does not match the paired PC.
3. Both sides derive the session key:
   ```
   sessionKey = HKDF-SHA256(IKM = pairingKey,
                            salt = serverNonce || clientNonce,
                            info = "seamless-clip v1 session",
                            L = 32)
   ```
4. From here on every frame payload is `AES-256-GCM(sessionKey)` ciphertext
   followed by the 16-byte tag. The 12-byte GCM nonce is **not transmitted**;
   each side computes it:
   ```
   nonce = uint32 BE direction || uint64 BE counter
   direction: 1 = server→client, 2 = client→server
   counter:   starts at 0 per direction, +1 per frame
   ```
   Because the key is fresh per session and TCP is ordered, this gives replay
   protection and never reuses a (key, nonce) pair. No associated data.
5. **Client → server, encrypted:** `{"type":"auth","device":"Pixel 8"}`
   If the server cannot decrypt this (wrong key), it closes the connection.
6. **Server → client, encrypted:** `{"type":"auth_ok","device":"MY-LAPTOP"}`

## Messages (encrypted JSON, UTF-8)

| type      | fields                                         | notes |
|-----------|------------------------------------------------|-------|
| `clip`    | `id`, `mime` (`text/plain`), `text`, `ts` (unix ms), `device` | either direction |
| `ping`    | –                                              | client sends every 20 s |
| `pong`    | –                                              | reply to `ping` |

Receivers ignore unknown `type`s and unknown fields, so new features can be
added without breaking older builds.

Timeouts: server drops a connection after 60 s without a frame; client treats
45 s of silence as dead and reconnects with exponential backoff (1 s → 30 s).

## Discovery beacon (UDP, unauthenticated)

```json
{"proto":"seamless-clip","v":1,"serverId":"<id>","name":"MY-LAPTOP","port":45700}
```

Sent to 255.255.255.255 and every interface's directed broadcast address. The
phone only uses it to learn the PC's *current* IP when DHCP changed it. A
spoofed beacon can at worst make the phone dial the wrong host, which then
fails the handshake because it doesn't know the pairing key.

## Loop prevention

When a side applies a remote clip to its clipboard it remembers that text; the
next local clipboard change with identical text is not sent back.

## Limits

- Text only in v1 (max 4 MiB UTF-8). Images/files are on the roadmap
  (`mime` field reserved for this).
