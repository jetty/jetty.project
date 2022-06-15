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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SslConnectionTest
{
    private static final Logger LOG = LoggerFactory.getLogger(SslConnectionTest.class);

    private static final int TIMEOUT = 1000000;
    private static ByteBufferPool __byteBufferPool = new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged());
    private static RetainableByteBufferPool __retainableByteBufferPool = new ArrayRetainableByteBufferPool();

    private final SslContextFactory _sslCtxFactory = new SslContextFactory.Server();
    protected volatile EndPoint _lastEndp;
    private volatile boolean _testFill = true;
    private volatile boolean _onXWriteThenShutdown = false;

    private volatile FutureCallback _writeCallback;
    protected ServerSocketChannel _connector;
    final AtomicInteger _dispatches = new AtomicInteger();
    protected QueuedThreadPool _threadPool = new QueuedThreadPool()
    {
        @Override
        public void execute(Runnable job)
        {
            _dispatches.incrementAndGet();
            super.execute(job);
        }
    };
    protected Scheduler _scheduler = new TimerScheduler();
    protected SelectorManager _manager = new SelectorManager(_threadPool, _scheduler)
    {
        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment)
        {
            SSLEngine engine = _sslCtxFactory.newSSLEngine();
            engine.setUseClientMode(false);
            SslConnection sslConnection = new SslConnection(__retainableByteBufferPool, __byteBufferPool, getExecutor(), endpoint, engine);
            sslConnection.setRenegotiationAllowed(_sslCtxFactory.isRenegotiationAllowed());
            sslConnection.setRenegotiationLimit(_sslCtxFactory.getRenegotiationLimit());
            Connection appConnection = new TestConnection(sslConnection.getDecryptedEndPoint());
            sslConnection.getDecryptedEndPoint().setConnection(appConnection);
            return sslConnection;
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey)
        {
            SocketChannelEndPoint endp = new TestEP(channel, selector, selectionKey, getScheduler());
            endp.setIdleTimeout(TIMEOUT);
            _lastEndp = endp;
            return endp;
        }
    };

    static final AtomicInteger __startBlocking = new AtomicInteger();
    static final AtomicInteger __blockFor = new AtomicInteger();
    static final AtomicBoolean __onIncompleteFlush = new AtomicBoolean();

    private static class TestEP extends SocketChannelEndPoint
    {
        public TestEP(SelectableChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
        {
            super((SocketChannel)channel, selector, key, scheduler);
        }

        @Override
        protected void onIncompleteFlush()
        {
            __onIncompleteFlush.set(true);
        }

        @Override
        public boolean flush(ByteBuffer... buffers) throws IOException
        {
            __onIncompleteFlush.set(false);
            if (__startBlocking.get() == 0 || __startBlocking.decrementAndGet() == 0)
            {
                if (__blockFor.get() > 0 && __blockFor.getAndDecrement() > 0)
                {
                    return false;
                }
            }
            return super.flush(buffers);
        }
    }

    @BeforeEach
    public void initSSL() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore.p12");
        _sslCtxFactory.setKeyStorePath(keystore.getAbsolutePath());
        _sslCtxFactory.setKeyStorePassword("storepwd");
        _sslCtxFactory.setRenegotiationAllowed(true);
        _sslCtxFactory.setRenegotiationLimit(-1);
        startManager();
    }

    public void startManager() throws Exception
    {
        _testFill = true;
        _writeCallback = null;
        _lastEndp = null;
        _connector = ServerSocketChannel.open();
        _connector.socket().bind(null);
        _threadPool.start();
        _scheduler.start();
        _manager.start();
    }

    private void startSSL() throws Exception
    {
        _sslCtxFactory.start();
    }

    @AfterEach
    public void stopSSL() throws Exception
    {
        stopManager();
        _sslCtxFactory.stop();
    }

    private void stopManager() throws Exception
    {
        if (_lastEndp != null && _lastEndp.isOpen())
            _lastEndp.close();
        _manager.stop();
        _scheduler.stop();
        _threadPool.stop();
        _connector.close();
    }

    public class TestConnection extends AbstractConnection
    {
        ByteBuffer _in = BufferUtil.allocate(8 * 1024);

        public TestConnection(EndPoint endp)
        {
            super(endp, _threadPool);
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            if (_testFill)
                fillInterested();
            else
            {
                getExecutor().execute(() -> getEndPoint().write(_writeCallback, BufferUtil.toBuffer("Hello Client")));
            }
        }

        @Override
        public void onClose(Throwable cause)
        {
            super.onClose(cause);
        }

        @Override
        public void onFillable()
        {
            EndPoint endp = getEndPoint();
            try
            {
                boolean progress = true;
                while (progress)
                {
                    progress = false;

                    // Fill the input buffer with everything available
                    int filled = endp.fill(_in);
                    while (filled > 0)
                    {
                        progress = true;
                        filled = endp.fill(_in);
                    }

                    boolean shutdown = _onXWriteThenShutdown && BufferUtil.toString(_in).contains("X");

                    // Write everything
                    int l = _in.remaining();
                    if (l > 0)
                    {
                        FutureCallback blockingWrite = new FutureCallback();

                        endp.write(blockingWrite, _in);
                        blockingWrite.get();
                        if (shutdown)
                            endp.shutdownOutput();
                    }

                    // are we done?
                    if (endp.isInputShutdown() || shutdown)
                        endp.shutdownOutput();
                }
            }
            catch (InterruptedException | EofException e)
            {
                LOG.trace("IGNORED", e);
            }
            catch (Exception e)
            {
                LOG.warn("During onFillable", e);
            }
            finally
            {
                if (endp.isOpen())
                    fillInterested();
            }
        }
    }

    protected SSLSocket newClient() throws IOException
    {
        SSLSocket socket = _sslCtxFactory.newSslSocket();
        socket.connect(_connector.socket().getLocalSocketAddress());
        return socket;
    }

    @Test
    public void testHelloWorld() throws Exception
    {
        startSSL();
        try (Socket client = newClient())
        {
            client.setSoTimeout(TIMEOUT);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                client.getOutputStream().write("Hello".getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[1024];
                int len = client.getInputStream().read(buffer);
                assertEquals(5, len);
                assertEquals("Hello", new String(buffer, 0, len, StandardCharsets.UTF_8));

                _dispatches.set(0);
                client.getOutputStream().write("World".getBytes(StandardCharsets.UTF_8));
                len = 5;
                while (len > 0)
                {
                    len -= client.getInputStream().read(buffer);
                }
            }
        }
    }

    @Test
    public void testRenegotiate() throws Exception
    {
        startSSL();
        try (SSLSocket client = newClient())
        {
            client.setSoTimeout(TIMEOUT);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                client.getOutputStream().write("Hello".getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[1024];
                int len = client.getInputStream().read(buffer);
                assertEquals(5, len);
                assertEquals("Hello", new String(buffer, 0, len, StandardCharsets.UTF_8));

                client.startHandshake();

                client.getOutputStream().write("World".getBytes(StandardCharsets.UTF_8));
                len = client.getInputStream().read(buffer);
                assertEquals(5, len);
                assertEquals("World", new String(buffer, 0, len, StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    public void testRenegotiateNotAllowed() throws Exception
    {
        // TLS 1.3 and beyond do not support renegotiation.
        _sslCtxFactory.setIncludeProtocols("TLSv1.2");
        _sslCtxFactory.setRenegotiationAllowed(false);
        startSSL();
        try (SSLSocket client = newClient())
        {
            client.setSoTimeout(TIMEOUT);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                client.getOutputStream().write("Hello".getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[1024];
                int len = client.getInputStream().read(buffer);
                assertEquals(5, len);
                assertEquals("Hello", new String(buffer, 0, len, StandardCharsets.UTF_8));

                // Try to renegotiate, must fail.
                client.startHandshake();

                client.getOutputStream().write("World".getBytes(StandardCharsets.UTF_8));
                assertThrows(SSLException.class, () -> client.getInputStream().read(buffer));
            }
        }
    }

    @Test
    public void testRenegotiateLimit() throws Exception
    {
        // TLS 1.3 and beyond do not support renegotiation.
        _sslCtxFactory.setIncludeProtocols("TLSv1.2");
        _sslCtxFactory.setRenegotiationAllowed(true);
        _sslCtxFactory.setRenegotiationLimit(2);
        startSSL();
        try (SSLSocket client = newClient())
        {
            client.setSoTimeout(TIMEOUT);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                client.getOutputStream().write("Good".getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[1024];
                int len = client.getInputStream().read(buffer);
                assertEquals(4, len);
                assertEquals("Good", new String(buffer, 0, len, StandardCharsets.UTF_8));

                client.startHandshake();

                client.getOutputStream().write("Bye".getBytes(StandardCharsets.UTF_8));
                len = client.getInputStream().read(buffer);
                assertEquals(3, len);
                assertEquals("Bye", new String(buffer, 0, len, StandardCharsets.UTF_8));

                client.startHandshake();

                client.getOutputStream().write("Cruel".getBytes(StandardCharsets.UTF_8));
                len = client.getInputStream().read(buffer);
                assertEquals(5, len);
                assertEquals("Cruel", new String(buffer, 0, len, StandardCharsets.UTF_8));

                client.startHandshake();

                client.getOutputStream().write("World".getBytes(StandardCharsets.UTF_8));
                assertThrows(SSLException.class, () -> client.getInputStream().read(buffer));
            }
        }
    }

    @Test
    public void testWriteOnConnect() throws Exception
    {
        _testFill = false;
        _writeCallback = new FutureCallback();
        startSSL();
        try (SSLSocket client = newClient())
        {
            client.setSoTimeout(TIMEOUT);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                // The server side will write something, and in order
                // to proceed with the initial TLS handshake we need
                // to start reading before waiting for the callback.

                byte[] buffer = new byte[1024];
                int len = client.getInputStream().read(buffer);
                assertEquals("Hello Client", new String(buffer, 0, len, StandardCharsets.UTF_8));

                assertNull(_writeCallback.get(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void testBlockedWrite() throws Exception
    {
        startSSL();
        try (SSLSocket client = newClient())
        {
            client.setSoTimeout(5000);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                client.getOutputStream().write("Hello".getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[1024];
                int len = client.getInputStream().read(buffer);
                assertEquals("Hello", new String(buffer, 0, len, StandardCharsets.UTF_8));

                __startBlocking.set(0);
                __blockFor.set(2);
                _dispatches.set(0);
                client.getOutputStream().write("World".getBytes(StandardCharsets.UTF_8));

                try
                {
                    client.setSoTimeout(500);
                    client.getInputStream().read(buffer);
                    throw new IllegalStateException();
                }
                catch (SocketTimeoutException e)
                {
                    // no op
                }

                assertTrue(__onIncompleteFlush.get());
                ((TestEP)_lastEndp).getWriteFlusher().completeWrite();

                len = client.getInputStream().read(buffer);
                assertEquals("World", new String(buffer, 0, len, StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    public void testBlockedClose() throws Exception
    {
        startSSL();
        try (SSLSocket client = newClient())
        {
            client.setSoTimeout(5000);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                client.getOutputStream().write("Short".getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[1024];
                int len = client.getInputStream().read(buffer);
                assertEquals("Short", new String(buffer, 0, len, StandardCharsets.UTF_8));

                _onXWriteThenShutdown = true;
                __startBlocking.set(2); // block on the close handshake flush
                __blockFor.set(Integer.MAX_VALUE); // > retry loops in SslConnection + 1
                client.getOutputStream().write("This is a much longer example with X".getBytes(StandardCharsets.UTF_8));
                len = client.getInputStream().read(buffer);
                assertEquals("This is a much longer example with X", new String(buffer, 0, len, StandardCharsets.UTF_8));

                try
                {
                    client.setSoTimeout(500);
                    client.getInputStream().read(buffer);
                    throw new IllegalStateException();
                }
                catch (SocketTimeoutException e)
                {
                    // no op
                }

                __blockFor.set(0);
                assertTrue(__onIncompleteFlush.get());
                ((TestEP)_lastEndp).getWriteFlusher().completeWrite();
                len = client.getInputStream().read(buffer);
                assertThat(len, is(-1));
            }
        }
    }

    @Test
    public void testManyLines() throws Exception
    {
        startSSL();
        try (Socket client = newClient())
        {
            client.setSoTimeout(10000);
            try (SocketChannel server = _connector.accept())
            {
                server.configureBlocking(false);
                _manager.accept(server);

                final int LINES = 20;
                final CountDownLatch count = new CountDownLatch(LINES);

                new Thread(() ->
                {
                    try
                    {
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                        while (count.getCount() > 0)
                        {
                            String line = in.readLine();
                            if (line == null)
                                break;
                            count.countDown();
                        }
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                }).start();

                for (int i = 0; i < LINES; i++)
                {
                    client.getOutputStream().write(("HelloWorld " + i + "\n").getBytes(StandardCharsets.UTF_8));
                    if (i % 1000 == 0)
                    {
                        client.getOutputStream().flush();
                        Thread.sleep(10);
                    }
                }

                assertTrue(count.await(20, TimeUnit.SECONDS));
            }
        }
    }
}
