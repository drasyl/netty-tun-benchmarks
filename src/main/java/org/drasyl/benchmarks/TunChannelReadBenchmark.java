package org.drasyl.benchmarks;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.PlatformDependent;
import org.drasyl.channel.tun.Tun4Packet;
import org.drasyl.channel.tun.TunAddress;
import org.drasyl.channel.tun.TunChannel;
import org.drasyl.channel.tun.jna.windows.WindowsTunDevice;
import org.drasyl.channel.tun.jna.windows.Wintun.WINTUN_ADAPTER_HANDLE;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE;
import static java.util.Objects.requireNonNull;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunGetAdapterLUID;

@SuppressWarnings({"java:S112", "java:S2142", "DataFlowIssue", "resource", "NewClassNamingConvention", "JmhInspections", "StatementWithEmptyBody"})
public class TunChannelReadBenchmark extends AbstractBenchmark {
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

    @Setup
    public void setup() {
        try {
            writeGroup = new NioEventLoopGroup(writeThreads);
            group = new DefaultEventLoopGroup(1);

            channel = new Bootstrap()
                    .group(group)
                    .channel(TunChannel.class)
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
                            if (msg instanceof Tun4Packet) {
                                ((Tun4Packet) msg).release();
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
            else if (PlatformDependent.isWindows()) {
                // Windows
                final WINTUN_ADAPTER_HANDLE adapter = ((WindowsTunDevice) ((TunChannel) channel).device()).adapter();

                final Pointer interfaceLuid = new Memory(8);
                WintunGetAdapterLUID(adapter, interfaceLuid);
                AddressAndNetmaskHelper.setIPv4AndNetmask(interfaceLuid, SRC_ADDRESS, 31);
            }
            else {
                // Linux
                exec("/sbin/ip", "addr", "add", SRC_ADDRESS + '/' + 31, "dev", name);
                exec("/sbin/ip", "link", "set", "dev", name, "up");
            }

            final ByteBuf msg = Unpooled.wrappedBuffer(new byte[packetSize]);

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

    @SuppressWarnings({"unchecked", "CallToPrintStackTrace"})
    static class WriteHandler<E> extends ChannelDuplexHandler {
        private final E msg;
        private final UnaryOperator<E> msgDuplicator;
        private volatile boolean stopWriting;

        public WriteHandler(final E msg,
                     final UnaryOperator<E> msgDuplicator) {
            this.msg = requireNonNull(msg);
            this.msgDuplicator = requireNonNull(msgDuplicator);
        }

        public WriteHandler(final ByteBuf msg) {
            this((E) msg, e -> (E) ((ByteBuf) e).retainedDuplicate());
        }

        public void stopWriting() {
            stopWriting = true;
        }

        @Override
        public void handlerAdded(final ChannelHandlerContext ctx) {
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
                ctx.write(msgDuplicator.apply(msg)).addListener(FIRE_EXCEPTION_ON_FAILURE);
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

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx,
                                    final Throwable cause) {
            if (!(cause instanceof PortUnreachableException) && !(cause instanceof IOException && "No buffer space available".equals(cause.getMessage()) && ctx.channel().isActive())) {
                cause.printStackTrace();
            }
        }
    }

    private static void exec(final String... command) throws IOException {
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
