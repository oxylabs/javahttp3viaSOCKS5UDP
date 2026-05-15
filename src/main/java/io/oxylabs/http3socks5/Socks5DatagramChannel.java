package io.oxylabs.http3socks5;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;

/**
 * Channel handler that transparently encapsulates outgoing datagrams in a SOCKS5 UDP
 * header (sending them to the proxy relay) and decapsulates incoming datagrams so that
 * upstream codecs (QUIC / HTTP/3) see the real remote address.
 */
public final class Socks5DatagramChannel extends ChannelDuplexHandler {

    private final InetSocketAddress relayAddress;
    private final InetSocketAddress targetAddress;

    public Socks5DatagramChannel(InetSocketAddress relayAddress, InetSocketAddress targetAddress) {
        this.relayAddress = relayAddress;
        this.targetAddress = targetAddress;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof DatagramPacket) {
            DatagramPacket original = (DatagramPacket) msg;
            try {
                ByteBuf content = original.content();
                byte[] payload = new byte[content.readableBytes()];
                content.getBytes(content.readerIndex(), payload);

                InetSocketAddress dest = (InetSocketAddress) original.recipient();
                if (dest == null) {
                    dest = targetAddress;
                }

                byte[] wrapped = Socks5UdpClient.encapsulate(dest, payload);
                DatagramPacket replacement = new DatagramPacket(
                        Unpooled.wrappedBuffer(wrapped), relayAddress);
                ctx.write(replacement, promise);
            } finally {
                original.release();
            }
            return;
        }
        ctx.write(msg, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DatagramPacket) {
            DatagramPacket packet = (DatagramPacket) msg;
            try {
                ByteBuf buf = packet.content();
                byte[] raw = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), raw);
                byte[] inner = Socks5UdpClient.decapsulate(raw, raw.length);
                DatagramPacket replacement = new DatagramPacket(
                        Unpooled.wrappedBuffer(inner),
                        (InetSocketAddress) packet.recipient(),
                        targetAddress);
                ctx.fireChannelRead(replacement);
            } finally {
                packet.release();
            }
            return;
        }
        ctx.fireChannelRead(msg);
    }
}
