# HTTP/3 via SOCKS5 UDP Example (Java)

<a href="https://oxylabs.io/?utm_source=github&utm_medium=referral&utm_content=javahttp3viasocks5udp&utm_campaign=banner&groupid=877"><img src="https://img.shields.io/badge/🚀_Get_Started-FF6B35?style=for-the-badge" alt="Get started"></a>
<a href="https://developers.oxylabs.io/?utm_source=github&utm_medium=referral&utm_content=javahttp3viasocks5udp&utm_campaign=banner&groupid=877"><img src="https://img.shields.io/badge/📚_Documentation-4A90E2?style=for-the-badge" alt="Documentation"></a>
<a href="https://dashboard.oxylabs.io"><img src="https://img.shields.io/badge/📊_Dashboard-2EA44F?style=for-the-badge" alt="Dashboard"></a>
<a href="https://discord.gg/Pds3gBmKMH"><img src="https://img.shields.io/badge/💬_Discord-5865F2?style=for-the-badge" alt="Discord"></a>
<a href="https://www.youtube.com/@oxylabs"><img src="https://img.shields.io/badge/▶️_YouTube-FF0000?style=for-the-badge" alt="YouTube"></a>

An example on how to perform an HTTP/3 request through a SOCKS5 UDP proxy from Java.

HTTP/3 uses UDP instead of TCP at the transport layer, which requires SOCKS5 UDP
support — a feature not widely available in standard HTTP client libraries. This
project bridges that gap by:

- Performing a SOCKS5 `UDP ASSOCIATE` handshake (RFC 1928 / RFC 1929)
- Wrapping every outgoing UDP datagram with the SOCKS5 UDP request header and
  unwrapping incoming datagrams transparently inside the Netty pipeline
- Driving QUIC and HTTP/3 with the Netty incubator codecs over that pipeline
- Providing a small CLI

## Requirements

- JDK 17+
- Maven 3.8+

The HTTP/3 stack is provided by:

- `io.netty.incubator:netty-incubator-codec-http3`
- `io.netty.incubator:netty-incubator-codec-native-quic` (BoringSSL-backed,
  classifiers for macOS x86_64 / aarch64, Linux x86_64 / aarch64 and Windows x86_64
  are all declared in `pom.xml`)

## Build

```bash
mvn -q package
```

This produces a runnable shaded jar at `target/http3viasocks5udp.jar`.

## Usage

```
  -h <host:port>   proxy host with port (default: socks.pr.oxylabs.io:7777)
  -u <username>    proxy username
  -p <password>    proxy password
  -t <host[:port]> target host, default port 443 (default: echo-udp.oxylabs.io)
  -body <body>     optional request body
```

Example:

```bash
java -jar target/http3viasocks5udp.jar \
    -h socks.pr.oxylabs.io:7777 \
    -u myproxyusername \
    -p myproxypassword \
    -t echo-udp.oxylabs.io \
    -body "Hello HTTP3"
```

Or run directly with Maven during development:

```bash
mvn -q exec:java -Dexec.args="-h socks.pr.oxylabs.io:7777 -u USER -p PASS -t echo-udp.oxylabs.io -body 'Hello HTTP3'"
```

## Source layout

```
src/main/java/io/oxylabs/http3socks5/
    Socks5UdpClient.java       SOCKS5 control connection + UDP header (de)encapsulation
    Socks5DatagramChannel.java Netty handler wiring SOCKS5 UDP into the pipeline
    Http3ViaSocks5Client.java  QUIC + HTTP/3 client built on Netty incubator codecs
    Main.java                  picocli CLI entry point
```

## Integration

For programmatic usage, see `Http3ViaSocks5Client` for:

- Setting up the SOCKS5 UDP relay
- Configuring Netty's QUIC + HTTP/3 codecs
- Authenticating against a SOCKS5 proxy with username/password
