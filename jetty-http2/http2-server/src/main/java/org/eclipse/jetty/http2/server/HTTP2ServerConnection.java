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

import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.ConcurrentArrayQueue;

class HTTP2ServerConnection extends HTTP2Connection
{    
    private final ServerSessionListener listener;
    private final Queue<HttpChannelOverHTTP2> channels = new ConcurrentArrayQueue<>();
    private final HttpConfiguration httpConfig;

    HTTP2ServerConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, HttpConfiguration httpConfig, Parser parser, ISession session, int inputBufferSize, boolean dispatchIO, ServerSessionListener listener)
    {
        super(byteBufferPool, executor, endPoint, parser, session, inputBufferSize, dispatchIO);
        this.listener = listener;
        this.httpConfig = httpConfig;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        notifyConnect(getSession());
    }

    private void notifyConnect(ISession session)
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

    public HttpChannelOverHTTP2 newHttpChannelOverHTTP2(Connector connector, Stream stream)
    {
        HttpChannelOverHTTP2 channel = channels.poll();
        if (channel!=null)
        {
            channel.getHttp2Transport().setStream((IStream)stream);
            if (LOG.isDebugEnabled())
                LOG.debug("recycled :{}/{}",channel,this);
        }
        else
        {
            channel = new HttpChannelOverHTTP2(connector, httpConfig, getEndPoint(), new HttpTransportOverHTTP2(connector, httpConfig, getEndPoint(), (IStream)stream))
            {
                @Override
                public void onCompleted()
                {
                    super.onCompleted();
                    recycle();
                    channels.add(this);
                } 
            };
            if (LOG.isDebugEnabled())
                LOG.debug("new :{}/{}",channel,this);
        }
        stream.setAttribute(IStream.CHANNEL_ATTRIBUTE, channel);
        return channel;
    }
    
    public boolean onNewStream(Connector connector, Stream stream, HeadersFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Processing {} on {}", frame, stream);

        HttpChannelOverHTTP2 channel = newHttpChannelOverHTTP2(connector,stream);
        channel.onRequest(frame);
        return frame.isEndStream() ? false : true;
    }
}