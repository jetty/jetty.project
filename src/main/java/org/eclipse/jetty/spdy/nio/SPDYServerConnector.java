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

package org.eclipse.jetty.spdy.nio;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.SslConnection;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.StandardSession;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SPDYServerConnector extends SelectChannelConnector
{
    private final ServerSessionFrameListener listener;
    private final SslContextFactory sslContextFactory;

    public SPDYServerConnector(ServerSessionFrameListener listener)
    {
        this(listener, null);
    }

    public SPDYServerConnector(ServerSessionFrameListener listener, SslContextFactory sslContextFactory)
    {
        this.listener = listener;
        this.sslContextFactory = sslContextFactory;
        if (sslContextFactory != null)
            addBean(sslContextFactory);
    }

    @Override
    protected AsyncConnection newConnection(final SocketChannel channel, AsyncEndPoint endPoint)
    {
        if (sslContextFactory != null)
        {
            SSLEngine engine = newSSLEngine(sslContextFactory, channel);
            SslConnection sslConnection = new SslConnection(engine, endPoint);
            endPoint.setConnection(sslConnection);
            final AsyncEndPoint sslEndPoint = sslConnection.getSslEndPoint();

            NextProtoNego.put(engine, new NextProtoNego.ServerProvider()
            {
                @Override
                public List<String> protocols()
                {
                    return provideProtocols();
                }

                @Override
                public void protocolSelected(String protocol)
                {
                    AsyncConnectionFactory connectionFactory = getAsyncConnectionFactory(protocol);
                    AsyncConnection connection = connectionFactory.newAsyncConnection(channel, sslEndPoint, null);
                    sslEndPoint.setConnection(connection);
                }
            });

            AsyncConnection connection = new NoProtocolConnection(sslEndPoint);
            sslEndPoint.setConnection(connection);

            startHandshake(engine);

            return sslConnection;
        }
        else
        {
            AsyncConnectionFactory connectionFactory = new ServerSPDY2AsyncConnectionFactory();
            AsyncConnection connection = connectionFactory.newAsyncConnection(channel, endPoint, null);
            endPoint.setConnection(connection);
            return connection;
        }
    }

    protected List<String> provideProtocols()
    {
        // TODO: connectionFactories.map(AsyncConnectionFactory::getProtocol())

        return Arrays.asList("spdy/2");
    }

    protected AsyncConnectionFactory getAsyncConnectionFactory(String protocol)
    {
        // TODO: select from existing AsyncConnectionFactories
        return new ServerSPDY2AsyncConnectionFactory();
    }

    protected SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        String peerHost = channel.socket().getInetAddress().getHostAddress();
        int peerPort = channel.socket().getPort();
        SSLEngine engine = sslContextFactory.newSslEngine(peerHost, peerPort);
        engine.setUseClientMode(false);
        return engine;
    }

    private void startHandshake(SSLEngine engine)
    {
        try
        {
            engine.beginHandshake();
        }
        catch (SSLException x)
        {
            throw new RuntimeException(x);
        }
    }

    private class ServerSPDY2AsyncConnectionFactory implements AsyncConnectionFactory
    {
        @Override
        public String getProtocol()
        {
            return "spdy/2";
        }

        @Override
        public AsyncConnection newAsyncConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
        {
            CompressionFactory compressionFactory = new StandardCompressionFactory();
            Parser parser = new Parser(compressionFactory.newDecompressor());
            Generator generator = new Generator(compressionFactory.newCompressor());

            ServerAsyncSPDYConnection connection = new ServerAsyncSPDYConnection(endPoint, parser, listener);
            endPoint.setConnection(connection);

            final StandardSession session = new StandardSession(connection, 2, listener, generator);
            parser.addListener(session);
            connection.setSession(session);

            return connection;
        }
    }

    private class ServerAsyncSPDYConnection extends AsyncSPDYConnection
    {
        private final ServerSessionFrameListener listener;
        private volatile Session session;

        private ServerAsyncSPDYConnection(EndPoint endp, Parser parser, ServerSessionFrameListener listener)
        {
            super(endp, parser);
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
