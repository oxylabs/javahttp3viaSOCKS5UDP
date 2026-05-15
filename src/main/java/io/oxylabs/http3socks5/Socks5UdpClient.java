package io.oxylabs.http3socks5;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Minimal SOCKS5 client that performs UDP ASSOCIATE.
 *
 * <p>RFC 1928 / RFC 1929. Supports NO-AUTH (0x00) and USERNAME/PASSWORD (0x02).
 * The TCP control connection must stay open for the lifetime of the UDP relay.
 */
public final class Socks5UdpClient implements Closeable {

    private static final byte SOCKS_VERSION = 0x05;
    private static final byte CMD_UDP_ASSOCIATE = 0x03;
    private static final byte ATYP_IPV4 = 0x01;
    private static final byte ATYP_DOMAIN = 0x03;
    private static final byte ATYP_IPV6 = 0x04;
    private static final byte AUTH_NONE = 0x00;
    private static final byte AUTH_USER_PASS = 0x02;

    private final Socket controlSocket;
    private final InetSocketAddress udpRelayAddress;

    private Socks5UdpClient(Socket controlSocket, InetSocketAddress udpRelayAddress) {
        this.controlSocket = controlSocket;
        this.udpRelayAddress = udpRelayAddress;
    }

    /**
     * Connects to the SOCKS5 proxy and performs UDP ASSOCIATE.
     *
     * @param proxyHost proxy host
     * @param proxyPort proxy port
     * @param username  optional username (may be null/empty for no-auth)
     * @param password  optional password
     * @param timeoutMs socket timeout for the handshake
     * @return an open client whose {@link #getUdpRelayAddress()} is the proxy UDP relay
     */
    public static Socks5UdpClient connect(String proxyHost, int proxyPort,
                                          String username, String password,
                                          int timeoutMs) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(timeoutMs);
        socket.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);

        boolean useAuth = username != null && !username.isEmpty();
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        DataInputStream in = new DataInputStream(socket.getInputStream());

        // Greeting: VER NMETHODS METHODS
        if (useAuth) {
            out.write(new byte[]{SOCKS_VERSION, 0x02, AUTH_NONE, AUTH_USER_PASS});
        } else {
            out.write(new byte[]{SOCKS_VERSION, 0x01, AUTH_NONE});
        }
        out.flush();

        int ver = in.readUnsignedByte();
        int method = in.readUnsignedByte();
        if (ver != SOCKS_VERSION) {
            throw new IOException("Unexpected SOCKS version in greeting reply: " + ver);
        }
        if (method == 0xFF) {
            throw new IOException("SOCKS5 proxy rejected all offered auth methods");
        }
        if (method == AUTH_USER_PASS) {
            performUserPassAuth(in, out, username, password);
        } else if (method != AUTH_NONE) {
            throw new IOException("Unsupported SOCKS5 auth method selected: " + method);
        }

        // UDP ASSOCIATE: VER CMD RSV ATYP DST.ADDR DST.PORT
        // We bind 0.0.0.0:0 so the proxy picks the UDP relay endpoint.
        out.write(new byte[]{
                SOCKS_VERSION, CMD_UDP_ASSOCIATE, 0x00, ATYP_IPV4,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
        });
        out.flush();

        InetSocketAddress relay = readReply(in, proxyHost);
        return new Socks5UdpClient(socket, relay);
    }

    private static void performUserPassAuth(DataInputStream in, DataOutputStream out,
                                            String username, String password) throws IOException {
        byte[] user = username == null ? new byte[0] : username.getBytes("UTF-8");
        byte[] pass = password == null ? new byte[0] : password.getBytes("UTF-8");
        if (user.length > 255 || pass.length > 255) {
            throw new IOException("SOCKS5 username/password must be <= 255 bytes");
        }
        out.writeByte(0x01);
        out.writeByte(user.length);
        out.write(user);
        out.writeByte(pass.length);
        out.write(pass);
        out.flush();

        int subVer = in.readUnsignedByte();
        int status = in.readUnsignedByte();
        if (subVer != 0x01 || status != 0x00) {
            throw new IOException("SOCKS5 username/password authentication failed (status=" + status + ")");
        }
    }

    private static InetSocketAddress readReply(DataInputStream in, String proxyHost) throws IOException {
        int ver = in.readUnsignedByte();
        int rep = in.readUnsignedByte();
        in.readUnsignedByte(); // RSV
        int atyp = in.readUnsignedByte();
        if (ver != SOCKS_VERSION) {
            throw new IOException("Unexpected SOCKS version in reply: " + ver);
        }
        if (rep != 0x00) {
            throw new IOException("SOCKS5 UDP ASSOCIATE failed, reply code=" + rep);
        }

        InetAddress addr;
        switch (atyp) {
            case ATYP_IPV4: {
                byte[] raw = new byte[4];
                in.readFully(raw);
                addr = InetAddress.getByAddress(raw);
                break;
            }
            case ATYP_IPV6: {
                byte[] raw = new byte[16];
                in.readFully(raw);
                addr = InetAddress.getByAddress(raw);
                break;
            }
            case ATYP_DOMAIN: {
                int len = in.readUnsignedByte();
                byte[] raw = new byte[len];
                in.readFully(raw);
                addr = InetAddress.getByName(new String(raw, "US-ASCII"));
                break;
            }
            default:
                throw new IOException("Unsupported ATYP in SOCKS5 reply: " + atyp);
        }
        int port = in.readUnsignedShort();

        // Many proxies return 0.0.0.0; in that case the relay shares the proxy host.
        if (addr.isAnyLocalAddress()) {
            addr = InetAddress.getByName(proxyHost);
        }
        return new InetSocketAddress(addr, port);
    }

    /** Wraps a payload with a SOCKS5 UDP request header for the given destination. */
    public static byte[] encapsulate(InetSocketAddress destination, byte[] payload) {
        InetAddress addr = destination.getAddress();
        byte[] addrBytes;
        byte atyp;
        if (addr != null && addr.getAddress().length == 4) {
            atyp = ATYP_IPV4;
            addrBytes = addr.getAddress();
        } else if (addr != null && addr.getAddress().length == 16) {
            atyp = ATYP_IPV6;
            addrBytes = addr.getAddress();
        } else {
            atyp = ATYP_DOMAIN;
            byte[] host = destination.getHostString().getBytes();
            if (host.length > 255) {
                throw new IllegalArgumentException("Destination hostname too long for SOCKS5");
            }
            addrBytes = new byte[1 + host.length];
            addrBytes[0] = (byte) host.length;
            System.arraycopy(host, 0, addrBytes, 1, host.length);
        }
        byte[] out = new byte[4 + addrBytes.length + 2 + payload.length];
        // RSV RSV FRAG ATYP
        out[0] = 0x00;
        out[1] = 0x00;
        out[2] = 0x00;
        out[3] = atyp;
        System.arraycopy(addrBytes, 0, out, 4, addrBytes.length);
        int portOffset = 4 + addrBytes.length;
        out[portOffset] = (byte) ((destination.getPort() >> 8) & 0xff);
        out[portOffset + 1] = (byte) (destination.getPort() & 0xff);
        System.arraycopy(payload, 0, out, portOffset + 2, payload.length);
        return out;
    }

    /** Removes the SOCKS5 UDP header and returns the inner payload. */
    public static byte[] decapsulate(byte[] datagram, int length) throws IOException {
        if (length < 10) {
            throw new IOException("SOCKS5 UDP datagram too short: " + length);
        }
        int atyp = datagram[3] & 0xff;
        int headerLen;
        switch (atyp) {
            case ATYP_IPV4:
                headerLen = 4 + 4 + 2;
                break;
            case ATYP_IPV6:
                headerLen = 4 + 16 + 2;
                break;
            case ATYP_DOMAIN:
                int dlen = datagram[4] & 0xff;
                headerLen = 4 + 1 + dlen + 2;
                break;
            default:
                throw new IOException("Unsupported ATYP in SOCKS5 UDP header: " + atyp);
        }
        if (length < headerLen) {
            throw new IOException("SOCKS5 UDP datagram shorter than declared header");
        }
        byte[] payload = new byte[length - headerLen];
        System.arraycopy(datagram, headerLen, payload, 0, payload.length);
        return payload;
    }

    public InetSocketAddress getUdpRelayAddress() {
        return udpRelayAddress;
    }

    @Override
    public void close() throws IOException {
        controlSocket.close();
    }
}
