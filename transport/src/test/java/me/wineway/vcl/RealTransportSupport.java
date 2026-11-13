/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.bootstrap.*;
import io.netty.channel.*;
import io.netty.util.concurrent.Future;
import java.net.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Shared real-backend fixture; native integration tests are opt-in, each mode uses a separate JVM. */
final class RealTransportSupport implements AutoCloseable {
    final String backend = System.getProperty("vcl.it.backend", "epoll");
    final MultiThreadIoEventLoopGroup group;
    final VclRuntime runtime;
    final Class<? extends Channel> socketClass, datagramClass;
    final Class<? extends ServerChannel> serverClass;
    private VclServer vclServer;
    private Channel epollServer;
    @SuppressWarnings("unchecked") RealTransportSupport() throws Exception {
        if (backend.equals("epoll")) {
            runtime = null;
            var factory = (IoHandlerFactory) Class.forName("io.netty.channel.epoll.EpollIoHandler").getMethod("newFactory").invoke(null);
            group = new MultiThreadIoEventLoopGroup(2, Thread.ofPlatform().name("epoll-it-", 0).factory(), factory);
            socketClass = (Class<? extends Channel>) Class.forName("io.netty.channel.epoll.EpollSocketChannel");
            serverClass = (Class<? extends ServerChannel>) Class.forName("io.netty.channel.epoll.EpollServerSocketChannel");
            datagramClass = (Class<? extends Channel>) Class.forName("io.netty.channel.epoll.EpollDatagramChannel");
        } else {
            runtime = VclRuntime.open(Path.of(required("vcl.it.library")), Path.of(required("vcl.it.socket")),
                    VclMode.valueOf(backend.toUpperCase(java.util.Locale.ROOT)), 2);
            group = new MultiThreadIoEventLoopGroup(2, Thread.ofPlatform().name("vcl-it-", 0).factory(), runtime.ioHandlerFactory());
            socketClass = VclSocketChannel.class; serverClass = VclServerSocketChannel.class; datagramClass = VclDatagramChannel.class;
        }
        // Complete startup on every fixed worker before creating sessions.
        try { for (var loop : group) loop.submit(() -> {}).sync(); }
        catch (Throwable failure) { close(); throw failure; }
    }
    InetSocketAddress address(boolean ipv6, int port) {
        return new InetSocketAddress(System.getProperty(ipv6 ? "vcl.it.ipv6" : "vcl.it.ipv4",
                backend.equals("epoll") ? ipv6 ? "::1" : "127.0.0.1" : ipv6 ? "fd77::1" : "10.77.0.1"), port);
    }
    InetSocketAddress listen(boolean ipv6, java.util.function.Supplier<ChannelHandler> child) throws Exception {
        if (runtime != null) {
            vclServer = VclServer.bind(group, address(ipv6, 0), child).sync().getNow(); return vclServer.localAddress();
        }
        EventLoop loop = group.next();
        epollServer = new ServerBootstrap().group(loop, loop).channel(serverClass).childHandler(child.get()).bind(address(ipv6, 0)).sync().channel();
        return (InetSocketAddress) epollServer.localAddress();
    }
    Channel connect(InetSocketAddress peer, ChannelHandler handler) throws Exception {
        return new Bootstrap().group(group).channel(socketClass).handler(handler).connect(peer).sync().channel();
    }
    Channel udp(boolean ipv6, ChannelHandler handler) throws Exception {
        return new Bootstrap().group(group).channel(datagramClass).handler(handler).bind(address(ipv6, 0)).sync().channel();
    }
    @Override public void close() throws Exception {
        try {
            if (vclServer != null) vclServer.close().sync();
            if (epollServer != null) epollServer.close().sync();
        } finally {
            try { group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
            finally { if (runtime != null) runtime.close(); }
        }
    }
    private static String required(String key) {
        String value = System.getProperty(key); if (value == null) throw new IllegalArgumentException("Missing -D" + key); return value;
    }
}
