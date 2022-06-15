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

package org.eclipse.jetty.docs.programming.client;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

import static java.lang.System.Logger.Level.INFO;

@SuppressWarnings("unused")
public class ClientConnectorDocs
{
    public void simplest() throws Exception
    {
        // tag::simplest[]
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.start();
        // end::simplest[]
    }

    public void typical() throws Exception
    {
        // tag::typical[]
        // Create and configure the SslContextFactory.
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.addExcludeProtocols("TLSv1", "TLSv1.1");

        // Create and configure the thread pool.
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("client");

        // Create and configure the ClientConnector.
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactory);
        clientConnector.setExecutor(threadPool);
        clientConnector.start();
        // end::typical[]
    }

    public void advanced() throws Exception
    {
        // tag::advanced[]
        class CustomClientConnector extends ClientConnector
        {
            @Override
            protected SelectorManager newSelectorManager()
            {
                return new ClientSelectorManager(getExecutor(), getScheduler(), getSelectors())
                {
                    @Override
                    protected void endPointOpened(EndPoint endpoint)
                    {
                        System.getLogger("endpoint").log(INFO, "opened %s", endpoint);
                    }

                    @Override
                    protected void endPointClosed(EndPoint endpoint)
                    {
                        System.getLogger("endpoint").log(INFO, "closed %s", endpoint);
                    }
                };
            }
        }

        // Create and configure the thread pool.
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("client");

        // Create and configure the scheduler.
        Scheduler scheduler = new ScheduledExecutorScheduler("scheduler-client", false);

        // Create and configure the custom ClientConnector.
        CustomClientConnector clientConnector = new CustomClientConnector();
        clientConnector.setExecutor(threadPool);
        clientConnector.setScheduler(scheduler);
        clientConnector.start();
        // end::advanced[]
    }

    public void connect() throws Exception
    {
        // tag::connect[]
        class CustomConnection extends AbstractConnection
        {
            public CustomConnection(EndPoint endPoint, Executor executor)
            {
                super(endPoint, executor);
            }

            @Override
            public void onOpen()
            {
                super.onOpen();
                System.getLogger("connection").log(INFO, "Opened connection {0}", this);
            }

            @Override
            public void onFillable()
            {
            }
        }

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.start();

        String host = "serverHost";
        int port = 8080;
        SocketAddress address = new InetSocketAddress(host, port);

        // The ClientConnectionFactory that creates CustomConnection instances.
        ClientConnectionFactory connectionFactory = (endPoint, context) ->
        {
            System.getLogger("connection").log(INFO, "Creating connection for {0}", endPoint);
            return new CustomConnection(endPoint, clientConnector.getExecutor());
        };

        // The Promise to notify of connection creation success or failure.
        CompletableFuture<CustomConnection> connectionPromise = new Promise.Completable<>();

        // Populate the context with the mandatory keys to create and obtain connections.
        Map<String, Object> context = new HashMap<>();
        context.put(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY, connectionFactory);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, connectionPromise);
        clientConnector.connect(address, context);

        // Use the Connection when it's available.

        // Use it in a non-blocking way via CompletableFuture APIs.
        connectionPromise.whenComplete((connection, failure) ->
        {
            System.getLogger("connection").log(INFO, "Created connection for {0}", connection);
        });

        // Alternatively, you can block waiting for the connection (or a failure).
        // CustomConnection connection = connectionPromise.get();
        // end::connect[]
    }

    public void telnet() throws Exception
    {
        // tag::telnet[]
        class TelnetConnection extends AbstractConnection
        {
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            private Consumer<String> consumer;

            public TelnetConnection(EndPoint endPoint, Executor executor)
            {
                super(endPoint, executor);
            }

            @Override
            public void onOpen()
            {
                super.onOpen();

                // Declare interest for fill events.
                fillInterested();
            }

            @Override
            public void onFillable()
            {
                try
                {
                    ByteBuffer buffer = BufferUtil.allocate(1024);
                    while (true)
                    {
                        int filled = getEndPoint().fill(buffer);
                        if (filled > 0)
                        {
                            while (buffer.hasRemaining())
                            {
                                // Search for newline.
                                byte read = buffer.get();
                                if (read == '\n')
                                {
                                    // Notify the consumer of the line.
                                    consumer.accept(bytes.toString(StandardCharsets.UTF_8));
                                    bytes.reset();
                                }
                                else
                                {
                                    bytes.write(read);
                                }
                            }
                        }
                        else if (filled == 0)
                        {
                            // No more bytes to fill, declare
                            // again interest for fill events.
                            fillInterested();
                            return;
                        }
                        else
                        {
                            // The other peer closed the
                            // connection, close it back.
                            getEndPoint().close();
                            return;
                        }
                    }
                }
                catch (Exception x)
                {
                    getEndPoint().close(x);
                }
            }

            public void onLine(Consumer<String> consumer)
            {
                this.consumer = consumer;
            }

            public void writeLine(String line, Callback callback)
            {
                line = line + "\r\n";
                getEndPoint().write(callback, ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
            }
        }

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.start();

        String host = "wikipedia.org";
        int port = 80;
        SocketAddress address = new InetSocketAddress(host, port);

        ClientConnectionFactory connectionFactory = (endPoint, context) ->
            new TelnetConnection(endPoint, clientConnector.getExecutor());

        CompletableFuture<TelnetConnection> connectionPromise = new Promise.Completable<>();

        Map<String, Object> context = new HashMap<>();
        context.put(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY, connectionFactory);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, connectionPromise);
        clientConnector.connect(address, context);

        connectionPromise.whenComplete((connection, failure) ->
        {
            if (failure == null)
            {
                // Register a listener that receives string lines.
                connection.onLine(line -> System.getLogger("app").log(INFO, "line: {0}", line));

                // Write a line.
                connection.writeLine("" +
                    "GET / HTTP/1.0\r\n" +
                    "", Callback.NOOP);
            }
            else
            {
                failure.printStackTrace();
            }
        });
        // end::telnet[]
    }

    public void tlsTelnet() throws Exception
    {
        // tag::tlsTelnet[]
        class TelnetConnection extends AbstractConnection
        {
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            private Consumer<String> consumer;

            public TelnetConnection(EndPoint endPoint, Executor executor)
            {
                super(endPoint, executor);
            }

            @Override
            public void onOpen()
            {
                super.onOpen();

                // Declare interest for fill events.
                fillInterested();
            }

            @Override
            public void onFillable()
            {
                try
                {
                    ByteBuffer buffer = BufferUtil.allocate(1024);
                    while (true)
                    {
                        int filled = getEndPoint().fill(buffer);
                        if (filled > 0)
                        {
                            while (buffer.hasRemaining())
                            {
                                // Search for newline.
                                byte read = buffer.get();
                                if (read == '\n')
                                {
                                    // Notify the consumer of the line.
                                    consumer.accept(bytes.toString(StandardCharsets.UTF_8));
                                    bytes.reset();
                                }
                                else
                                {
                                    bytes.write(read);
                                }
                            }
                        }
                        else if (filled == 0)
                        {
                            // No more bytes to fill, declare
                            // again interest for fill events.
                            fillInterested();
                            return;
                        }
                        else
                        {
                            // The other peer closed the
                            // connection, close it back.
                            getEndPoint().close();
                            return;
                        }
                    }
                }
                catch (Exception x)
                {
                    getEndPoint().close(x);
                }
            }

            public void onLine(Consumer<String> consumer)
            {
                this.consumer = consumer;
            }

            public void writeLine(String line, Callback callback)
            {
                line = line + "\r\n";
                getEndPoint().write(callback, ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
            }
        }

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.start();

        // Use port 443 to contact the server using encrypted HTTP.
        String host = "wikipedia.org";
        int port = 443;
        SocketAddress address = new InetSocketAddress(host, port);

        ClientConnectionFactory connectionFactory = (endPoint, context) ->
            new TelnetConnection(endPoint, clientConnector.getExecutor());

        // Wrap the "telnet" ClientConnectionFactory with the SslClientConnectionFactory.
        connectionFactory = new SslClientConnectionFactory(clientConnector.getSslContextFactory(),
            clientConnector.getByteBufferPool(), clientConnector.getRetainableByteBufferPool(),
            clientConnector.getExecutor(), connectionFactory);

        // We will obtain a SslConnection now.
        CompletableFuture<SslConnection> connectionPromise = new Promise.Completable<>();

        Map<String, Object> context = new HashMap<>();
        context.put(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY, connectionFactory);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, connectionPromise);
        clientConnector.connect(address, context);

        connectionPromise.whenComplete((sslConnection, failure) ->
        {
            if (failure == null)
            {
                // Unwrap the SslConnection to access the "line" APIs in TelnetConnection.
                TelnetConnection connection = (TelnetConnection)sslConnection.getDecryptedEndPoint().getConnection();
                // Register a listener that receives string lines.
                connection.onLine(line -> System.getLogger("app").log(INFO, "line: {0}", line));

                // Write a line.
                connection.writeLine("" +
                    "GET / HTTP/1.0\r\n" +
                    "", Callback.NOOP);
            }
            else
            {
                failure.printStackTrace();
            }
        });
        // end::tlsTelnet[]
    }

    public void unixDomain() throws Exception
    {
        // tag::unixDomain[]
        // This is the path where the server "listens" on.
        Path unixDomainPath = Path.of("/path/to/server.sock");

        // Creates a ClientConnector that uses Unix-Domain
        // sockets, not the network, to connect to the server.
        ClientConnector clientConnector = ClientConnector.forUnixDomain(unixDomainPath);
        clientConnector.start();
        // end::unixDomain[]
    }

    public static void main(String[] args) throws Exception
    {
        new ClientConnectorDocs().tlsTelnet();
    }
}
