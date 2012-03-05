/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerHTTPSPDYAsyncConnectionFactory extends ServerSPDYAsyncConnectionFactory
{
    private static final String CONNECTION_ATTRIBUTE = "org.eclipse.jetty.spdy.http.connection";
    private static final Logger logger = LoggerFactory.getLogger(ServerHTTPSPDYAsyncConnectionFactory.class);

    private final Connector connector;

    public ServerHTTPSPDYAsyncConnectionFactory(short version, ByteBufferPool bufferPool, Executor threadPool, ScheduledExecutorService scheduler, Connector connector)
    {
        super(version, bufferPool, threadPool, scheduler);
        this.connector = connector;
    }

    @Override
    protected ServerSessionFrameListener newServerSessionFrameListener(AsyncEndPoint endPoint, Object attachment)
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

            HTTPSPDYAsyncEndPoint asyncEndPoint = new HTTPSPDYAsyncEndPoint(stream);
            ServerHTTPSPDYAsyncConnection connection = new ServerHTTPSPDYAsyncConnection(connector,
                    asyncEndPoint, connector.getServer(),
                    (SPDYAsyncConnection)endPoint.getConnection(), stream);
            asyncEndPoint.setConnection(connection);
            stream.setAttribute(CONNECTION_ATTRIBUTE, connection);

            Headers headers = synInfo.getHeaders();
            connection.beginRequest(headers);

            if (headers.isEmpty())
            {
                // If the SYN has no headers, they may come later in a HEADERS frame
                return this;
            }
            else
            {
                if (synInfo.isClose())
                {
                    connection.endRequest();
                    return null;
                }
                else
                {
                    return this;
                }
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
        private final Stream stream;

        public HTTPSPDYAsyncEndPoint(Stream stream)
        {
            this.stream = stream;
        }

        @Override
        public void asyncDispatch()
        {
            ServerHTTPSPDYAsyncConnection connection = (ServerHTTPSPDYAsyncConnection)stream.getAttribute(CONNECTION_ATTRIBUTE);
            connection.async();
        }
    }
}
