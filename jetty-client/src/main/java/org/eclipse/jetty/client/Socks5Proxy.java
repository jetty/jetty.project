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

import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jetty.client.Origin.Address;
import org.eclipse.jetty.client.ProxyConfiguration.Proxy;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Socks5Proxy extends Proxy 
{

    private static final Logger log = LoggerFactory.getLogger(Socks5Proxy.class);

    private String username;
    private String password;

    public Socks5Proxy(String host, int port)
    {
        this(new Origin.Address(host, port), false);
    }

    public Socks5Proxy(String host, int port, String username, String password)
    {
        this(new Address(host, port), false);
        this.username = username;
        this.password = password;
    }

    public Socks5Proxy(Origin.Address address, boolean secure)
    {
        super(address, secure, null, null);
    }

    @Override
    public ClientConnectionFactory newClientConnectionFactory(ClientConnectionFactory connectionFactory) 
    {
        return new Socks5ProxyClientConnectionFactory(connectionFactory, username, password);
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

        private String username;
        private String password;

        private RequestStage requestStage = RequestStage.Init;
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
                case Init:
                    ByteBuffer init = ByteBuffer.allocate(4);
                    if(username == null || username.isEmpty())
                    {
                        init.put(SockConst.VER).put((byte)1).put(AuthType.NO_AUTH);
                        init.flip();
                    } 
                    else 
                    {
                        init.put(SockConst.VER).put((byte)2).put(AuthType.NO_AUTH).put(AuthType.USER_PASS);
                        init.flip();
                    }
                    setResponseStage(ResponseStage.Init);
                    this.getEndPoint().write(this, init);
                    break;
                case Auth:
                    byte uLen = (byte) username.length();
                    byte pLen = (byte) (password == null ? 0 : password.length());
                    ByteBuffer userPass = ByteBuffer.allocate(3 + uLen + pLen);
                    userPass.put(SockConst.UserPassVer)
                        .put(uLen)
                        .put(username.getBytes(StandardCharsets.UTF_8))
                        .put(pLen)
                        .put(password.getBytes(StandardCharsets.UTF_8));
                    userPass.flip();
                    setResponseStage(ResponseStage.Auth);
                    this.getEndPoint().write(this, userPass);
                    break;
                case Connecting:
                    HttpDestination destination = (HttpDestination)this.context.get("http.destination");
                    String host = destination.getHost();
                    short port = (short)destination.getPort();
                    setResponseStage(ResponseStage.Connecting);
                    Matcher matcher = IPv4_PATTERN.matcher(host);
                    if (matcher.matches()) 
                    {
                        // ip
                        ByteBuffer buffer = ByteBuffer.allocate(10);
                        buffer.put(SockConst.VER)
                            .put(CMD.CONNECT)
                            .put(SockConst.RSV)
                            .put(AddrType.IPV4);
                        for(int i = 1; i <= 4; ++i) 
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
                            .put(CMD.CONNECT)
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
            if (log.isDebugEnabled()) 
            {
                log.debug("Written SOCKS5 handshake request");
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
                    if (log.isDebugEnabled()) 
                    {
                        log.debug("Read SOCKS5 connect response, {} bytes", (long)filled);
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
                while(!parser.parse(buffer));
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
                case Init:
                    if(bs[0] != SockConst.VER)
                    {
                        throw new SocketException("SOCKS5 tunnel failed with err VER " + bs[0]);
                    }
                    if(bs[1] == AuthType.NO_AUTH)
                    {
                        requestStage = RequestStage.Connecting;
                        writeHandshakeCmd();
                    } 
                    else if(bs[1] == AuthType.USER_PASS)
                    {
                        requestStage = RequestStage.Auth;
                        writeHandshakeCmd();
                    } 
                    else if(bs[1] == AuthType.NO_ACCEPTABLE)
                    {
                        throw new SocketException("SOCKS : No acceptable methods");
                    } 
                    else 
                    {
                        throw new SocketException("SOCKS5 tunnel failed with unknown auth type");
                    }
                    break;
                case Auth:
                    if(bs[0] != SockConst.UserPassVer)
                    {
                        throw new SocketException("SOCKS5 tunnel failed with err UserPassVer " + bs[0]);
                    }
                    if(bs[1] != SockConst.SUCCEEDED)
                    {
                        throw new SocketException("SOCKS : authentication failed");
                    }
                    // authorization successful
                    requestStage = RequestStage.Connecting;
                    writeHandshakeCmd();
                    break;
                case Connecting:
                    if(bs[0] != SockConst.VER)
                    {
                        throw new SocketException("SOCKS5 tunnel failed with err VER " + bs[0]);
                    }
                    switch (bs[1])
                    {
                        case SockConst.SUCCEEDED:
                            switch (bs[3])
                            {
                                case AddrType.IPV4:
                                    setResponseStage(ResponseStage.ConnectedIpV4);
                                    fillInterested();
                                    break;
                                case AddrType.DOMAIN_NAME:
                                    setResponseStage(ResponseStage.ConnectedDomainName);
                                    fillInterested();
                                    break;
                                case AddrType.IPV6:
                                    setResponseStage(ResponseStage.ConnectedIpV6);
                                    fillInterested();
                                    break;
                                default:
                                    throw new SocketException("SOCKS: unknown addr type " + bs[3]);
                            }
                            break;
                        case REPLY.GENERAL:
                            throw new SocketException("SOCKS server general failure");
                        case REPLY.RULE_BAN:
                            throw new SocketException("SOCKS: Connection not allowed by ruleset");
                        case REPLY.NETWORK_UNREACHABLE:
                            throw new SocketException("SOCKS: Network unreachable");
                        case REPLY.HOST_UNREACHABLE:
                            throw new SocketException("SOCKS: Host unreachable");
                        case REPLY.CONNECT_REFUSE:
                            throw new SocketException("SOCKS: Connection refused");
                        case REPLY.TTL_TIMEOUT:
                            throw new SocketException("SOCKS: TTL expired");
                        case REPLY.CMD_UNSUPPORTED:
                            throw new SocketException("SOCKS: Command not supported");
                        case REPLY.ATYPE_UNSUPPORTED:
                            throw new SocketException("SOCKS: address type not supported");
                        default:
                            throw new SocketException("SOCKS: unknown code " + bs[1]);
                    }
                    break;
                case ConnectedDomainName:
                case ConnectedIpV6:
                    variableLen = 2 + bs[0];
                    setResponseStage(ResponseStage.READ_REPLY_VARIABLE);
                    fillInterested();
                    break;
                case ConnectedIpV4:
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
                HttpDestination destination = (HttpDestination)this.context.get("http.destination");
                this.context.put("ssl.peer.host", destination.getHost());
                this.context.put("ssl.peer.port", destination.getPort());
                ClientConnectionFactory connectionFactory = this.connectionFactory;
                if (destination.isSecure()) 
                {
                    connectionFactory = new SslClientConnectionFactory(
                        destination.getHttpClient().getSslContextFactory(),
                        destination.getHttpClient().getByteBufferPool(),
                        destination.getHttpClient().getExecutor(),
                        connectionFactory);
                }

                org.eclipse.jetty.io.Connection newConnection = connectionFactory.newConnection(this.getEndPoint(), this.context);
                this.getEndPoint().upgrade(newConnection);
                if (log.isDebugEnabled()) 
                {
                    log.debug("SOCKS5 tunnel established: {} over {}", this, newConnection);
                }
            } 
            catch (Exception e) 
            {
                this.failed(e);
            }
        }

        void setResponseStage(ResponseStage responseStage) 
        {
            log.debug("set responseStage to {}", responseStage);
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
                    case Init:
                        expectedLength = 2;
                        break;
                    case Auth:
                        expectedLength = 2;
                        break;
                    case Connecting:
                        expectedLength = 4;
                        break;
                    case ConnectedIpV4:
                        expectedLength = 6;
                        break;
                    case ConnectedIpV6:
                        expectedLength = 1;
                        break;
                    case ConnectedDomainName:
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

        private enum RequestStage 
        {
            Init,
            Auth,
            Connecting
        }

        private enum ResponseStage 
        {
            Init,
            Auth,
            Connecting,
            ConnectedIpV4,
            ConnectedDomainName,
            ConnectedIpV6,
            READ_REPLY_VARIABLE
        }

        private interface SockConst
        {
            byte VER = 0x05;
            byte UserPassVer = 0x01;
            byte RSV = 0x00;
            byte SUCCEEDED = 0x00;
        }

        private interface AuthType 
        {
            byte NO_AUTH = 0x00;
            byte USER_PASS = 0x02;
            byte NO_ACCEPTABLE = -1;
        }

        private interface CMD 
        {
            byte CONNECT = 0x01;
            byte BIND = 0x02;
            byte UDP = 0x03;
        }

        private interface REPLY 
        {
            byte GENERAL = 0x01;
            byte RULE_BAN = 0x02;
            byte NETWORK_UNREACHABLE = 0x03;
            byte HOST_UNREACHABLE = 0x04;
            byte CONNECT_REFUSE = 0x05;
            byte TTL_TIMEOUT = 0x06;
            byte CMD_UNSUPPORTED = 0x07;
            byte ATYPE_UNSUPPORTED = 0x08;
        }

        private interface AddrType 
        {
            byte IPV4 = 0x01;
            byte DOMAIN_NAME = 0x03;
            byte IPV6 = 0x04;
        }
    }

    public static class Socks5ProxyClientConnectionFactory implements ClientConnectionFactory 
    {
        private final ClientConnectionFactory connectionFactory;
        private final String username;
        private final String password;

        public Socks5ProxyClientConnectionFactory(ClientConnectionFactory connectionFactory, String username, String password) 
        {
            this.connectionFactory = connectionFactory;
            this.username = username;
            this.password = password;
        }

        public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) 
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            Executor executor = destination.getHttpClient().getExecutor();
            Socks5ProxyConnection connection = new Socks5ProxyConnection(endPoint, executor, this.connectionFactory, context);
            connection.username = username;
            connection.password = password;
            return this.customize(connection, context);
        }
    }
}
