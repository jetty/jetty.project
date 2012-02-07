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

import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.SslConnection;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.spdy.ServerSPDY2AsyncConnectionFactory;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SPDYServerConnector extends SelectChannelConnector
{
    private final List<AsyncConnectionFactory> factories = new CopyOnWriteArrayList<>();
    private final SslContextFactory sslContextFactory;

    public SPDYServerConnector(ServerSessionFrameListener listener)
    {
        this(listener, null);
    }

    public SPDYServerConnector(ServerSessionFrameListener listener, SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
        if (sslContextFactory != null)
            addBean(sslContextFactory);
        if (listener != null)
            addAsyncConnectionFactory(new ServerSPDY2AsyncConnectionFactory(listener));
    }

    public void addAsyncConnectionFactory(AsyncConnectionFactory factory)
    {
        factories.add(factory);
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

            AsyncConnection connection = new EmptyAsyncConnection(sslEndPoint);
            sslEndPoint.setConnection(connection);

            startHandshake(engine);

            return sslConnection;
        }
        else
        {
            AsyncConnectionFactory connectionFactory = getAsyncConnectionFactory("spdy/2");
            AsyncConnection connection = connectionFactory.newAsyncConnection(channel, endPoint, null);
            endPoint.setConnection(connection);
            return connection;
        }
    }

    protected List<String> provideProtocols()
    {
        List<String> result = new ArrayList<>();
        for (AsyncConnectionFactory factory : factories)
            result.add(factory.getProtocol());
        return result;
    }

    protected AsyncConnectionFactory getAsyncConnectionFactory(String protocol)
    {
        for (AsyncConnectionFactory factory : factories)
        {
            if (factory.getProtocol().equals(protocol))
                return factory;
        }
        return null;
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
}
