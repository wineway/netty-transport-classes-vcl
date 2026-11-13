package dev.vcl.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.util.*;

/** Each listener uses the same EventLoop for accept and child I/O; no VCL session migration. */
public final class VclServerBootstrap {
    private final List<EventLoop> loops;
    private final Map<ChannelOption<?>, Object> options = new HashMap<>(), childOptions = new HashMap<>();
    private ChannelHandler childHandler;

    public VclServerBootstrap(EventLoopGroup group) {
        Objects.requireNonNull(group, "group");
        List<EventLoop> found = new ArrayList<>();
        for (io.netty.util.concurrent.EventExecutor executor : group) {
            if (!(executor instanceof IoEventLoop loop) || !loop.isIoType(VclIoHandler.class)) {
                throw new IllegalArgumentException("Requires a fixed VCL IoEventLoopGroup");
            }
            found.add(loop);
        }
        if (found.isEmpty()) throw new IllegalArgumentException("No VCL EventLoops");
        loops = List.copyOf(found);
    }
    public VclServerBootstrap childHandler(ChannelHandler handler) { childHandler = Objects.requireNonNull(handler); return this; }
    public <T> VclServerBootstrap option(ChannelOption<T> option, T value) { options.put(option, value); return this; }
    public <T> VclServerBootstrap childOption(ChannelOption<T> option, T value) { childOptions.put(option, value); return this; }

    public Future<VclServerBinding> bind(InetSocketAddress address) {
        AbstractVclChannel.ipv4(address);
        if (address.getPort() == 0) throw new IllegalArgumentException("Shared listeners require an explicit nonzero port");
        ChannelHandler handler = Objects.requireNonNull(childHandler, "childHandler");
        Map<ChannelOption<?>, Object> opts = Map.copyOf(options), children = Map.copyOf(childOptions);
        EventLoop coordinator = loops.getFirst();
        Promise<VclServerBinding> result = coordinator.newPromise();
        List<Channel> bound = new ArrayList<>();
        class Binder {
            void next(int index) {
                if (result.isCancelled()) { rollback(null); return; }
                if (index == loops.size()) {
                    VclServerBinding binding = new VclServerBinding(bound, coordinator);
                    if (!result.trySuccess(binding)) binding.closeAsync();
                    return;
                }
                EventLoop loop = loops.get(index);
                ServerBootstrap bootstrap = new ServerBootstrap().group(loop, loop)
                        .channel(VclServerSocketChannel.class).childHandler(handler);
                opts.forEach((option, value) -> setOption(bootstrap, option, value, false));
                children.forEach((option, value) -> setOption(bootstrap, option, value, true));
                bootstrap.bind(address).addListener((ChannelFutureListener) future -> coordinator.execute(() -> {
                    if (future.isSuccess()) {
                        bound.add(future.channel());
                        try { next(index + 1); } catch (Exception e) { rollback(e); }
                    }
                    else {
                        // Failed binds can still own a registered session until close completes.
                        bound.add(future.channel()); rollback(future.cause());
                    }
                }));
            }
            void rollback(Throwable cause) {
                new VclServerBinding(bound, coordinator).closeAsync().addListener(closed -> {
                    if (cause != null) {
                        if (!closed.isSuccess()) cause.addSuppressed(closed.cause());
                        result.tryFailure(cause);
                    }
                });
            }
        }
        try { coordinator.execute(() -> {
            try { new Binder().next(0); }
            catch (Exception e) { new Binder().rollback(e); }
        }); } catch (RuntimeException e) { result.tryFailure(e); }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> void setOption(ServerBootstrap bootstrap, ChannelOption<T> option, Object value, boolean child) {
        if (child) bootstrap.childOption(option, (T) value); else bootstrap.option(option, (T) value);
    }
}
