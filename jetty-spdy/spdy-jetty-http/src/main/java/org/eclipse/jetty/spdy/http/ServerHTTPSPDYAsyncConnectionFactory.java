//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.spdy.ByteBufferPool;
import org.eclipse.jetty.spdy.EmptyAsyncEndPoint;
import org.eclipse.jetty.spdy.SPDYAsyncConnection;
import org.eclipse.jetty.spdy.ServerSPDYAsyncConnectionFactory;
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

public class ServerHTTPSPDYAsyncConnectionFactory extends ServerSPDYAsyncConnectionFactory
{
    private static final String CONNECTION_ATTRIBUTE = "org.eclipse.jetty.spdy.http.connection";
    private static final Logger logger = Log.getLogger(ServerHTTPSPDYAsyncConnectionFactory.class);

    private final Connector connector;
    private final PushStrategy pushStrategy;

    public ServerHTTPSPDYAsyncConnectionFactory(short version, ByteBufferPool bufferPool, Executor threadPool, ScheduledExecutorService scheduler, Connector connector, PushStrategy pushStrategy)
    {
        super(version, bufferPool, threadPool, scheduler);
        this.connector = connector;
        this.pushStrategy = pushStrategy;
    }

    @Override
    protected ServerSessionFrameListener provideServerSessionFrameListener(AsyncEndPoint endPoint, Object attachment)
    {
        return new HTTPServerFrameListener(endPoint);
    }

    private class HTTPServerFrameListener extends ServerSessionFrameListener.Adapter implements StreamFrameListener
    {
        private final AsyncEndPoint endPoint;

        public HTTPServerFrameListener(AsyncEndPoint endPoint)
        {
            this.endPoint = endPoint;
        }

        @Override
        public StreamFrameListener onSyn(final Stream stream, SynInfo synInfo)
        {
            // Every time we have a SYN, it maps to a HTTP request.
            // We can have multiple concurrent SYNs on the same connection,
            // and this is very different from HTTP, where only one request/response
            // cycle is processed at a time, so we need to fake an http connection
            // for each SYN in order to run concurrently.

            logger.debug("Received {} on {}", synInfo, stream);

            HTTPSPDYAsyncEndPoint asyncEndPoint = new HTTPSPDYAsyncEndPoint(endPoint, stream);
            ServerHTTPSPDYAsyncConnection connection = new ServerHTTPSPDYAsyncConnection(connector, asyncEndPoint,
                    connector.getServer(), getVersion(), (SPDYAsyncConnection)endPoint.getConnection(),
                    pushStrategy, stream);
            asyncEndPoint.setConnection(connection);
            stream.setAttribute(CONNECTION_ATTRIBUTE, connection);

            Headers headers = synInfo.getHeaders();
            connection.beginRequest(headers, synInfo.isClose());

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
            ServerHTTPSPDYAsyncConnection connection = (ServerHTTPSPDYAsyncConnection)stream.getAttribute(CONNECTION_ATTRIBUTE);
            connection.headers(headersInfo.getHeaders());
            if (headersInfo.isClose())
                connection.endRequest();
        }

        @Override
        public void onData(Stream stream, DataInfo dataInfo)
        {
            logger.debug("Received {} on {}", dataInfo, stream);
            ServerHTTPSPDYAsyncConnection connection = (ServerHTTPSPDYAsyncConnection)stream.getAttribute(CONNECTION_ATTRIBUTE);
            connection.content(dataInfo, dataInfo.isClose());
            if (dataInfo.isClose())
                connection.endRequest();
        }
    }

    private class HTTPSPDYAsyncEndPoint extends EmptyAsyncEndPoint
    {
        private final AsyncEndPoint endPoint;
        private final Stream stream;

        private HTTPSPDYAsyncEndPoint(AsyncEndPoint endPoint, Stream stream)
        {
            this.endPoint = endPoint;
            this.stream = stream;
        }

        @Override
        public void asyncDispatch()
        {
            ServerHTTPSPDYAsyncConnection connection = (ServerHTTPSPDYAsyncConnection)stream.getAttribute(CONNECTION_ATTRIBUTE);
            connection.async();
        }

        @Override
        public String getLocalAddr()
        {
            return endPoint.getLocalAddr();
        }

        @Override
        public String getLocalHost()
        {
            return endPoint.getLocalHost();
        }

        @Override
        public int getLocalPort()
        {
            return endPoint.getLocalPort();
        }

        @Override
        public String getRemoteAddr()
        {
            return endPoint.getRemoteAddr();
        }

        @Override
        public String getRemoteHost()
        {
            return endPoint.getRemoteHost();
        }

        @Override
        public int getRemotePort()
        {
            return endPoint.getRemotePort();
        }
    }
}
