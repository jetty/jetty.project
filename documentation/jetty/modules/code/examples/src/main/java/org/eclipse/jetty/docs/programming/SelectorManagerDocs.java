//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;

@SuppressWarnings("unused")
public class SelectorManagerDocs
{
    // tag::connect[]
    public void connect(SelectorManager selectorManager, Map<String, Object> context) throws IOException
    {
        String host = "host";
        int port = 8080;

        // Create an unconnected SocketChannel.
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);

        // Connect and register to Jetty.
        if (socketChannel.connect(new InetSocketAddress(host, port)))
            selectorManager.accept(socketChannel, context);
        else
            selectorManager.connect(socketChannel, context);
    }
    // end::connect[]

    // tag::accept[]
    public void accept(ServerSocketChannel acceptor, SelectorManager selectorManager) throws IOException
    {
        // Wait until a client connects.
        SocketChannel socketChannel = acceptor.accept();
        socketChannel.configureBlocking(false);

        // Accept and register to Jetty.
        Object attachment = null;
        selectorManager.accept(socketChannel, attachment);
    }
    // end::accept[]

    public void connection()
    {
        // tag::connection[]
        // Extend AbstractConnection to inherit basic implementation.
        class MyConnection extends AbstractConnection
        {
            public MyConnection(EndPoint endPoint, Executor executor)
            {
                super(endPoint, executor);
            }

            @Override
            public void onOpen()
            {
                super.onOpen();

                // Declare interest for fill events.
                // When the fill event happens, method onFillable() below is invoked.
                fillInterested();
            }

            @Override
            public void onFillable()
            {
                // Invoked when a fill event happens.
            }
        }
        // end::connection[]
    }

    public void connectionListener() throws Exception
    {
        // tag::connectionListener[]
        class ThresholdConnectionListener implements Connection.Listener
        {
            private final AtomicInteger connections = new AtomicInteger();

            private int threshold;
            private boolean notified;

            public ThresholdConnectionListener(int threshold)
            {
                this.threshold = threshold;
            }

            @Override
            public void onOpened(Connection connection)
            {
                int count = connections.incrementAndGet();
                if (count > threshold && !notified)
                {
                    notified = true;
                    System.getLogger("connection.threshold").log(System.Logger.Level.WARNING, "Connection threshold exceeded");
                }
            }

            @Override
            public void onClosed(Connection connection)
            {
                int count = connections.decrementAndGet();
                // Reset the alert when we are below 90% of the threshold.
                if (count < threshold * 0.9F)
                    notified = false;
            }
        }

        // Configure server-side connectors with Connection.Listeners.
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);
        // Add statistics.
        connector.addBean(new ConnectionStatistics());
        // Add your own Connection.Listener.
        connector.addBean(new ThresholdConnectionListener(2048));
        server.start();

        // Configure client-side HttpClient with Connection.Listeners.
        HttpClient httpClient = new HttpClient();
        // Add statistics.
        httpClient.addBean(new ConnectionStatistics());
        // Add your own Connection.Listener.
        httpClient.addBean(new ThresholdConnectionListener(512));
        httpClient.start();
        // end::connectionListener[]
    }

    public void echoWrong()
    {
        // tag::echo-wrong[]
        class WrongEchoConnection extends AbstractConnection implements Callback
        {
            public WrongEchoConnection(EndPoint endPoint, Executor executor)
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
                    int filled = getEndPoint().fill(buffer);
                    if (filled > 0)
                    {
                        // Filled some bytes, echo them back.
                        getEndPoint().write(this, buffer);
                    }
                    else if (filled == 0)
                    {
                        // No more bytes to fill, declare
                        // again interest for fill events.
                        fillInterested();
                    }
                    else
                    {
                        // The other peer closed the
                        // connection, close it back.
                        getEndPoint().close();
                    }
                }
                catch (Exception x)
                {
                    getEndPoint().close(x);
                }
            }

            @Override
            public void succeeded()
            {
                // The write is complete, fill again.
                onFillable();
            }

            @Override
            public void failed(Throwable x)
            {
                getEndPoint().close(x);
            }
        }
        // end::echo-wrong[]
    }

    public void echoCorrect()
    {
        // tag::echo-correct[]
        class EchoConnection extends AbstractConnection
        {
            private final IteratingCallback callback = new EchoIteratingCallback();

            public EchoConnection(EndPoint endp, Executor executor)
            {
                super(endp, executor);
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
                // Start the iteration loop that reads and echoes back.
                callback.iterate();
            }

            class EchoIteratingCallback extends IteratingCallback
            {
                private ByteBuffer buffer;

                @Override
                protected Action process() throws Throwable
                {
                    // Obtain a buffer if we don't already have one.
                    if (buffer == null)
                        buffer = BufferUtil.allocate(1024);

                    int filled = getEndPoint().fill(buffer);
                    if (filled > 0)
                    {
                        // We have filled some bytes, echo them back.
                        getEndPoint().write(this, buffer);

                        // Signal that the iteration should resume
                        // when the write() operation is completed.
                        return Action.SCHEDULED;
                    }
                    else if (filled == 0)
                    {
                        // We don't need the buffer anymore, so
                        // don't keep it around while we are idle.
                        buffer = null;

                        // No more bytes to read, declare
                        // again interest for fill events.
                        fillInterested(this);

                        // Signal that the iteration is now IDLE.
                        return Action.IDLE;
                    }
                    else
                    {
                        // The other peer closed the connection,
                        // the iteration completed successfully.
                        return Action.SUCCEEDED;
                    }
                }

                @Override
                protected void onCompleteSuccess()
                {
                    // The iteration completed successfully.
                    getEndPoint().close();
                }

                @Override
                protected void onCompleteFailure(Throwable cause)
                {
                    // The iteration completed with a failure.
                    getEndPoint().close(cause);
                }

                @Override
                public InvocationType getInvocationType()
                {
                    return InvocationType.NON_BLOCKING;
                }
            }
        }
        // end::echo-correct[]
    }
}
