//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package embedded;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritePendingException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

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
                fillInterested();
            }

            @Override
            public void onFillable()
            {
                // Called when a fill event happens.
            }
        }
        // end::connection[]
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
        class EchoConnection extends AbstractConnection implements Callback
        {
            public static final int IDLE = 0;
            public static final int WRITING = 1;
            public static final int PENDING = 2;

            private final AtomicInteger state = new AtomicInteger();

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
                try
                {
                    ByteBuffer buffer = BufferUtil.allocate(1024);
                    while (true)
                    {
                        int filled = getEndPoint().fill(buffer);
                        if (filled > 0)
                        {
                            // We have filled some bytes, echo them back.
                            if (write(buffer))
                            {
                                // If the write completed, continue to fill.
                                continue;
                            }
                            else
                            {
                                // The write is pending, return to wait for completion.
                                return;
                            }
                        }
                        else if (filled == 0)
                        {
                            // No more bytes to read, declare
                            // again interest for fill events.
                            fillInterested();
                            return;
                        }
                        else
                        {
                            // The other peer closed the connection.
                            close();
                            return;
                        }
                    }
                }
                catch (Throwable x)
                {
                    getEndPoint().close(x);
                }
            }

            private boolean write(ByteBuffer buffer)
            {
                // Check if we are writing concurrently.
                if (!state.compareAndSet(IDLE, WRITING))
                    throw new WritePendingException();

                // Write the buffer using "this" as a callback.
                getEndPoint().write(this, buffer);

                // Check if the write is already completed.
                boolean writeIsPending = state.compareAndSet(WRITING, PENDING);

                // Return true if the write was completed.
                return !writeIsPending;
            }

            @Override
            public void succeeded()
            {
                // The write is complete, reset the state.
                int prevState = state.getAndSet(IDLE);

                // If the write was pending we need
                // to resume reading from the network.
                if (prevState == PENDING)
                    onFillable();
            }

            @Override
            public void failed(Throwable x)
            {
                getEndPoint().close(x);
            }
        }
        // end::echo-correct[]
    }
}
