//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("Duplicates")
public class SocketChannelEndPointTest
{
    private static final Logger LOG = LoggerFactory.getLogger(SocketChannelEndPoint.class);

    public interface Scenario
    {
        Socket newClient(ServerSocketChannel connector) throws IOException;

        Connection newConnection(SelectableChannel channel, EndPoint endPoint, Executor executor, AtomicInteger blockAt, AtomicInteger writeCount);

        boolean supportsHalfCloses();
    }

    public static Stream<Arguments> scenarios() throws Exception
    {
        NormalScenario normalScenario = new NormalScenario();
        SslScenario sslScenario = new SslScenario(normalScenario);

        return Stream.of(normalScenario, sslScenario).map(Arguments::of);
    }

    private Scenario _scenario;

    private ServerSocketChannel _connector;
    private QueuedThreadPool _threadPool;
    private Scheduler _scheduler;
    private SelectorManager _manager;
    private volatile EndPoint _lastEndPoint;
    private CountDownLatch _lastEndPointLatch;

    // Must be volatile or the test may fail spuriously
    private final AtomicInteger _blockAt = new AtomicInteger(0);
    private final AtomicInteger _writeCount = new AtomicInteger(1);

    public void init(Scenario scenario) throws Exception
    {
        _scenario = scenario;
        _threadPool = new QueuedThreadPool();
        _scheduler = new TimerScheduler();
        _manager = new ScenarioSelectorManager(_threadPool, _scheduler);

        _lastEndPointLatch = new CountDownLatch(1);
        _connector = ServerSocketChannel.open();
        _connector.socket().bind(null);
        _scheduler.start();
        _threadPool.start();
        _manager.start();
    }

    @AfterEach
    public void stopManager() throws Exception
    {
        _scheduler.stop();
        _manager.stop();
        _threadPool.stop();
        _connector.close();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testEcho(Scenario scenario) throws Exception
    {
        init(scenario);
        try (Socket client = _scenario.newClient(_connector))
        {
            client.setSoTimeout(60000);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                // Write client to server
                client.getOutputStream().write("HelloWorld".getBytes(StandardCharsets.UTF_8));

                // Verify echo server to client
                for (char c : "HelloWorld".toCharArray())
                {
                    int b = client.getInputStream().read();
                    assertTrue(b > 0);
                    assertEquals(c, (char)b);
                }

                // wait for read timeout
                client.setSoTimeout(500);
                long start = NanoTime.now();
                assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());
                assertThat(NanoTime.millisSince(start), greaterThanOrEqualTo(400L));

                // write then shutdown
                client.getOutputStream().write("Goodbye Cruel TLS".getBytes(StandardCharsets.UTF_8));

                // Verify echo server to client
                for (char c : "Goodbye Cruel TLS".toCharArray())
                {
                    int b = client.getInputStream().read();
                    assertThat("expect valid char integer", b, greaterThan(0));
                    assertEquals(c, (char)b, "expect characters to be same");
                }
                client.close();

                for (int i = 0; i < 10; ++i)
                {
                    if (server.isOpen())
                        Thread.sleep(10);
                    else
                        break;
                }
                assertFalse(server.isOpen());
            }
        }
    }

    @Test
    public void testShutdown() throws Exception
    {
        // We don't test SSL as JVM SSL doesn't support half-close
        init(new NormalScenario());

        try (Socket client = _scenario.newClient(_connector))
        {
            client.setSoTimeout(500);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                // Write client to server
                client.getOutputStream().write("HelloWorld".getBytes(StandardCharsets.UTF_8));

                // Verify echo server to client
                for (char c : "HelloWorld".toCharArray())
                {
                    int b = client.getInputStream().read();
                    assertTrue(b > 0);
                    assertEquals(c, (char)b);
                }

                // wait for read timeout
                long start = NanoTime.now();
                assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());
                assertThat(NanoTime.millisSince(start), greaterThanOrEqualTo(400L));

                // write then shutdown
                client.getOutputStream().write("Goodbye Cruel TLS".getBytes(StandardCharsets.UTF_8));
                client.shutdownOutput();

                // Verify echo server to client
                for (char c : "Goodbye Cruel TLS".toCharArray())
                {
                    int b = client.getInputStream().read();
                    assertTrue(b > 0);
                    assertEquals(c, (char)b);
                }

                // Read close
                assertEquals(-1, client.getInputStream().read());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testReadBlocked(Scenario scenario) throws Exception
    {
        init(scenario);
        try (Socket client = _scenario.newClient(_connector);
             SocketChannel server = _connector.accept())
        {
            server.configureBlocking(false);
            _manager.accept(server);

            OutputStream clientOutputStream = client.getOutputStream();
            InputStream clientInputStream = client.getInputStream();

            int specifiedTimeout = 1000;
            client.setSoTimeout(specifiedTimeout);

            // Write 8 and cause block waiting for 10
            _blockAt.set(10);
            clientOutputStream.write("12345678".getBytes(StandardCharsets.UTF_8));
            clientOutputStream.flush();

            assertTrue(_lastEndPointLatch.await(1, TimeUnit.SECONDS));
            _lastEndPoint.setIdleTimeout(10 * specifiedTimeout);
            Thread.sleep((11 * specifiedTimeout) / 10);

            long start = NanoTime.now();
            assertThrows(SocketTimeoutException.class, clientInputStream::read);
            assertThat(NanoTime.millisSince(start), greaterThanOrEqualTo(3L * specifiedTimeout / 4));

            // write remaining characters
            clientOutputStream.write("90ABCDEF".getBytes(StandardCharsets.UTF_8));
            clientOutputStream.flush();

            // Verify echo server to client
            for (char c : "1234567890ABCDEF".toCharArray())
            {
                int b = clientInputStream.read();
                assertTrue(b > 0);
                assertEquals(c, (char)b);
            }
        }
    }

    @Tag("stress")
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testStress(Scenario scenario) throws Exception
    {
        init(scenario);
        try (Socket client = _scenario.newClient(_connector))
        {
            client.setSoTimeout(30000);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);
                final int writes = 200000;

                final byte[] bytes = "HelloWorld-".getBytes(StandardCharsets.UTF_8);
                byte[] count = "0\n".getBytes(StandardCharsets.UTF_8);
                BufferedOutputStream out = new BufferedOutputStream(client.getOutputStream());
                final CountDownLatch latch = new CountDownLatch(writes);
                final InputStream in = new BufferedInputStream(client.getInputStream());
                final long start = NanoTime.now();
                out.write(bytes);
                out.write(count);
                out.flush();

                assertTrue(_lastEndPointLatch.await(1, TimeUnit.SECONDS));
                _lastEndPoint.setIdleTimeout(5000);

                new Thread(() ->
                {
                    Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
                    long last = -1;
                    int count1 = -1;
                    try
                    {
                        while (latch.getCount() > 0)
                        {
                            // Verify echo server to client
                            for (byte b0 : bytes)
                            {
                                int b = in.read();
                                assertThat(b, greaterThan(0));
                                assertEquals(0xff & b0, b);
                            }

                            count1 = 0;
                            int b = in.read();
                            while (b > 0 && b != '\n')
                            {
                                count1 = count1 * 10 + (b - '0');
                                b = in.read();
                            }
                            last = NanoTime.now();

                            //if (latch.getCount()%1000==0)
                            //    System.out.println(writes-latch.getCount());

                            latch.countDown();
                        }
                    }
                    catch (Throwable e)
                    {
                        long now = NanoTime.now();
                        System.err.println("count=" + count1);
                        System.err.println("latch=" + latch.getCount());
                        System.err.println("time=" + NanoTime.millisElapsed(start, now));
                        System.err.println("last=" + NanoTime.millisElapsed(last, now));
                        System.err.println("endp=" + _lastEndPoint);
                        System.err.println("conn=" + _lastEndPoint.getConnection());

                        e.printStackTrace();
                    }
                }).start();

                // Write client to server
                for (int i = 1; i < writes; i++)
                {
                    out.write(bytes);
                    out.write(Integer.toString(i).getBytes(StandardCharsets.ISO_8859_1));
                    out.write('\n');
                    if (i % 1000 == 0)
                    {
                        //System.err.println(i+"/"+writes);
                        out.flush();
                    }
                    Thread.yield();
                }
                out.flush();

                long last = latch.getCount();
                while (!latch.await(5, TimeUnit.SECONDS))
                {
                    //System.err.println(latch.getCount());
                    if (latch.getCount() == last)
                        fail("Latch failure");
                    last = latch.getCount();
                }

                assertEquals(0, latch.getCount());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testWriteBlocked(Scenario scenario) throws Exception
    {
        init(scenario);
        try (Socket client = _scenario.newClient(_connector))
        {
            client.setSoTimeout(10000);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                // Write client to server
                _writeCount.set(10000);
                String data = "Now is the time for all good men to come to the aid of the party";
                client.getOutputStream().write(data.getBytes(StandardCharsets.UTF_8));
                BufferedInputStream in = new BufferedInputStream(client.getInputStream());

                int byteNum = 0;
                try
                {
                    for (int i = 0; i < _writeCount.get(); i++)
                    {
                        if (i % 1000 == 0)
                            TimeUnit.MILLISECONDS.sleep(200);

                        // Verify echo server to client
                        for (int j = 0; j < data.length(); j++)
                        {
                            char c = data.charAt(j);
                            int b = in.read();
                            byteNum++;
                            assertTrue(b > 0);
                            assertEquals(c, (char)b, "test-" + i + "/" + j);
                        }

                        if (i == 0)
                            _lastEndPoint.setIdleTimeout(60000);
                    }
                }
                catch (SocketTimeoutException e)
                {
                    System.err.println("SelectorManager.dump() = " + _manager.dump());
                    LOG.warn("Server: " + server);
                    LOG.warn("Error reading byte #" + byteNum, e);
                    throw e;
                }

                client.close();

                for (int i = 0; i < 10; ++i)
                {
                    if (server.isOpen())
                        Thread.sleep(10);
                    else
                        break;
                }
                assertFalse(server.isOpen());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    @Tag("Unstable")
    @Disabled
    public void testRejectedExecution(Scenario scenario) throws Exception
    {
        init(scenario);
        _manager.stop();
        _threadPool.stop();

        final CountDownLatch latch = new CountDownLatch(1);

        BlockingQueue<Runnable> q = new ArrayBlockingQueue<>(4);
        _threadPool = new QueuedThreadPool(4, 4, 60000, q);
        _manager = new SelectorManager(_threadPool, _scheduler, 1)
        {

            @Override
            protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey)
            {
                SocketChannelEndPoint endPoint = new SocketChannelEndPoint((SocketChannel)channel, selector, selectionKey, getScheduler());
                _lastEndPoint = endPoint;
                _lastEndPointLatch.countDown();
                return endPoint;
            }

            @Override
            public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment)
            {
                return new TestConnection(endpoint, latch, getExecutor(), _blockAt, _writeCount);
            }
        };

        _threadPool.start();
        _manager.start();

        AtomicInteger timeout = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        AtomicInteger echoed = new AtomicInteger();

        CountDownLatch closed = new CountDownLatch(20);
        for (int i = 0; i < 20; i++)
        {
            new Thread(() ->
            {
                try (Socket client = _scenario.newClient(_connector))
                {
                    client.setSoTimeout(5000);
                    try (SocketChannel server = _connector.accept())
                    {
                        server.configureBlocking(false);
                        _manager.accept(server);

                        // Write client to server
                        client.getOutputStream().write("HelloWorld".getBytes(StandardCharsets.UTF_8));
                        client.getOutputStream().flush();
                        client.shutdownOutput();

                        // Verify echo server to client
                        for (char c : "HelloWorld".toCharArray())
                        {
                            int b = client.getInputStream().read();
                            assertTrue(b > 0);
                            assertEquals(c, (char)b);
                        }
                        assertEquals(-1, client.getInputStream().read());
                        echoed.incrementAndGet();
                    }
                }
                catch (SocketTimeoutException x)
                {
                    x.printStackTrace();
                    timeout.incrementAndGet();
                }
                catch (Throwable x)
                {
                    rejections.incrementAndGet();
                }
                finally
                {
                    closed.countDown();
                }
            }).start();
        }

        // unblock the handling
        latch.countDown();

        // wait for all clients to complete or fail
        closed.await();

        // assert some clients must have been rejected
        assertThat(rejections.get(), Matchers.greaterThan(0));
        // but not all of them
        assertThat(rejections.get(), Matchers.lessThan(20));
        // none should have timed out
        assertThat(timeout.get(), Matchers.equalTo(0));
        // and the rest should have worked
        assertThat(echoed.get(), Matchers.equalTo(20 - rejections.get()));

        // and the selector is still working for new requests
        try (Socket client = _scenario.newClient(_connector))
        {
            client.setSoTimeout(5000);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                // Write client to server
                client.getOutputStream().write("HelloWorld".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                client.shutdownOutput();

                // Verify echo server to client
                for (char c : "HelloWorld".toCharArray())
                {
                    int b = client.getInputStream().read();
                    assertTrue(b > 0);
                    assertEquals(c, (char)b);
                }
                assertEquals(-1, client.getInputStream().read());
            }
        }
    }

    public class ScenarioSelectorManager extends SelectorManager
    {
        protected ScenarioSelectorManager(Executor executor, Scheduler scheduler)
        {
            super(executor, scheduler);
        }

        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key)
        {
            SocketChannelEndPoint endPoint = new SocketChannelEndPoint((SocketChannel)channel, selector, key, getScheduler());
            endPoint.setIdleTimeout(60000);
            _lastEndPoint = endPoint;
            _lastEndPointLatch.countDown();
            return endPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment)
        {
            return _scenario.newConnection(channel, endpoint, getExecutor(), _blockAt, _writeCount);
        }
    }

    public static class NormalScenario implements Scenario
    {
        @Override
        public Socket newClient(ServerSocketChannel connector) throws IOException
        {
            return new Socket(connector.socket().getInetAddress(), connector.socket().getLocalPort());
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Executor executor, AtomicInteger blockAt, AtomicInteger writeCount)
        {
            return new TestConnection(endpoint, executor, blockAt, writeCount);
        }

        @Override
        public boolean supportsHalfCloses()
        {
            return true;
        }

        @Override
        public String toString()
        {
            return "normal";
        }
    }

    public static class SslScenario implements Scenario
    {
        private final NormalScenario _normalScenario;
        private final SslContextFactory _sslCtxFactory = new SslContextFactory.Server();
        private final ByteBufferPool _byteBufferPool = new MappedByteBufferPool();

        public SslScenario(NormalScenario normalScenario) throws Exception
        {
            _normalScenario = normalScenario;
            File keystore = MavenTestingUtils.getTestResourceFile("keystore.p12");
            _sslCtxFactory.setKeyStorePath(keystore.getAbsolutePath());
            _sslCtxFactory.setKeyStorePassword("storepwd");
            _sslCtxFactory.start();
        }

        @Override
        public Socket newClient(ServerSocketChannel connector) throws IOException
        {
            SSLSocket socket = _sslCtxFactory.newSslSocket();
            socket.connect(connector.socket().getLocalSocketAddress());
            return socket;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Executor executor, AtomicInteger blockAt, AtomicInteger writeCount)
        {
            SSLEngine engine = _sslCtxFactory.newSSLEngine();
            engine.setUseClientMode(false);
            SslConnection sslConnection = new SslConnection(_byteBufferPool, executor, endpoint, engine);
            sslConnection.setRenegotiationAllowed(_sslCtxFactory.isRenegotiationAllowed());
            sslConnection.setRenegotiationLimit(_sslCtxFactory.getRenegotiationLimit());
            Connection appConnection = _normalScenario.newConnection(channel, sslConnection.getDecryptedEndPoint(), executor, blockAt, writeCount);
            sslConnection.getDecryptedEndPoint().setConnection(appConnection);
            return sslConnection;
        }

        @Override
        public boolean supportsHalfCloses()
        {
            return false;
        }

        @Override
        public String toString()
        {
            return "ssl";
        }
    }

    @SuppressWarnings("Duplicates")
    public static class TestConnection extends AbstractConnection
    {
        private static final Logger LOG = LoggerFactory.getLogger(TestConnection.class);

        volatile FutureCallback _blockingRead;
        final AtomicInteger _blockAt;
        final AtomicInteger _writeCount;
        // volatile int _blockAt = 0;
        ByteBuffer _in = BufferUtil.allocate(32 * 1024);
        ByteBuffer _out = BufferUtil.allocate(32 * 1024);
        final CountDownLatch _latch;

        public TestConnection(EndPoint endp, Executor executor, AtomicInteger blockAt, AtomicInteger writeCount)
        {
            super(endp, executor);
            _latch = null;
            this._blockAt = blockAt;
            this._writeCount = writeCount;
        }

        public TestConnection(EndPoint endp, CountDownLatch latch, Executor executor, AtomicInteger blockAt, AtomicInteger writeCount)
        {
            super(endp, executor);
            _latch = latch;
            this._blockAt = blockAt;
            this._writeCount = writeCount;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            fillInterested();
        }

        @Override
        public void onFillInterestedFailed(Throwable cause)
        {
            Callback blocking = _blockingRead;
            if (blocking != null)
            {
                _blockingRead = null;
                blocking.failed(cause);
                return;
            }
            super.onFillInterestedFailed(cause);
        }

        @Override
        public void onFillable()
        {
            if (_latch != null)
            {
                try
                {
                    _latch.await();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }

            Callback blocking = _blockingRead;
            if (blocking != null)
            {
                _blockingRead = null;
                blocking.succeeded();
                return;
            }

            EndPoint endPoint = getEndPoint();
            try
            {
                boolean progress = true;
                while (progress)
                {
                    progress = false;

                    // Fill the input buffer with everything available
                    BufferUtil.compact(_in);
                    if (BufferUtil.isFull(_in))
                        throw new IllegalStateException("FULL " + BufferUtil.toDetailString(_in));
                    int filled = endPoint.fill(_in);
                    if (filled > 0)
                        progress = true;

                    // If the tests wants to block, then block
                    while (_blockAt.get() > 0 && endPoint.isOpen() && _in.remaining() < _blockAt.get())
                    {
                        FutureCallback future = _blockingRead = new FutureCallback();
                        fillInterested();
                        future.get();
                        filled = endPoint.fill(_in);
                        progress |= filled > 0;
                    }

                    // Copy to the out buffer
                    if (BufferUtil.hasContent(_in) && BufferUtil.append(_out, _in) > 0)
                        progress = true;

                    // Blocking writes
                    if (BufferUtil.hasContent(_out))
                    {
                        ByteBuffer out = _out.duplicate();
                        BufferUtil.clear(_out);
                        for (int i = 0; i < _writeCount.get(); i++)
                        {
                            FutureCallback blockingWrite = new FutureCallback();
                            endPoint.write(blockingWrite, out.asReadOnlyBuffer());
                            blockingWrite.get();
                        }
                        progress = true;
                    }

                    // are we done?
                    if (endPoint.isInputShutdown())
                        endPoint.shutdownOutput();
                }

                if (endPoint.isOpen())
                    fillInterested();
            }
            catch (ExecutionException e)
            {
                // Timeout does not close, so echo exception then shutdown
                try
                {
                    FutureCallback blockingWrite = new FutureCallback();
                    endPoint.write(blockingWrite, BufferUtil.toBuffer("EE: " + BufferUtil.toString(_in)));
                    blockingWrite.get();
                    endPoint.shutdownOutput();
                }
                catch (Exception e2)
                {
                    // e2.printStackTrace();
                }
            }
            catch (InterruptedException | EofException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Fill interrupted", e);
                else
                    LOG.info(e.getClass().getName());
            }
            catch (Exception e)
            {
                LOG.warn("Unable to fill from endpoint", e);
            }
        }
    }
}
