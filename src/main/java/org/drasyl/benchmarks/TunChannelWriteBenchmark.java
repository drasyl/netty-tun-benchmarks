package org.drasyl.benchmarks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import org.drasyl.channel.tun.Tun4Packet;
import org.drasyl.channel.tun.TunAddress;
import org.drasyl.channel.tun.TunChannel;
import org.drasyl.channel.tun.TunPacket;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
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
import java.net.UnknownHostException;

import static io.netty.channel.ChannelOption.WRITE_BUFFER_WATER_MARK;
import static org.pcap4j.packet.namednumber.IpVersion.IPV4;

public class TunChannelWriteBenchmark extends AbstractBenchmark {
    private static final String ADDRESS = "10.10.10.10";
    @Param({ "32" })
    private int flushAfter;
    @Param({ "1468" })
    private int packetSize;
    private boolean flush;
    private int messagesSinceFlush;
    private EventLoopGroup group;
    private Channel channel;
    private TunPacket packet;

    @SuppressWarnings("unchecked")
    @Setup
    public void setup() throws UnknownHostException {
        group = new DefaultEventLoopGroup(1);

        // build packet
        final IpV4Packet.Builder packetBuilder = new IpV4Packet.Builder();
        packetBuilder.version(IPV4)
                .tos(IpV4Rfc791Tos.newInstance((byte) 0))
                .identification((short) 100)
                .ttl((byte) 100)
                .protocol(IpNumber.UDP)
                .srcAddr((Inet4Address) InetAddress.getByName(ADDRESS))
                .dstAddr((Inet4Address) InetAddress.getByName(ADDRESS))
                .payloadBuilder(new UdpPacket.Builder()
                        .srcPort(new UdpPort((short) 12345, "udp"))
                        .dstPort(new UdpPort((short) 12345, "udp"))
                        .payloadBuilder(new UnknownPacket.Builder().rawData(new byte[packetSize]))
                        .correctLengthAtBuild(true)
                )
                .correctChecksumAtBuild(true)
                .correctLengthAtBuild(true);
        packet = new Tun4Packet(Unpooled.wrappedBuffer(packetBuilder.build().getRawData()));

        try {
            channel = new Bootstrap()
                    .group(group)
                    .channel(TunChannel.class)
                    .option(WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(flushAfter * (packetSize + 20) * 2, flushAfter * (packetSize + 20) * 2))
                    .handler(new ChannelInboundHandlerAdapter())
                    .bind(new TunAddress())
                    .sync()
                    .channel();
        }
        catch (final Exception e) {
            handleUnexpectedException(e);
        }
    }

    @TearDown
    public void teardown() {
        try {
            packet.release();
            channel.close().await();
            group.shutdownGracefully().await();
        }
        catch (final Exception e) {
            handleUnexpectedException(e);
        }
    }

    @Setup(Level.Invocation)
    public void setupWrite() {
        if (++messagesSinceFlush >= flushAfter) {
            flush = true;
            messagesSinceFlush = 0;
        }
        else {
            flush = false;
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void write(final Blackhole blackhole) {
        while (!channel.isWritable()) {
            // wait until channel is writable again
        }

        final ChannelFuture future;
        if (flush) {
            future = channel.writeAndFlush(packet.retain());
        }
        else {
            future = channel.write(packet.retain());
        }
        blackhole.consume(future);
    }
}
