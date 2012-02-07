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
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.nio.AsyncConnectionFactory;
import org.eclipse.jetty.spdy.nio.AsyncSPDYConnection;
import org.eclipse.jetty.spdy.parser.Parser;

public class ServerSPDY2AsyncConnectionFactory implements AsyncConnectionFactory
{
    private final ServerSessionFrameListener listener;

    public ServerSPDY2AsyncConnectionFactory()
    {
        this(null);
    }

    public ServerSPDY2AsyncConnectionFactory(ServerSessionFrameListener listener)
    {
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

        ServerAsyncSPDYConnection connection = new ServerAsyncSPDYConnection(endPoint, parser, listener);
        endPoint.setConnection(connection);

        final StandardSession session = new StandardSession(connection, 2, listener, generator);
        parser.addListener(session);
        connection.setSession(session);

        return connection;
    }

    protected ServerSessionFrameListener newServerSessionFrameListener(AsyncEndPoint endPoint, Object attachment)
    {
        return listener;
    }

    private static class ServerAsyncSPDYConnection extends AsyncSPDYConnection
    {
        private final ServerSessionFrameListener listener;
        private volatile Session session;

        private ServerAsyncSPDYConnection(EndPoint endPoint, Parser parser, ServerSessionFrameListener listener)
        {
            super(endPoint, parser);
            this.listener = listener;
        }

        @Override
        public Connection handle() throws IOException
        {
            final Session session = this.session;
            if (session != null)
            {
                // NPE guard to support tests
                if (listener != null)
                    listener.onConnect(session);
                this.session = null;
            }
            return super.handle();
        }

        private void setSession(Session session)
        {
            this.session = session;
        }
    }
}
