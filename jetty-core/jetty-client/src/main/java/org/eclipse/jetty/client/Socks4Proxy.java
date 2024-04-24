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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Client-side proxy configuration for SOCKS4, a de-facto standard.</p>
 * <p>Consider using SOCK5 instead, a protocol that has been standardized
 * by IETF.</p>
 *
 * @see Socks5Proxy
 */
public class Socks4Proxy extends ProxyConfiguration.Proxy
{
    /**
     * <p>Creates a new instance with the given SOCKS4 proxy host and port.</p>
     *
     * @param host the SOCKS4 proxy host name
     * @param port the SOCKS4 proxy port
     */
    public Socks4Proxy(String host, int port)
    {
        this(new Origin.Address(host, port), false);
    }

    /**
     * <p>Creates a new instance with the given SOCKS4 proxy address.</p>
     * <p>When {@code secure=true} the communication between the client and the
     * proxy will be encrypted (using this proxy {@link #getSslContextFactory()}
     * which typically defaults to that of {@link HttpClient}.</p>
     *
     * @param address the SOCKS4 proxy address (host and port)
     * @param secure whether the communication between the client and the SOCKS4 proxy should be secure
     */
    public Socks4Proxy(Origin.Address address, boolean secure)
    {
        super(address, secure, null, null);
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
        public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
        {
            Destination destination = (Destination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            Executor executor = destination.getHttpClient().getExecutor();
            Socks4ProxyConnection connection = new Socks4ProxyConnection(endPoint, executor, connectionFactory, context);
            return customize(connection, context);
        }
    }

    private static class Socks4ProxyConnection extends AbstractConnection implements Callback
    {
        private static final Pattern IPv4_PATTERN = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
        private static final Logger LOG = LoggerFactory.getLogger(Socks4ProxyConnection.class);

        private final Socks4Parser parser = new Socks4Parser();
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
            Destination destination = (Destination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            Origin.Address address = destination.getOrigin().getAddress();
            String host = address.getHost();
            short port = (short)address.getPort();
            Matcher matcher = IPv4_PATTERN.matcher(host);
            if (matcher.matches())
            {
                // SOCKS 4
                ByteBuffer buffer = ByteBuffer.allocate(9);
                buffer.put((byte)4).put((byte)1).putShort(port);
                for (int i = 1; i <= 4; ++i)
                {
                    buffer.put((byte)Integer.parseInt(matcher.group(i)));
                }
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
            if (LOG.isDebugEnabled())
                LOG.debug("Written SOCKS4 connect request");
            fillInterested();
        }

        @Override
        public void failed(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("SOCKS4 failure", x);
            getEndPoint().close(x);
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
            promise.failed(x);
        }

        @Override
        public boolean onIdleExpired(TimeoutException timeout)
        {
            failed(timeout);
            return false;
        }

        @Override
        public void onFillable()
        {
            try
            {
                while (true)
                {
                    // Avoid to read too much from the socket: ask
                    // the parser how much left there is to read.
                    ByteBuffer buffer = BufferUtil.allocate(parser.expected());
                    int filled = getEndPoint().fill(buffer);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Read SOCKS4 connect response, {} bytes", filled);

                    if (filled < 0)
                        throw new IOException("SOCKS4 tunnel failed, connection closed");

                    if (filled == 0)
                    {
                        fillInterested();
                        return;
                    }

                    if (parser.parse(buffer))
                        return;
                }
            }
            catch (Throwable x)
            {
                failed(x);
            }
        }

        private void onSocks4Response(int responseCode) throws IOException
        {
            if (responseCode == 0x5A)
                tunnel();
            else
                throw new IOException("SOCKS4 tunnel failed with code " + responseCode);
        }

        private void tunnel()
        {
            try
            {
                Destination destination = (Destination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
                Origin.Address address = destination.getOrigin().getAddress();
                // Don't want to do DNS resolution here.
                InetSocketAddress inet = InetSocketAddress.createUnresolved(address.getHost(), address.getPort());
                context.put(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY, inet);
                ClientConnectionFactory connectionFactory = this.connectionFactory;
                if (destination.isSecure())
                    connectionFactory = destination.getHttpClient().newSslClientConnectionFactory(null, connectionFactory);
                org.eclipse.jetty.io.Connection newConnection = connectionFactory.newConnection(getEndPoint(), context);
                getEndPoint().upgrade(newConnection);
                if (LOG.isDebugEnabled())
                    LOG.debug("SOCKS4 tunnel established: {} over {}", this, newConnection);
            }
            catch (Throwable x)
            {
                failed(x);
            }
        }

        private class Socks4Parser
        {
            private static final int EXPECTED_LENGTH = 8;
            private int cursor;
            private int response;

            private boolean parse(ByteBuffer buffer) throws IOException
            {
                while (buffer.hasRemaining())
                {
                    byte current = buffer.get();
                    if (cursor == 1)
                        response = current & 0xFF;
                    ++cursor;
                    if (cursor == EXPECTED_LENGTH)
                    {
                        onSocks4Response(response);
                        return true;
                    }
                }
                return false;
            }

            private int expected()
            {
                return EXPECTED_LENGTH - cursor;
            }
        }
    }
}
