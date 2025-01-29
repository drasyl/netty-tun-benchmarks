package org.drasyl.benchmarks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.PlatformDependent;
import org.drasyl.channel.tun.Tun4Packet;
import org.drasyl.channel.tun.TunAddress;
import org.drasyl.channel.tun.TunChannel;
import org.drasyl.channel.tun.TunPacket;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV4Rfc791Tos;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.UdpPort;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;
import static org.drasyl.benchmarks.NativeTunChannelReadBenchmark.exec;
import static org.pcap4j.packet.namednumber.IpVersion.IPV4;

@SuppressWarnings({"java:S112", "java:S2142", "java:S3077", "DataFlowIssue", "NewClassNamingConvention", "JmhInspections", "StatementWithEmptyBody"})
public class TunChannelWriteBenchmark extends AbstractBenchmark {
    private static final String SRC_ADDRESS = "10.10.10.10";
    private static final String DST_ADDRESS = "10.10.10.11";
    @Param({ "1468" })
    private int packetSize;
    private EventLoopGroup group;
    private Channel channel;
    private WriteHandler<TunPacket> writeHandler;

    @Setup
    public void setup() {
        try {
            group = new DefaultEventLoopGroup(1);

            channel = new Bootstrap()
                    .group(group)
                    .channel(TunChannel.class)
                    .handler(new ChannelInboundHandlerAdapter())
                    .bind(new TunAddress())
                    .sync()
                    .channel();
            final String name = ((TunAddress) channel.localAddress()).ifName();

            if (PlatformDependent.isOsx()) {
                exec("/sbin/ifconfig", name, "add", SRC_ADDRESS, SRC_ADDRESS);
                exec("/sbin/ifconfig", name, "up");
                exec("/sbin/route", "add", "-net", SRC_ADDRESS + '/' + 31, "-iface", name);
            }
            else {
                // Linux
                exec("/sbin/ip", "addr", "add", SRC_ADDRESS + '/' + 31, "dev", name);
                exec("/sbin/ip", "link", "set", "dev", name, "up");
            }

            final IpV4Packet.Builder packetBuilder = new IpV4Packet.Builder();
            packetBuilder.version(IPV4)
                    .tos(IpV4Rfc791Tos.newInstance((byte) 0))
                    .identification((short) 100)
                    .ttl((byte) 100)
                    .protocol(IpNumber.UDP)
                    .srcAddr((Inet4Address) InetAddress.getByName(SRC_ADDRESS))
                    .dstAddr((Inet4Address) InetAddress.getByName(DST_ADDRESS))
                    .payloadBuilder(new UdpPacket.Builder()
                            .srcPort(new UdpPort((short) 12345, "udp"))
                            .dstPort(new UdpPort((short) 12345, "udp"))
                            .payloadBuilder(new UnknownPacket.Builder().rawData(new byte[packetSize]))
                            .correctLengthAtBuild(true)
                    )
                    .correctChecksumAtBuild(true)
                    .correctLengthAtBuild(true);
            final Tun4Packet packet = new Tun4Packet(Unpooled.wrappedBuffer(packetBuilder.build().getRawData()));

            writeHandler = new WriteHandler<>(packet, oldPacket -> new Tun4Packet(oldPacket.content().retainedDuplicate()));
            channel.pipeline().addLast(writeHandler);
        }
        catch (final Exception e) {
            handleUnexpectedException(e);
        }
    }

    @TearDown
    public void teardown() {
        try {
            writeHandler.stopWriting();
            channel.close().await();
            group.shutdownGracefully().await();
        }
        catch (final Exception e) {
            handleUnexpectedException(e);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void write(final Blackhole blackhole) {
        while (writeHandler.messagesWritten().get() < 1) {
            // do nothing
        }
        blackhole.consume(writeHandler.messagesWritten().getAndDecrement());
    }

    static class WriteHandler<E> extends ChannelDuplexHandler {
        private final AtomicLong messagesWritten;
        private final E msg;
        private final UnaryOperator<E> msgDuplicator;
        private volatile ChannelFutureListener writeListener;
        private volatile boolean stopWriting;

        WriteHandler(final AtomicLong messagesWritten,
                     final E msg,
                     final UnaryOperator<E> msgDuplicator) {
            this.messagesWritten = messagesWritten;
            this.msg = requireNonNull(msg);
            this.msgDuplicator = requireNonNull(msgDuplicator);
        }

        public WriteHandler(final E msg,
                            final UnaryOperator<E> msgDuplicator) {
            this(new AtomicLong(), msg, msgDuplicator);
        }

        public AtomicLong messagesWritten() {
            return messagesWritten;
        }

        public void stopWriting() {
            stopWriting = true;
        }

        @Override
        public void handlerAdded(final ChannelHandlerContext ctx) {
            this.writeListener = future -> {
                if (future.isSuccess()) {
                    WriteHandler.this.messagesWritten.getAndIncrement();
                }
                else {
                    future.channel().pipeline().fireExceptionCaught(future.cause());
                }
            };

            if (ctx.channel().isActive()) {
                doWrite(ctx);
            }
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx) {
            ctx.fireChannelActive();
            doWrite(ctx);
        }

        private void doWrite(final ChannelHandlerContext ctx) {
            final Channel channel = ctx.channel();
            if (stopWriting || !channel.isActive()) {
                ReferenceCountUtil.release(msg);
                ctx.flush();
                return;
            }

            while (!stopWriting && channel.isWritable()) {
                ctx.write(msgDuplicator.apply(msg)).addListener(writeListener);
            }

            ctx.flush();
        }

        @Override
        public void channelWritabilityChanged(final ChannelHandlerContext ctx) {
            if (ctx.channel().isWritable()) {
                // channel is writable again try to continue writing
                doWrite(ctx);
            }
            ctx.fireChannelWritabilityChanged();
        }
    }
}
