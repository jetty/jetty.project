//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.http;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.spdy.ServerSPDYConnectionFactory;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ServerHTTPSPDYConnectionFactory extends ServerSPDYConnectionFactory
{
    private static final String CHANNEL_ATTRIBUTE = "org.eclipse.jetty.spdy.http.HTTPChannelOverSPDY";
    private static final Logger logger = Log.getLogger(ServerHTTPSPDYConnectionFactory.class);

    private final Connector connector;
    private final PushStrategy pushStrategy;
    private final HttpConfiguration configuration;

    public ServerHTTPSPDYConnectionFactory(short version, ByteBufferPool bufferPool, Executor threadPool, ScheduledExecutorService scheduler, Connector connector, PushStrategy pushStrategy)
    {
        super(version, bufferPool, threadPool, scheduler);
        this.connector = connector;
        this.pushStrategy = pushStrategy;
        this.configuration = new HttpConfiguration(connector.getSslContextFactory(), connector.getSslContextFactory() != null);
    }

    @Override
    protected ServerSessionFrameListener provideServerSessionFrameListener(EndPoint endPoint, Object attachment)
    {
        return new HTTPServerFrameListener(endPoint);
    }

    private class HTTPServerFrameListener extends ServerSessionFrameListener.Adapter implements StreamFrameListener
    {
        private final EndPoint endPoint;

        public HTTPServerFrameListener(EndPoint endPoint)
        {
            this.endPoint = endPoint;
        }

        @Override
        public StreamFrameListener onSyn(final Stream stream, SynInfo synInfo)
        {
            // Every time we have a SYN, it maps to a HTTP request.
            // We can have multiple concurrent SYNs on the same connection,
            // and this is very different from HTTP, where only one request
            // can arrive on the same connection, so we need to create an
            // HttpChannel for each SYN in order to run concurrently.

            logger.debug("Received {} on {}", synInfo, stream);

            Headers headers = synInfo.getHeaders();
            HttpTransportOverSPDY transport = new HttpTransportOverSPDY(connector, configuration, endPoint, pushStrategy, stream, headers);
            HttpInputOverSPDY input = new HttpInputOverSPDY();
            HttpChannelOverSPDY channel = new HttpChannelOverSPDY(connector, configuration, endPoint, transport, input, stream);
            stream.setAttribute(CHANNEL_ATTRIBUTE, channel);

            channel.requestStart(headers, synInfo.isClose());

            if (headers.isEmpty())
            {
                // If the SYN has no headers, they may come later in a HEADERS frame
                return this;
            }
            else
            {
                if (synInfo.isClose())
                    return null;
                else
                    return this;
            }
        }

        @Override
        public void onReply(Stream stream, ReplyInfo replyInfo)
        {
            // Do nothing, servers cannot get replies
        }

        @Override
        public void onHeaders(Stream stream, HeadersInfo headersInfo)
        {
            logger.debug("Received {} on {}", headersInfo, stream);
            HttpChannelOverSPDY channel = (HttpChannelOverSPDY)stream.getAttribute(CHANNEL_ATTRIBUTE);
            channel.requestHeaders(headersInfo.getHeaders(), headersInfo.isClose());
        }

        @Override
        public void onData(Stream stream, final DataInfo dataInfo)
        {
            logger.debug("Received {} on {}", dataInfo, stream);
            HttpChannelOverSPDY channel = (HttpChannelOverSPDY)stream.getAttribute(CHANNEL_ATTRIBUTE);
            channel.requestContent(dataInfo, dataInfo.isClose());
        }
    }
}
