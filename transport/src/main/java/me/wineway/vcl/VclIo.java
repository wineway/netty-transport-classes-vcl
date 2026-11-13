/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/** Worker-confined transport boundary; test implementations never load libvppcom. */
interface VclIo extends AutoCloseable {
    int READ = 1, WRITE = 4, ERROR = 8, HUP = 16, RDHUP = 8192;
    int AGAIN = -11, IN_PROGRESS = -115;
    record Accepted(int session, InetSocketAddress peer) { }
    record Received(int bytes, InetSocketAddress sender) { }
    int create(boolean datagram) throws Exception;
    void closeSession(int session) throws Exception;
    void bind(int session, InetSocketAddress address) throws Exception;
    void listen(int session, int backlog) throws Exception;
    Accepted accept(int session) throws Exception;
    boolean connect(int session, InetSocketAddress remote) throws Exception;
    boolean finishConnect(int session) throws Exception;
    InetSocketAddress localAddress(int session) throws Exception;
    int read(int session, ByteBuffer destination) throws Exception;
    int write(int session, ByteBuffer source) throws Exception;
    Received receive(int session, ByteBuffer destination) throws Exception;
    int send(int session, ByteBuffer source, InetSocketAddress recipient) throws Exception;
    void shutdown(int session, int how) throws Exception;
    void interest(int session, long token, int flags, boolean add) throws Exception;
    void remove(int session) throws Exception;
    int poll(long[] tokens, int[] flags) throws Exception;
    boolean await(long nanos) throws Exception;
    boolean alive() throws Exception;
    void wakeup();
    /** Stop session I/O before failure callbacks release channels. */
    default void failed(Throwable failure) { }
    @Override void close() throws Exception;
}
