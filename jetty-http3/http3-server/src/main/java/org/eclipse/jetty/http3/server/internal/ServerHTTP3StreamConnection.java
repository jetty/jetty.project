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

import java.util.function.Consumer;

import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.http3.internal.HTTP3StreamConnection;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;

public class ServerHTTP3StreamConnection extends HTTP3StreamConnection
{
    private final Connector connector;
    private final HttpConfiguration httpConfiguration;
    private final ServerHTTP3Session session;

    public ServerHTTP3StreamConnection(Connector connector, HttpConfiguration httpConfiguration, QuicStreamEndPoint endPoint, ServerHTTP3Session session, MessageParser parser)
    {
        super(endPoint, connector.getExecutor(), connector.getRetainableByteBufferPool(), parser);
        this.connector = connector;
        this.httpConfiguration = httpConfiguration;
        this.session = session;
    }

    @Override
    protected void onDataAvailable(long streamId)
    {
        session.onDataAvailable(streamId);
    }

    public Runnable onRequest(HTTP3StreamServer stream, HeadersFrame frame)
    {
        HttpTransportOverHTTP3 transport = new HttpTransportOverHTTP3(stream);
        HttpChannelOverHTTP3 channel = new HttpChannelOverHTTP3(connector, httpConfiguration, getEndPoint(), transport, stream, this);
        stream.setAttachment(channel);
        return channel.onRequest(frame);
    }

    public Runnable onDataAvailable(HTTP3Stream stream)
    {
        HttpChannelOverHTTP3 channel = (HttpChannelOverHTTP3)stream.getAttachment();
        return channel.onDataAvailable();
    }

    public Runnable onTrailer(HTTP3Stream stream, HeadersFrame frame)
    {
        HttpChannelOverHTTP3 channel = (HttpChannelOverHTTP3)stream.getAttachment();
        return channel.onTrailer(frame);
    }

    public boolean onIdleTimeout(HTTP3Stream stream, Throwable failure, Consumer<Runnable> consumer)
    {
        HttpChannelOverHTTP3 channel = (HttpChannelOverHTTP3)stream.getAttachment();
        return channel.onIdleTimeout(failure, consumer);
    }

    public Runnable onFailure(HTTP3Stream stream, Throwable failure)
    {
        HttpChannelOverHTTP3 channel = (HttpChannelOverHTTP3)stream.getAttachment();
        return channel.onFailure(failure);
    }
}
