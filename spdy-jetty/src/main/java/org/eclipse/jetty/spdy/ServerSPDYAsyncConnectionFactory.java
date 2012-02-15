/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.io.IOException;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;

public class ServerSPDYAsyncConnectionFactory implements AsyncConnectionFactory
{
    private final short version;
    private final ServerSessionFrameListener listener;

    public ServerSPDYAsyncConnectionFactory(short version)
    {
        this(version, null);
    }

    public ServerSPDYAsyncConnectionFactory(short version, ServerSessionFrameListener listener)
    {
        this.version = version;
        this.listener = listener;
    }

    @Override
    public AsyncConnection newAsyncConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
    {
        CompressionFactory compressionFactory = new StandardCompressionFactory();
        Parser parser = new Parser(compressionFactory.newDecompressor());
        Generator generator = new Generator(compressionFactory.newCompressor());

        ServerSessionFrameListener listener = this.listener;
        if (listener == null)
            listener = newServerSessionFrameListener(endPoint, attachment);

        ServerSPDYAsyncConnection connection = new ServerSPDYAsyncConnection(endPoint, parser, listener);
        endPoint.setConnection(connection);

        final StandardSession session = new StandardSession(version, connection, 2, listener, generator);
        parser.addListener(session);
        connection.setSession(session);

        return connection;
    }

    protected ServerSessionFrameListener newServerSessionFrameListener(AsyncEndPoint endPoint, Object attachment)
    {
        return listener;
    }

    private static class ServerSPDYAsyncConnection extends SPDYAsyncConnection
    {
        private final ServerSessionFrameListener listener;
        private volatile boolean connected;

        private ServerSPDYAsyncConnection(AsyncEndPoint endPoint, Parser parser, ServerSessionFrameListener listener)
        {
            super(endPoint, parser);
            this.listener = listener;
        }

        @Override
        public Connection handle() throws IOException
        {
            if (!connected)
            {
                // NPE guard to support tests
                if (listener != null)
                    listener.onConnect(getSession());
                connected = true;
            }
            return super.handle();
        }
    }
}
