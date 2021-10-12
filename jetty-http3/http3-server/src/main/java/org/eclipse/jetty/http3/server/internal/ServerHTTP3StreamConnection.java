//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.http3.internal.HTTP3StreamConnection;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpTransport;

public class ServerHTTP3StreamConnection extends HTTP3StreamConnection
{
    private final Connector connector;
    private final HttpConfiguration httpConfiguration;
    private final ServerHTTP3Session http3Session;

    public ServerHTTP3StreamConnection(Connector connector, HttpConfiguration httpConfiguration, QuicStreamEndPoint endPoint, ServerHTTP3Session http3Session, MessageParser parser)
    {
        super(endPoint, connector.getExecutor(), connector.getByteBufferPool(), parser);
        this.connector = connector;
        this.httpConfiguration = httpConfiguration;
        this.http3Session = http3Session;
    }

    @Override
    protected void onDataAvailable(long streamId)
    {
        http3Session.onDataAvailable(streamId);
    }

    public Runnable onRequest(HTTP3Stream stream, HeadersFrame frame)
    {
        HttpTransport transport = new HttpTransportOverHTTP3(stream);
        HttpChannel channel = new HttpChannelOverHTTP3(connector, httpConfiguration, getEndPoint(), transport, stream, this);
        stream.setAttachment(channel);
        channel.onRequest(((MetaData.Request)frame.getMetaData()));
        return channel;
    }

    public void onDataAvailable(HTTP3Stream stream)
    {
        HttpChannel channel = (HttpChannel)stream.getAttachment();
        if (channel.getRequest().getHttpInput().onContentProducible())
            channel.handle();
    }

    public void onTrailer(HTTP3Stream stream, HeadersFrame frame)
    {
        HttpChannel channel = (HttpChannel)stream.getAttachment();
        channel.onTrailers(frame.getMetaData().getFields());
    }
}
