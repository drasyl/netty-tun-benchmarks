package org.drasyl.benchmarks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollTunChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueTunChannel;
import io.netty.channel.socket.Tun4Packet;
import io.netty.channel.socket.TunAddress;
import io.netty.channel.socket.TunChannel;
import io.netty.channel.socket.TunPacket;
import org.drasyl.benchmarks.TunChannelWriteBenchmark.WriteHandler;
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
import java.net.UnknownHostException;

import static org.pcap4j.packet.namednumber.IpVersion.IPV4;

public class NativeTunChannelWriteBenchmark extends AbstractBenchmark {
    private static final String ADDRESS = "10.10.10.10";
    @Param({ "1468" })
    private int packetSize;
    private EventLoopGroup group;
    private Channel channel;
    protected WriteHandler<TunPacket> writeHandler;

    @SuppressWarnings("unchecked")
    @Setup
    public void setup() throws UnknownHostException {
        final Class<? extends TunChannel> channelClass;
        if (KQueue.isAvailable()) {
            group = new KQueueEventLoopGroup(1);
            channelClass = KQueueTunChannel.class;
        } else if (Epoll.isAvailable()) {
            group = new EpollEventLoopGroup(1);
            channelClass = EpollTunChannel.class;
        } else {
            throw new RuntimeException("Unsupported platform: Neither kqueue nor epoll are available");
        }

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
        final Tun4Packet packet = new Tun4Packet(Unpooled.wrappedBuffer(packetBuilder.build().getRawData()));

        try {
            writeHandler = new WriteHandler<>(packet, oldPacket -> new Tun4Packet(oldPacket.content().retainedDuplicate()));
            channel = new Bootstrap()
                    .group(group)
                    .channel(channelClass)
                    .handler(writeHandler)
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
}
