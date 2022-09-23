//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.server.internal;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.http3.internal.HTTP3StreamConnection;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.HostPort;

public class ServerHTTP3StreamConnection extends HTTP3StreamConnection implements ConnectionMetaData
{
    private final HttpChannel.Factory httpChannelFactory = new HttpChannel.DefaultFactory();
    private final Attributes attributes = new Attributes.Lazy();
    private final Connector connector;
    private final HttpConfiguration httpConfiguration;
    private final ServerHTTP3Session session;

    public ServerHTTP3StreamConnection(Connector connector, HttpConfiguration httpConfiguration, QuicStreamEndPoint endPoint, ServerHTTP3Session session, MessageParser parser)
    {
        super(endPoint, connector.getExecutor(), connector.getByteBufferPool(), parser);
        this.connector = connector;
        this.httpConfiguration = httpConfiguration;
        this.session = session;
    }

    public Runnable onRequest(HTTP3StreamServer stream, HeadersFrame frame)
    {
        HttpChannel httpChannel = httpChannelFactory.newHttpChannel(this);
        HttpStreamOverHTTP3 httpStream = new HttpStreamOverHTTP3(this, httpChannel, stream);
        httpChannel.setHttpStream(httpStream);
        stream.setAttachment(httpStream);
        return httpStream.onRequest(frame);
    }

    public Runnable onDataAvailable(HTTP3Stream stream)
    {
        HttpStreamOverHTTP3 httpStream = (HttpStreamOverHTTP3)stream.getAttachment();
        return httpStream.onDataAvailable();
    }

    public Runnable onTrailer(HTTP3Stream stream, HeadersFrame frame)
    {
        HttpStreamOverHTTP3 httpStream = (HttpStreamOverHTTP3)stream.getAttachment();
        return httpStream.onTrailer(frame);
    }

    public boolean onIdleTimeout(HTTP3Stream stream, Throwable failure, Consumer<Runnable> consumer)
    {
        HttpStreamOverHTTP3 httpStream = (HttpStreamOverHTTP3)stream.getAttachment();
        return httpStream.onIdleTimeout(failure, consumer);
    }

    public Runnable onFailure(HTTP3Stream stream, Throwable failure)
    {
        HttpStreamOverHTTP3 httpStream = (HttpStreamOverHTTP3)stream.getAttachment();
        return httpStream.onFailure(failure);
    }

    void offer(Runnable task)
    {
        session.offer(task, false);
    }

    @Override
    public String getId()
    {
        return session.getQuicSession().getConnectionId().toString();
    }

    @Override
    public HttpConfiguration getHttpConfiguration()
    {
        return httpConfiguration;
    }

    @Override
    public HttpVersion getHttpVersion()
    {
        return HttpVersion.HTTP_3;
    }

    @Override
    public String getProtocol()
    {
        return getHttpVersion().asString();
    }

    @Override
    public Connection getConnection()
    {
        return getEndPoint().getConnection();
    }

    @Override
    public Connector getConnector()
    {
        return connector;
    }

    @Override
    public boolean isPersistent()
    {
        return true;
    }

    @Override
    public boolean isSecure()
    {
        return true;
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return getEndPoint().getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return getEndPoint().getLocalSocketAddress();
    }

    @Override
    public HostPort getServerAuthority()
    {
        HostPort override = httpConfiguration.getServerAuthority();
        if (override != null)
            return override;

        // TODO cache the HostPort?
        SocketAddress addr = getLocalSocketAddress();
        if (addr instanceof InetSocketAddress inet)
            return new HostPort(inet.getHostString(), inet.getPort());
        return new HostPort(addr.toString(), -1);
    }

    @Override
    public Object getAttribute(String name)
    {
        return attributes.getAttribute(name);
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return attributes.setAttribute(name, attribute);
    }

    @Override
    public Object removeAttribute(String name)
    {
        return attributes.removeAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return attributes.getAttributeNameSet();
    }

    @Override
    public void clearAttributes()
    {
        attributes.clearAttributes();
    }
}
