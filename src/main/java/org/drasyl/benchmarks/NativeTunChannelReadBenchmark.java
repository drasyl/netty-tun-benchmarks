package org.drasyl.benchmarks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollTunChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueTunChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.Tun4Packet;
import io.netty.channel.socket.TunAddress;
import io.netty.channel.socket.TunChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.internal.PlatformDependent;
import org.drasyl.benchmarks.TunChannelReadBenchmark.WriteHandler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings({"java:S112", "java:S2142", "DataFlowIssue"})
public class NativeTunChannelReadBenchmark extends AbstractBenchmark {
    private static final String SRC_ADDRESS = "10.10.10.10";
    private static final String DST_ADDRESS = "10.10.10.11";
    private static final int PORT = 12345;
    @Param({ "1" })
    private int writeThreads;
    @Param({ "1468" })
    private int packetSize;
    private EventLoopGroup writeGroup;
    private EventLoopGroup group;
    private ChannelGroup writeChannels;
    private Channel channel;
    private final AtomicLong receivedPackets = new AtomicLong();

    @SuppressWarnings("unchecked")
    @Setup
    public void setup() {
        writeGroup = new NioEventLoopGroup(writeThreads);
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
        final ByteBuf msg = Unpooled.wrappedBuffer(new byte[packetSize]);

        try {
            channel = new Bootstrap()
                    .group(group)
                    .channel(channelClass)
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(final ChannelHandlerContext ctx, final Object msg1) {
                            if (msg1 instanceof Tun4Packet) {
                                ((Tun4Packet) msg1).release();
                                receivedPackets.incrementAndGet();
                            }
                        }
                    })
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

            final Bootstrap writeBootstrap = new Bootstrap()
                    .group(writeGroup)
                    .channel(NioDatagramChannel.class)
                    .handler(new WriteHandler<>(msg));

            writeChannels = new DefaultChannelGroup(writeGroup.next());
            for (int i = 0; i < writeThreads; i++) {
                msg.retain();
                writeChannels.add(writeBootstrap.connect(DST_ADDRESS, PORT).sync().channel());
            }
        }
        catch (final Exception e) {
            handleUnexpectedException(e);
        }
    }

    @TearDown
    public void teardown() {
        try {
            writeChannels.forEach(ch -> ch.pipeline().get(WriteHandler.class).stopWriting());
            writeChannels.close().await();
            channel.close().await();
            writeGroup.shutdownGracefully().await();
            group.shutdownGracefully().await();
        }
        catch (final Exception e) {
            handleUnexpectedException(e);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void read() {
        while (receivedPackets.get() < 1) {
            // do nothing
        }
        receivedPackets.getAndDecrement();
    }

    static void exec(final String... command) throws IOException {
        try {
            final int exitCode = Runtime.getRuntime().exec(command).waitFor();
            if (exitCode != 0) {
                throw new IOException("Executing `" + String.join(" ", command) + "` returned non-zero exit code (" + exitCode + ").");
            }
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

