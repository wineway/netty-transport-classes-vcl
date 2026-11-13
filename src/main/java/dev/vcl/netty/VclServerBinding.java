package dev.vcl.netty;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.PromiseCombiner;
import java.util.List;

/** All listeners for one shared VPP endpoint. EventLoopGroup ownership stays with the caller. */
public final class VclServerBinding {
    private final List<Channel> listeners;
    private final EventLoop executor;

    VclServerBinding(List<Channel> listeners, EventLoop executor) {
        this.listeners = List.copyOf(listeners); this.executor = executor;
    }
    public List<Channel> listeners() { return listeners; }

    public Future<Void> closeAsync() {
        Promise<Void> result = executor.newPromise();
        Runnable close = () -> {
            PromiseCombiner combiner = new PromiseCombiner(executor);
            for (Channel listener : listeners) combiner.add((Future<?>) listener.close());
            combiner.finish(result);
        };
        if (executor.inEventLoop()) close.run(); else {
            try { executor.execute(close); } catch (RuntimeException e) { result.tryFailure(e); }
        }
        return result;
    }
}
