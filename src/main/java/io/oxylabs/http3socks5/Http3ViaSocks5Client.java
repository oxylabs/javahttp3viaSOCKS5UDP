package io.oxylabs.http3socks5;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.http3.Http3;
import io.netty.incubator.codec.http3.Http3ClientConnectionHandler;
import io.netty.incubator.codec.http3.Http3DataFrame;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import io.netty.incubator.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/** Performs an HTTP/3 GET request to {@code target} via the supplied SOCKS5 proxy. */
public final class Http3ViaSocks5Client {

    private final String proxyHost;
    private final int proxyPort;
    private final String proxyUsername;
    private final String proxyPassword;

    public Http3ViaSocks5Client(String proxyHost, int proxyPort,
                                String proxyUsername, String proxyPassword) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.proxyUsername = proxyUsername;
        this.proxyPassword = proxyPassword;
    }

    public void execute(String targetHost, int targetPort, String body) throws Exception {
        InetSocketAddress targetAddress = new InetSocketAddress(targetHost, targetPort);

        try (Socks5UdpClient socks = Socks5UdpClient.connect(
                proxyHost, proxyPort, proxyUsername, proxyPassword, 10_000)) {

            InetSocketAddress relay = socks.getUdpRelayAddress();
            System.out.println("SOCKS5 UDP relay: " + relay);

            QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                    .trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols(Http3.supportedApplicationProtocols())
                    .build();

            ChannelHandler quicCodec = Http3.newQuicClientCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(40, TimeUnit.SECONDS)
                    .initialMaxData(10_000_000)
                    .initialMaxStreamDataBidirectionalLocal(1_000_000)
                    .initialMaxStreamDataBidirectionalRemote(1_000_000)
                    .initialMaxStreamsBidirectional(100)
                    .initialMaxStreamsUnidirectional(100)
                    .build();

            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                Bootstrap bs = new Bootstrap()
                        .group(group)
                        .channel(NioDatagramChannel.class)
                        .option(ChannelOption.SO_REUSEADDR, true)
                        .handler(new ChannelInitializer<NioDatagramChannel>() {
                            @Override
                            protected void initChannel(NioDatagramChannel ch) {
                                // Inbound: relay -> decapsulate -> QUIC codec.
                                // Outbound: QUIC codec -> encapsulate -> relay.
                                ch.pipeline().addLast(new Socks5DatagramChannel(relay, targetAddress));
                                ch.pipeline().addLast(quicCodec);
                            }
                        });

                Channel datagramChannel = bs.bind(0).sync().channel();

                QuicChannel quicChannel = QuicChannel.newBootstrap(datagramChannel)
                        .handler(new Http3ClientConnectionHandler())
                        .remoteAddress(targetAddress)
                        .connect()
                        .get();

                ResponseCollector collector = new ResponseCollector();
                QuicStreamChannel stream = Http3.newRequestStream(quicChannel, collector).sync().getNow();

                DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
                headersFrame.headers()
                        .method("GET")
                        .path("/")
                        .authority(targetHost + ":" + targetPort)
                        .scheme("https")
                        .add(HttpHeaderNames.USER_AGENT, "http3viasocks5udp-java/1.0");

                stream.writeAndFlush(headersFrame).sync();

                if (body != null && !body.isEmpty()) {
                    ByteBuf content = Unpooled.wrappedBuffer(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    stream.writeAndFlush(new DefaultHttp3DataFrame(content)).sync();
                }
                stream.shutdownOutput().sync();

                collector.awaitCompletion(45, TimeUnit.SECONDS);

                quicChannel.close().sync();
                datagramChannel.close().sync();
            } finally {
                group.shutdownGracefully().sync();
            }
        }
    }

    /** Collects the HTTP/3 response and prints status / protocol / body when complete. */
    private static final class ResponseCollector extends Http3RequestStreamInboundHandler {

        private final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        private final StringBuilder body = new StringBuilder();
        private CharSequence status = "(unknown)";

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            CharSequence s = frame.headers().status();
            if (s != null) {
                status = s;
            }
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
            ByteBuf content = frame.content();
            body.append(content.toString(java.nio.charset.StandardCharsets.UTF_8));
            frame.release();
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            try {
                System.out.println("Status:   " + status);
                System.out.println("Protocol: HTTP/3");
                System.out.println("Response:");
                System.out.println(body);
            } finally {
                done.countDown();
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            done.countDown();
            ctx.close();
        }

        void awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            done.await(timeout, unit);
        }
    }
}
