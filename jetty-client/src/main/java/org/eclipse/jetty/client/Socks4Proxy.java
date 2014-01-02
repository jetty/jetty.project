//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class Socks4Proxy extends ProxyConfiguration.Proxy
{
    public Socks4Proxy(String host, int port)
    {
        this(new Origin.Address(host, port), false);
    }

    public Socks4Proxy(Origin.Address address, boolean secure)
    {
        super(address, secure);
    }

    @Override
    public ClientConnectionFactory newClientConnectionFactory(ClientConnectionFactory connectionFactory)
    {
        return new Socks4ProxyClientConnectionFactory(connectionFactory);
    }

    public static class Socks4ProxyClientConnectionFactory implements ClientConnectionFactory
    {
        private final ClientConnectionFactory connectionFactory;

        public Socks4ProxyClientConnectionFactory(ClientConnectionFactory connectionFactory)
        {
            this.connectionFactory = connectionFactory;
        }

        @Override
        public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            Executor executor = destination.getHttpClient().getExecutor();
            return new Socks4ProxyConnection(endPoint, executor, connectionFactory, context);
        }
    }

    private static class Socks4ProxyConnection extends AbstractConnection implements Callback
    {
        private static final Pattern IPv4_PATTERN = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
        private static final Logger LOG = Log.getLogger(Socks4ProxyConnection.class);

        private final ClientConnectionFactory connectionFactory;
        private final Map<String, Object> context;

        public Socks4ProxyConnection(EndPoint endPoint, Executor executor, ClientConnectionFactory connectionFactory, Map<String, Object> context)
        {
            super(endPoint, executor);
            this.connectionFactory = connectionFactory;
            this.context = context;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            writeSocks4Connect();
        }

        /**
         * Writes the SOCKS "connect" bytes, differentiating between SOCKS 4 and 4A;
         * the former sends an IPv4 address, the latter the full domain name.
         */
        private void writeSocks4Connect()
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            String host = destination.getHost();
            short port = (short)destination.getPort();
            Matcher matcher = IPv4_PATTERN.matcher(host);
            if (matcher.matches())
            {
                // SOCKS 4
                ByteBuffer buffer = ByteBuffer.allocate(9);
                buffer.put((byte)4).put((byte)1).putShort(port);
                for (int i = 1; i <= 4; ++i)
                    buffer.put((byte)Integer.parseInt(matcher.group(i)));
                buffer.put((byte)0);
                buffer.flip();
                getEndPoint().write(this, buffer);
            }
            else
            {
                // SOCKS 4A
                byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
                ByteBuffer buffer = ByteBuffer.allocate(9 + hostBytes.length + 1);
                buffer.put((byte)4).put((byte)1).putShort(port);
                buffer.put((byte)0).put((byte)0).put((byte)0).put((byte)1).put((byte)0);
                buffer.put(hostBytes).put((byte)0);
                buffer.flip();
                getEndPoint().write(this, buffer);
            }
        }

        @Override
        public void succeeded()
        {
            LOG.debug("Written SOCKS4 connect request");
            fillInterested();
        }

        @Override
        public void failed(Throwable x)
        {
            close();
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
            promise.failed(x);
        }

        @Override
        public void onFillable()
        {
            try
            {
                ByteBuffer buffer = BufferUtil.allocate(8);
                int filled = getEndPoint().fill(buffer);
                LOG.debug("Read SOCKS4 connect response, {} bytes", filled);
                if (filled != 8)
                    throw new IOException("Invalid response from SOCKS4 proxy");
                int result = buffer.get(1);
                if (result == 0x5A)
                    tunnel();
                else
                    throw new IOException("SOCKS4 tunnel failed with code " + result);
            }
            catch (Throwable x)
            {
                failed(x);
            }
        }

        private void tunnel()
        {
            try
            {
                HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
                HttpClient client = destination.getHttpClient();
                ClientConnectionFactory connectionFactory = this.connectionFactory;
                if (HttpScheme.HTTPS.is(destination.getScheme()))
                    connectionFactory = new SslClientConnectionFactory(client.getSslContextFactory(), client.getByteBufferPool(), client.getExecutor(), connectionFactory);
                org.eclipse.jetty.io.Connection connection = connectionFactory.newConnection(getEndPoint(), context);
                ClientConnectionFactory.Helper.replaceConnection(this, connection);
                LOG.debug("SOCKS4 tunnel established: {} over {}", this, connection);
            }
            catch (Throwable x)
            {
                failed(x);
            }
        }
    }
}
