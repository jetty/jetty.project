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

package org.eclipse.jetty.http2.server;

import java.util.Queue;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.ConcurrentArrayQueue;

public class HTTP2ServerConnection extends HTTP2Connection
{
    private final Queue<HttpChannelOverHTTP2> channels = new ConcurrentArrayQueue<>();
    private final ServerSessionListener listener;
    private final HttpConfiguration httpConfig;

    public HTTP2ServerConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, HttpConfiguration httpConfig, Parser parser, ISession session, int inputBufferSize, ServerSessionListener listener)
    {
        super(byteBufferPool, executor, endPoint, parser, session, inputBufferSize);
        this.listener = listener;
        this.httpConfig = httpConfig;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        notifyAccept(getSession());
    }

    private void notifyAccept(ISession session)
    {
        try
        {
            listener.onAccept(session);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    public void onNewStream(Connector connector, IStream stream, HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing {} on {}", frame, stream);
        HttpChannelOverHTTP2 channel = provideHttpChannel(connector, stream);
        Runnable task = channel.onRequest(frame);
        offerTask(task);
    }

    public void onPush(Connector connector, IStream stream, MetaData.Request request)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing push {} on {}", request, stream);
        HttpChannelOverHTTP2 channel = provideHttpChannel(connector, stream);
        Runnable task = channel.onPushRequest(request);
        offerTask(task);
    }

    private HttpChannelOverHTTP2 provideHttpChannel(Connector connector, IStream stream)
    {
        HttpChannelOverHTTP2 channel = channels.poll();
        if (channel != null)
        {
            channel.getHttpTransport().setStream(stream);
            if (LOG.isDebugEnabled())
                LOG.debug("Recycling channel {} for {}", channel, this);
        }
        else
        {
            HttpTransportOverHTTP2 transport = new HttpTransportOverHTTP2(connector, this);
            transport.setStream(stream);
            channel = new HttpChannelOverHTTP2(connector, httpConfig, getEndPoint(), transport)
            {
                @Override
                public void onCompleted()
                {
                    super.onCompleted();
                    recycle();
                    channels.offer(this);
                }
            };
            if (LOG.isDebugEnabled())
                LOG.debug("Creating channel {} for {}", channel, this);
        }
        stream.setAttribute(IStream.CHANNEL_ATTRIBUTE, channel);
        return channel;
    }
}
