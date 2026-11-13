/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** One listener per EventLoop; accepted children stay on the listener's worker.
 * The handler supplier is called on each accepted child's EventLoop. */
public final class VclServer {
    private final List<Channel> listeners;
    private final EventLoop coordinator;
    private VclServer(List<Channel> listeners, EventLoop coordinator) {
        this.listeners = List.copyOf(listeners); this.coordinator = coordinator;
    }
    public List<Channel> listeners() { return listeners; }
    public InetSocketAddress localAddress() { return (InetSocketAddress) listeners.getFirst().localAddress(); }
    public Future<Void> close() { return closeAll(listeners, coordinator); }

    public static Future<VclServer> bind(EventLoopGroup group, InetSocketAddress address,
                                         Supplier<? extends ChannelHandler> childHandler) {
        Objects.requireNonNull(childHandler, "childHandler"); AbstractVclChannel.address(address);
        var loops = new ArrayList<EventLoop>();
        for (var executor : group) loops.add((EventLoop) executor);
        if (loops.isEmpty()) throw new IllegalArgumentException("Empty EventLoopGroup");
        EventLoop coordinator = loops.getFirst();
        Promise<VclServer> result = coordinator.newPromise();
        class Binding {
            final List<Channel> created = new ArrayList<>();
            InetSocketAddress endpoint = address;
            boolean stopping;
            void abort(Throwable failure) {
                if (stopping) return;
                stopping = true;
                closeAll(created, coordinator).addListener(closed -> {
                    if (!closed.isSuccess() && closed.cause() != failure) failure.addSuppressed(closed.cause());
                    result.tryFailure(failure);
                });
            }
            void next(int index) {
                if (result.isCancelled()) { abort(new java.util.concurrent.CancellationException()); return; }
                try {
                    EventLoop loop = loops.get(index);
                    ChannelHandler child = new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel channel) {
                            channel.pipeline().addLast(Objects.requireNonNull(childHandler.get(), "child handler"));
                        }
                    };
                    var future = new ServerBootstrap().group(loop, loop).channel(VclServerSocketChannel.class)
                            .option(ChannelOption.AUTO_READ, false).childHandler(child).bind(endpoint);
                    created.add(future.channel());
                    future.addListener(bound -> coordinator.execute(() -> {
                        if (stopping) { future.channel().close(); return; }
                        if (!bound.isSuccess()) { abort(bound.cause()); return; }
                        endpoint = (InetSocketAddress) future.channel().localAddress();
                        if (index + 1 < loops.size()) { next(index + 1); return; }
                        VclServer server = new VclServer(created, coordinator);
                        if (result.trySuccess(server)) {
                            for (var listener : created) listener.config().setAutoRead(true);
                        } else abort(new java.util.concurrent.CancellationException());
                    }));
                } catch (Throwable failure) { abort(failure); }
            }
        }
        var binding = new Binding();
        result.addListener(f -> { if (f.isCancelled()) binding.abort(new java.util.concurrent.CancellationException()); });
        coordinator.execute(() -> binding.next(0));
        return result;
    }
    private static Future<Void> closeAll(List<Channel> channels, EventLoop coordinator) {
        Promise<Void> result = coordinator.newPromise();
        if (channels.isEmpty()) { result.setSuccess(null); return result; }
        var remaining = new AtomicInteger(channels.size());
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        for (Channel channel : List.copyOf(channels)) {
            channel.close().addListener(closed -> {
                if (!closed.isSuccess()) failure.compareAndSet(null, closed.cause());
                if (remaining.decrementAndGet() == 0) {
                    if (failure.get() == null) result.trySuccess(null); else result.tryFailure(failure.get());
                }
            });
        }
        return result;
    }
}
