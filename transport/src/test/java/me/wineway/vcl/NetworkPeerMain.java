/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.bootstrap.*;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import io.netty.util.ReferenceCountUtil;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;

/** Separate peer JVM, attached to a second VPP for actual TCP/UDP protocol processing over memif. */
public final class NetworkPeerMain {
    public static void main(String[] args) throws Exception {
        Path ready = Path.of(args[0]);
        var stop = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            stop.countDown(); try { stopped.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        try (var transport = new RealTransportSupport()) {
            for (boolean ipv6 : new boolean[]{false, true}) {
                for (boolean half : new boolean[]{false, true}) {
                    EventLoop loop = transport.group.next();
                    new ServerBootstrap().group(loop, loop).channel(transport.serverClass)
                            .childOption(ChannelOption.ALLOW_HALF_CLOSURE, half)
                            .childHandler(new ChannelInitializer<Channel>() {
                                @Override protected void initChannel(Channel channel) {
                                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                            if (half) ReferenceCountUtil.release(msg); else ctx.writeAndFlush(msg);
                                        }
                                        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                                            if (half && event == ChannelInputShutdownEvent.INSTANCE) {
                                                ctx.writeAndFlush(ctx.alloc().buffer(1).writeByte(9));
                                            }
                                        }
                                        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { cause.printStackTrace(); ctx.close(); }
                                    });
                                }
                            }).bind(transport.address(ipv6, half ? 32102 : 32100)).sync();
                }
                new Bootstrap().group(transport.group).channel(transport.datagramClass)
                        .handler(new ChannelInboundHandlerAdapter() {
                            @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                DatagramPacket packet = (DatagramPacket) msg;
                                ctx.writeAndFlush(new DatagramPacket(packet.content().retain(), packet.sender())); packet.release();
                            }
                            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { cause.printStackTrace(); ctx.close(); }
                        }).bind(transport.address(ipv6, 32101)).sync();
            }
            EventLoop pausedLoop = transport.group.next();
            new ServerBootstrap().group(pausedLoop, pausedLoop).channel(transport.serverClass)
                    .childOption(ChannelOption.AUTO_READ, false)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel channel) {
                            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override public void channelActive(ChannelHandlerContext ctx) {
                                    ctx.executor().schedule(() -> ctx.channel().config().setAutoRead(true),
                                            500, java.util.concurrent.TimeUnit.MILLISECONDS);
                                }
                                @Override public void channelRead(ChannelHandlerContext ctx, Object msg) { ctx.writeAndFlush(msg); }
                                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
                            });
                        }
                    }).bind(transport.address(false, 32104)).sync();
            // Test-only metrics endpoint. Baselines are sampled outside measured client work.
            EventLoop metricsLoop = transport.group.next();
            new ServerBootstrap().group(metricsLoop, metricsLoop).channel(transport.serverClass)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel channel) {
                            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    ReferenceCountUtil.release(msg);
                                    ProcessMetrics metrics = ProcessMetrics.current();
                                    ctx.writeAndFlush(ctx.alloc().buffer(32).writeLong(metrics.cpuNanos())
                                            .writeLong(metrics.allocatedBytes()).writeLong(metrics.gcCount()).writeLong(metrics.gcMillis()))
                                            .addListener(ChannelFutureListener.CLOSE);
                                }
                                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
                            });
                        }
                    }).bind(transport.address(false, 32103)).sync();
            Files.writeString(ready, "ready\n");
            stop.await();
        } finally { stopped.countDown(); }
    }
}
