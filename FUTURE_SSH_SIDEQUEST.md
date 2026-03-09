# Future Side Quest: Async SSH with Netty

Rewrite [mwiede/jsch](https://github.com/mwiede/jsch) core with Netty for fully asynchronous SSH.

## Idea

jsch is blocking, single-threaded, and built on `Socket`/`InputStream`/`OutputStream`.
SSH is a multiplexed protocol — it maps naturally to Netty's pipeline model.

Keep jsch's battle-tested protocol and crypto logic. Replace the I/O substrate with Netty.

## Philosophy

Don't try to surgically transplant jsch's internals. Reading someone else's codebase means
reverse-engineering their mental model, legacy workarounds, and historical baggage.
Instead: read the RFCs, skim jsch to understand *what* protocol states exist, then build
it fresh on Netty. You'll understand it better because you produced it, and the result will
be cleaner than any transplant.

This is the same approach that worked for sqleicht — studied HikariCP's architecture, took
the good ideas, dropped what didn't apply, and designed for our own constraints.

Every project is inherently constrained by its author's skills and knowledge at the time.
The API *is* their understanding of the system, frozen in code. jsch was written in early
2000s Java — so it's blocking, `synchronized` everywhere, no generics. Not because those
were the best choices for SSH, but because that's what Java offered and the author knew.
Same story with JDBC and "one thread per connection." When you rewrite, you start with
today's constraints instead of inheriting someone else's from a decade ago.

Code you didn't write, you don't own. Even with full comprehension, you only see the
crystallization — the tip of the iceberg. You don't see what the author considered and
rejected, where they were when the idea clicked, what vision guided the trade-offs.
You can study it deeply, but your understanding remains surface-level. Like in Kafka's
"Der Prozess" — the accused navigates the lower courts endlessly but never reaches the
higher court where the real decisions are made. Reading code is the same: you're always
one level removed from the actual understanding. Writing it yourself is the only way up.

## Approach

1. Map jsch's architecture — identify which classes do I/O vs protocol vs crypto
2. Multi-pass AI extraction:
   - Pass 1: Feed jsch classes, strip all I/O and `synchronized` blocks, keep protocol/crypto logic
   - Pass 2: Feed cleaned protocol logic + RFCs + Netty API → reimplement as `ChannelHandler`s
3. SSH packet framing → `ByteToMessageDecoder` (length, decrypt, verify MAC, emit)
4. Outbound packets → `MessageToByteEncoder` (encrypt, MAC, write)
5. SSH channels → multiplexed over a single Netty `Channel`
6. Replace blocking `read()` loops with event-driven handlers

## Core RFCs (the spec)

Plain text, available at `https://www.rfc-editor.org/rfc/rfcNNNN`:

- **RFC 4251** — SSH Protocol Architecture (overview)
- **RFC 4252** — SSH Authentication Protocol
- **RFC 4253** — SSH Transport Layer Protocol (KEX, encryption, MAC, packet format)
- **RFC 4254** — SSH Connection Protocol (channels, port forwarding, sessions)

For the Netty rewrite, RFC 4253 (packet format) and RFC 4254 (channels) matter most.

### Supplementary (newer algorithms)

- RFC 5656 — Elliptic curve (ECDSA, ECDH)
- RFC 8332 — RSA SHA-256/512 signatures
- RFC 8709 — Ed25519/Ed448
- RFC 6668 — SHA-2 HMACs

## What stays from jsch

- Crypto (KEX, host key verification, key re-exchange)
- Channel windowing / flow control
- All security invariants (timing-safe comparisons, HMAC verification, sequence numbers)

## What gets replaced

- `java.net.Socket` → `io.netty.channel.Channel`
- `InputStream`/`OutputStream` → Netty pipeline encoders/decoders
- `synchronized` blocks → Netty's single-threaded event loop model
- Blocking "send then wait" patterns → state machines with callback handlers

## Alternatives considered

- **Apache MINA SSHD** — already async (NIO-based), mature. Check if it fits before building.

## Platform I/O backends

| | Linux | Windows | macOS |
|---|---|---|---|
| Netty native | `EpollEventLoopGroup` | NIO (IOCP under the hood) | `KQueueEventLoopGroup` |
