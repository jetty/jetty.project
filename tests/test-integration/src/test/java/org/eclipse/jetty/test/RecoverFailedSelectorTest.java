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

package org.eclipse.jetty.test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RecoverFailedSelectorTest
{
    private Server server;
    private ServerConnector connector;

    private void start(Function<Server, ServerConnector> consumer) throws Exception
    {
        server = new Server();
        connector = consumer.apply(server);
        server.addConnector(connector);
        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testSelectFailureBetweenReads() throws Exception
    {
        // There will be 3 calls to select(): one at start(),
        // one to accept, and one to set read interest.
        CountDownLatch selectLatch = new CountDownLatch(3);
        CountDownLatch failureLatch = new CountDownLatch(1);
        AtomicBoolean fail = new AtomicBoolean();
        start(server -> new ServerConnector(server, 1, 1)
        {
            @Override
            protected SelectorManager newSelectorManager(Executor executor, Scheduler scheduler, int selectors)
            {
                return new ServerConnectorManager(executor, scheduler, selectors)
                {
                    @Override
                    protected ManagedSelector newSelector(int id)
                    {
                        return new ManagedSelector(this, id)
                        {
                            @Override
                            protected int nioSelect(Selector selector, boolean now) throws IOException
                            {
                                selectLatch.countDown();
                                if (fail.getAndSet(false))
                                    throw new IOException("explicit select() failure");
                                return super.nioSelect(selector, now);
                            }

                            @Override
                            protected void handleSelectFailure(Selector selector, Throwable failure) throws IOException
                            {
                                super.handleSelectFailure(selector, failure);
                                failureLatch.countDown();
                            }
                        };
                    }
                };
            }
        });

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            assertTrue(selectLatch.await(5, TimeUnit.SECONDS));

            String request = "GET / HTTP/1.0\r\n\r\n";
            int split = request.length() / 2;
            ByteBuffer chunk1 = StandardCharsets.UTF_8.encode(request.substring(0, split));
            ByteBuffer chunk2 = StandardCharsets.UTF_8.encode(request.substring(split));

            // Wake up the selector and fail it.
            fail.set(true);
            client.write(chunk1);

            // Wait for the failure handling to be completed.
            assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

            // Write the rest of the request, the
            // server should be able to continue.
            client.write(chunk2);

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client));
            assertNotNull(response);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
        }
    }

    @Test
    public void testAcceptDuringSelectFailure() throws Exception
    {
        // There will be 3 calls to select(): one at start(),
        // one to accept, and one to set read interest.
        CountDownLatch selectLatch = new CountDownLatch(3);
        CountDownLatch failureLatch = new CountDownLatch(1);
        AtomicBoolean fail = new AtomicBoolean();
        AtomicReference<SocketChannel> socketRef = new AtomicReference<>();
        start(server -> new ServerConnector(server, 1, 1)
        {
            @Override
            protected SelectorManager newSelectorManager(Executor executor, Scheduler scheduler, int selectors)
            {
                return new ServerConnectorManager(executor, scheduler, selectors)
                {
                    @Override
                    protected ManagedSelector newSelector(int id)
                    {
                        return new ManagedSelector(this, id)
                        {
                            @Override
                            protected int nioSelect(Selector selector, boolean now) throws IOException
                            {
                                selectLatch.countDown();
                                if (fail.getAndSet(false))
                                    throw new IOException("explicit select() failure");
                                return super.nioSelect(selector, now);
                            }

                            @Override
                            protected void handleSelectFailure(Selector selector, Throwable failure) throws IOException
                            {
                                // Before handling the failure, connect with another socket.
                                SocketChannel socket = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort()));
                                socketRef.set(socket);
                                super.handleSelectFailure(selector, failure);
                                failureLatch.countDown();
                            }
                        };
                    }
                };
            }
        });

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            assertTrue(selectLatch.await(5, TimeUnit.SECONDS));

            String request = "GET / HTTP/1.0\r\n\r\n";
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(request);

            // Wake up the selector and fail it.
            fail.set(true);
            client.write(buffer);

            // Wait for the failure handling to be completed.
            assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client));
            assertNotNull(response);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

            // Verify that the newly created socket works well.
            SocketChannel socket = socketRef.get();
            buffer.flip();
            socket.write(buffer);
            response = HttpTester.parseResponse(HttpTester.from(socket));
            assertNotNull(response);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
        }
    }

    @Test
    public void testSelectFailureDuringEndPointCreation() throws Exception
    {
        // There will be 2 calls to select(): one at start(), one to accept.
        CountDownLatch selectLatch = new CountDownLatch(2);
        CountDownLatch failureLatch = new CountDownLatch(1);
        AtomicBoolean fail = new AtomicBoolean();
        CountDownLatch endPointLatch1 = new CountDownLatch(1);
        CountDownLatch endPointLatch2 = new CountDownLatch(1);
        start(server -> new ServerConnector(server, 1, 1)
        {
            @Override
            protected SelectorManager newSelectorManager(Executor executor, Scheduler scheduler, int selectors)
            {
                return new ServerConnectorManager(executor, scheduler, selectors)
                {
                    @Override
                    protected ManagedSelector newSelector(int id)
                    {
                        return new ManagedSelector(this, id)
                        {
                            @Override
                            protected int nioSelect(Selector selector, boolean now) throws IOException
                            {
                                selectLatch.countDown();
                                if (fail.getAndSet(false))
                                    throw new IOException("explicit select() failure");
                                return super.nioSelect(selector, now);
                            }

                            @Override
                            protected void handleSelectFailure(Selector selector, Throwable failure) throws IOException
                            {
                                super.handleSelectFailure(selector, failure);
                                failureLatch.countDown();
                            }
                        };
                    }

                    @Override
                    protected SocketChannelEndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException
                    {
                        try
                        {
                            SocketChannelEndPoint endPoint = super.newEndPoint(channel, selector, selectionKey);
                            endPointLatch1.countDown();
                            assertTrue(endPointLatch2.await(5, TimeUnit.SECONDS));
                            return endPoint;
                        }
                        catch (InterruptedException x)
                        {
                            throw new InterruptedIOException();
                        }
                    }
                };
            }
        });

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            assertTrue(selectLatch.await(5, TimeUnit.SECONDS));

            // Wait until the server EndPoint instance is created.
            assertTrue(endPointLatch1.await(5, TimeUnit.SECONDS));

            // Wake up the selector and fail it.
            fail.set(true);
            SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())).close();

            // Wait until the selector is replaced.
            assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

            // Continue the EndPoint creation.
            endPointLatch2.countDown();

            String request = "GET / HTTP/1.0\r\n\r\n";
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(request);
            client.write(buffer);

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client));
            assertNotNull(response);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
        }
    }

    @Test
    public void testSelectFailureDuringEndPointCreatedThenClosed() throws Exception
    {
        // There will be 2 calls to select(): one at start(), one to accept.
        CountDownLatch selectLatch = new CountDownLatch(2);
        CountDownLatch failureLatch = new CountDownLatch(1);
        AtomicBoolean fail = new AtomicBoolean();
        CountDownLatch connectionLatch1 = new CountDownLatch(1);
        CountDownLatch connectionLatch2 = new CountDownLatch(1);
        start(server -> new ServerConnector(server, 1, 1)
        {
            @Override
            protected SelectorManager newSelectorManager(Executor executor, Scheduler scheduler, int selectors)
            {
                return new ServerConnectorManager(executor, scheduler, selectors)
                {
                    @Override
                    protected ManagedSelector newSelector(int id)
                    {
                        return new ManagedSelector(this, id)
                        {
                            @Override
                            protected int nioSelect(Selector selector, boolean now) throws IOException
                            {
                                selectLatch.countDown();
                                if (fail.getAndSet(false))
                                    throw new IOException("explicit select() failure");
                                return super.nioSelect(selector, now);
                            }

                            @Override
                            protected void handleSelectFailure(Selector selector, Throwable failure) throws IOException
                            {
                                super.handleSelectFailure(selector, failure);
                                failureLatch.countDown();
                            }
                        };
                    }

                    @Override
                    public Connection newConnection(SelectableChannel channel, EndPoint endPoint, Object attachment) throws IOException
                    {
                        try
                        {
                            Connection connection = super.newConnection(channel, endPoint, attachment);
                            endPoint.close();
                            connectionLatch1.countDown();
                            assertTrue(connectionLatch2.await(5, TimeUnit.SECONDS));
                            return connection;
                        }
                        catch (InterruptedException e)
                        {
                            throw new InterruptedIOException();
                        }
                    }
                };
            }
        });

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            assertTrue(selectLatch.await(5, TimeUnit.SECONDS));

            // Wait until the server EndPoint is closed.
            assertTrue(connectionLatch1.await(5, TimeUnit.SECONDS));

            // Wake up the selector and fail it.
            fail.set(true);
            SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())).close();

            // Wait until the selector is replaced.
            assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

            // Continue the server processing.
            connectionLatch2.countDown();

            // The channel has been closed on the server.
            int read = client.read(ByteBuffer.allocate(1));
            assertTrue(read < 0);
        }
    }
}
