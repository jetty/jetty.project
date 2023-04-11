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

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.ProxyConfiguration.Proxy;
import org.eclipse.jetty.client.Socks5.AddrType;
import org.eclipse.jetty.client.Socks5.AuthType;
import org.eclipse.jetty.client.Socks5.Authentication;
import org.eclipse.jetty.client.Socks5.Command;
import org.eclipse.jetty.client.Socks5.NoAuthentication;
import org.eclipse.jetty.client.Socks5.Reply;
import org.eclipse.jetty.client.Socks5.RequestStage;
import org.eclipse.jetty.client.Socks5.ResponseStage;
import org.eclipse.jetty.client.Socks5.SockConst;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Socks5Proxy extends Proxy 
{
    private static final int MAX_AUTHRATIONS = 255;
    private static final Logger LOG = LoggerFactory.getLogger(Socks5Proxy.class);

    private LinkedHashMap<Byte, Authentication> authorizations = new LinkedHashMap<>();

    public Socks5Proxy(String host, int port)
    {
        this(new Origin.Address(host, port), false);
    }

    public Socks5Proxy(Origin.Address address, boolean secure)
    {
        super(address, secure, null, null);
        // default support no_auth
        addAuthentication(new NoAuthentication());
    }

    public Socks5Proxy addAuthentication(Authentication authentication)
    {
        if (authorizations.size() >= MAX_AUTHRATIONS)
        {
            throw new IllegalArgumentException("too much authentications");
        }
        authorizations.put(authentication.getAuthType(), authentication);
        return this;
    }

    /**
     * remove authorization by type
     * @see AuthType
     * @param type authorization type
     */
    public Socks5Proxy removeAuthentication(byte type)
    {
        authorizations.remove(type);
        return this;
    }

    @Override
    public ClientConnectionFactory newClientConnectionFactory(ClientConnectionFactory connectionFactory) 
    {
        return new Socks5ProxyClientConnectionFactory(connectionFactory, authorizations);
    }

    @Override
    public boolean matches(Origin origin) 
    {
        return true;
    }

    private static class Socks5ProxyConnection extends AbstractConnection implements Callback 
    {
        private static final Pattern IPv4_PATTERN = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
        private final ClientConnectionFactory connectionFactory;
        private final Map<String, Object> context;

        private LinkedHashMap<Byte, Authentication> authorizations;

        private Authentication selectedAuthentication;
        private RequestStage requestStage = RequestStage.INIT;
        private ResponseStage responseStage = null;
        private int variableLen;

        public Socks5ProxyConnection(EndPoint endPoint, Executor executor, ClientConnectionFactory connectionFactory, Map<String, Object> context) 
        {
            super(endPoint, executor);
            this.connectionFactory = connectionFactory;
            this.context = context;
        }

        public void onOpen() 
        {
            super.onOpen();
            this.writeHandshakeCmd();
        }

        private void writeHandshakeCmd() 
        {
            switch (requestStage)
            {
                case INIT:
                    // write supported authorizations
                    int authLen = authorizations.size();
                    ByteBuffer init = ByteBuffer.allocate(2 + authLen);
                    init.put(SockConst.VER).put((byte)authLen);
                    for (byte type : authorizations.keySet())
                    {
                        init.put(type);
                    }
                    init.flip();
                    setResponseStage(ResponseStage.INIT);
                    this.getEndPoint().write(this, init);
                    break;
                case AUTH:
                    ByteBuffer auth = selectedAuthentication.authorize();
                    setResponseStage(ResponseStage.AUTH);
                    this.getEndPoint().write(this, auth);
                    break;
                case CONNECTING:
                    HttpDestination destination = (HttpDestination)this.context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
                    String host = destination.getHost();
                    short port = (short)destination.getPort();
                    setResponseStage(ResponseStage.CONNECTING);
                    Matcher matcher = IPv4_PATTERN.matcher(host);
                    if (matcher.matches()) 
                    {
                        // ip
                        ByteBuffer buffer = ByteBuffer.allocate(10);
                        buffer.put(SockConst.VER)
                            .put(Command.CONNECT)
                            .put(SockConst.RSV)
                            .put(AddrType.IPV4);
                        for (int i = 1; i <= 4; ++i) 
                        {
                            buffer.put((byte)Integer.parseInt(matcher.group(i)));
                        }
                        buffer.putShort(port);
                        buffer.flip();
                        this.getEndPoint().write(this, buffer);
                    } 
                    else 
                    {
                        // domain
                        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
                        ByteBuffer buffer = ByteBuffer.allocate(7 + hostBytes.length);
                        buffer.put(SockConst.VER)
                            .put(Command.CONNECT)
                            .put(SockConst.RSV)
                            .put(AddrType.DOMAIN_NAME);

                        buffer.put((byte)hostBytes.length)
                            .put(hostBytes)
                            .putShort(port);
                        buffer.flip();
                        this.getEndPoint().write(this, buffer);
                    }
                    break;
            }
        }

        public void succeeded() 
        {
            if (LOG.isDebugEnabled()) 
            {
                LOG.debug("Written SOCKS5 handshake request");
            }
            this.fillInterested();
        }

        public void failed(Throwable x) 
        {
            this.close();
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)this.context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
            promise.failed(x);
        }

        public void onFillable() 
        {
            try 
            {
                Socks5Parser parser = new Socks5Parser();
                ByteBuffer buffer;
                do 
                {
                    buffer = BufferUtil.allocate(parser.expected());
                    int filled = this.getEndPoint().fill(buffer);
                    if (LOG.isDebugEnabled()) 
                    {
                        LOG.debug("Read SOCKS5 connect response, {} bytes", (long)filled);
                    }

                    if (filled < 0) 
                    {
                        throw new SocketException("SOCKS5 tunnel failed, connection closed");
                    }

                    if (filled == 0) 
                    {
                        this.fillInterested();
                        return;
                    }
                } 
                while (!parser.parse(buffer));
            } 
            catch (Exception e) 
            {
                this.failed(e);
            }
        }

        private void onSocks5Response(byte[] bs) throws SocketException 
        {
            switch (responseStage)
            {
                case INIT:
                    if (bs[0] != SockConst.VER)
                    {
                        throw new SocketException("SOCKS5 tunnel failed with err VER " + bs[0]);
                    }
                    if (bs[1] == AuthType.NO_AUTH)
                    {
                        requestStage = RequestStage.CONNECTING;
                        writeHandshakeCmd();
                    } 
                    else if (bs[1] == AuthType.NO_ACCEPTABLE)
                    {
                        throw new SocketException("SOCKS : No acceptable methods");
                    }
                    else 
                    {
                        selectedAuthentication = authorizations.get(bs[1]);
                        if (selectedAuthentication == null)
                        {
                            throw new SocketException("SOCKS5 tunnel failed with unknown auth type");
                        }
                        requestStage = RequestStage.AUTH;
                        writeHandshakeCmd();
                    }
                    break;
                case AUTH:
                    if (bs[0] != SockConst.USER_PASS_VER)
                    {
                        throw new SocketException("SOCKS5 tunnel failed with err UserPassVer " + bs[0]);
                    }
                    if (bs[1] != SockConst.SUCCEEDED)
                    {
                        throw new SocketException("SOCKS : authentication failed");
                    }
                    // authorization successful
                    requestStage = RequestStage.CONNECTING;
                    writeHandshakeCmd();
                    break;
                case CONNECTING:
                    if (bs[0] != SockConst.VER)
                    {
                        throw new SocketException("SOCKS5 tunnel failed with err VER " + bs[0]);
                    }
                    switch (bs[1])
                    {
                        case SockConst.SUCCEEDED:
                            switch (bs[3])
                            {
                                case AddrType.IPV4:
                                    setResponseStage(ResponseStage.CONNECTED_IPV4);
                                    fillInterested();
                                    break;
                                case AddrType.DOMAIN_NAME:
                                    setResponseStage(ResponseStage.CONNECTED_DOMAIN_NAME);
                                    fillInterested();
                                    break;
                                case AddrType.IPV6:
                                    setResponseStage(ResponseStage.CONNECTED_IPV6);
                                    fillInterested();
                                    break;
                                default:
                                    throw new SocketException("SOCKS: unknown addr type " + bs[3]);
                            }
                            break;
                        case Reply.GENERAL:
                            throw new SocketException("SOCKS server general failure");
                        case Reply.RULE_BAN:
                            throw new SocketException("SOCKS: Connection not allowed by ruleset");
                        case Reply.NETWORK_UNREACHABLE:
                            throw new SocketException("SOCKS: Network unreachable");
                        case Reply.HOST_UNREACHABLE:
                            throw new SocketException("SOCKS: Host unreachable");
                        case Reply.CONNECT_REFUSE:
                            throw new SocketException("SOCKS: Connection refused");
                        case Reply.TTL_TIMEOUT:
                            throw new SocketException("SOCKS: TTL expired");
                        case Reply.CMD_UNSUPPORTED:
                            throw new SocketException("SOCKS: Command not supported");
                        case Reply.ATYPE_UNSUPPORTED:
                            throw new SocketException("SOCKS: address type not supported");
                        default:
                            throw new SocketException("SOCKS: unknown code " + bs[1]);
                    }
                    break;
                case CONNECTED_DOMAIN_NAME:
                case CONNECTED_IPV6:
                    variableLen = 2 + bs[0];
                    setResponseStage(ResponseStage.READ_REPLY_VARIABLE);
                    fillInterested();
                    break;
                case CONNECTED_IPV4:
                case READ_REPLY_VARIABLE:
                    tunnel();
                    break;
                default:
                    throw new SocketException("BAD SOCKS5 PROTOCOL");
            }
        }

        private void tunnel() 
        {
            try 
            {
                HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
                // Don't want to do DNS resolution here.
                InetSocketAddress address = InetSocketAddress.createUnresolved(destination.getHost(), destination.getPort());
                context.put(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY, address);
                ClientConnectionFactory connectionFactory = this.connectionFactory;
                if (destination.isSecure())
                {
                    connectionFactory = destination.newSslClientConnectionFactory(null, connectionFactory);
                }
                org.eclipse.jetty.io.Connection newConnection = connectionFactory.newConnection(getEndPoint(), context);
                getEndPoint().upgrade(newConnection);
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("SOCKS5 tunnel established: {} over {}", this, newConnection);
                }
            } 
            catch (Exception e) 
            {
                this.failed(e);
            }
        }

        void setResponseStage(ResponseStage responseStage) 
        {
            LOG.debug("set responseStage to {}", responseStage);
            this.responseStage = responseStage;
        }

        private class Socks5Parser 
        {
            private final int expectedLength;
            private final byte[] bs;
            private int cursor;

            private Socks5Parser() 
            {
                switch (Socks5ProxyConnection.this.responseStage)
                {
                    case INIT:
                        expectedLength = 2;
                        break;
                    case AUTH:
                        expectedLength = 2;
                        break;
                    case CONNECTING:
                        expectedLength = 4;
                        break;
                    case CONNECTED_IPV4:
                        expectedLength = 6;
                        break;
                    case CONNECTED_IPV6:
                        expectedLength = 1;
                        break;
                    case CONNECTED_DOMAIN_NAME:
                        expectedLength = 1;
                        break;
                    case READ_REPLY_VARIABLE:
                        expectedLength = Socks5ProxyConnection.this.variableLen;
                        break;
                    default:
                        expectedLength = 0;
                        break;
                }
                bs = new byte[expectedLength];
            }

            private boolean parse(ByteBuffer buffer) throws SocketException 
            {
                while (buffer.hasRemaining()) 
                {
                    byte current = buffer.get();
                    bs[cursor] = current;

                    ++this.cursor;
                    if (this.cursor != expectedLength) 
                    {
                        continue;
                    }

                    onSocks5Response(bs);
                    return true;
                }
                return false;
            }

            private int expected() 
            {
                return expectedLength - this.cursor;
            }
        }
    }

    public static class Socks5ProxyClientConnectionFactory implements ClientConnectionFactory 
    {
        private final ClientConnectionFactory connectionFactory;
        private final LinkedHashMap<Byte, Authentication> authorizations;

        public Socks5ProxyClientConnectionFactory(ClientConnectionFactory connectionFactory, LinkedHashMap<Byte, Authentication> authorizations) 
        {
            this.connectionFactory = connectionFactory;
            this.authorizations = authorizations;
        }

        public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) 
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            Executor executor = destination.getHttpClient().getExecutor();
            Socks5ProxyConnection connection = new Socks5ProxyConnection(endPoint, executor, this.connectionFactory, context);
            connection.authorizations = authorizations;
            return this.customize(connection, context);
        }
    }
}
