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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.server.Connector;
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
    private static final Logger logger = LoggerFactory.getLogger(ServerHTTPSPDYAsyncConnectionFactory.class);
    private final Connector connector;

    public ServerHTTPSPDYAsyncConnectionFactory(ScheduledExecutorService scheduler, short version, Connector connector)
    {
        super(scheduler, version);
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
            // Furthermore, in order to avoid that one "slow" SYN blocks all other
            // SYNs that may be processed concurrently (for example when the
            // application is waiting for a JDBC connection), we dispatch to a new
            // thread when invoking the fake connection (that will call the application).
            // Dispatching must be ordered to avoid that client's data frames are
            // processed out of order.

            logger.debug("Received {} on {}", synInfo, stream);

            final ServerHTTPSPDYAsyncConnection connection = new ServerHTTPSPDYAsyncConnection(connector,
                    new EmptyAsyncEndPoint(), connector.getServer(),
                    (SPDYAsyncConnection)endPoint.getConnection(), stream);
            stream.setAttribute("connection", connection);
            final Headers headers = synInfo.getHeaders();
            final boolean isClose = synInfo.isClose();
            // If the SYN has no headers, they may come later in a HEADERS frame
            StreamFrameListener result = headers.isEmpty() || !isClose ? this : null;

            connection.post(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        connection.beginRequest(headers);
                        if (isClose)
                            connection.endRequest();
                    }
                    catch (HttpException x)
                    {
                        respond(stream, x.getStatus());
                    }
                    catch (IOException x)
                    {
                        close(stream);
                    }
                }
            });

            return result;
        }

        @Override
        public void onReply(Stream stream, ReplyInfo replyInfo)
        {
            // Do nothing, servers cannot get replies
        }

        @Override
        public void onHeaders(final Stream stream, final HeadersInfo headersInfo)
        {
            logger.debug("Received {} on {}", headersInfo, stream);

            final ServerHTTPSPDYAsyncConnection connection = (ServerHTTPSPDYAsyncConnection)stream.getAttribute("connection");
            final Headers headers = headersInfo.getHeaders();
            final boolean isClose = headersInfo.isClose();

            connection.post(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        connection.headers(headers);
                        if (isClose)
                            connection.endRequest();
                    }
                    catch (HttpException x)
                    {
                        respond(stream, x.getStatus());
                    }
                    catch (IOException x)
                    {
                        close(stream);
                    }
                }
            });
        }

        @Override
        public void onData(final Stream stream, DataInfo dataInfo)
        {
            logger.debug("Received {} on {}", dataInfo, stream);

            final ServerHTTPSPDYAsyncConnection connection = (ServerHTTPSPDYAsyncConnection)stream.getAttribute("connection");
            final ByteBuffer buffer = dataInfo.asByteBuffer();
            final boolean isClose = dataInfo.isClose();

            connection.post(new Runnable()
            {
                public void run()
                {
                    try
                    {
                        connection.content(buffer, isClose);
                        if (isClose)
                            connection.endRequest();
                    }
                    catch (HttpException x)
                    {
                        respond(stream, x.getStatus());
                    }
                    catch (IOException x)
                    {
                        close(stream);
                    }
                }
            });
        }

        private void respond(Stream stream, int status)
        {
            Headers headers = new Headers();
            headers.put("status", String.valueOf(status));
            headers.put("version", "HTTP/1.1");
            stream.reply(new ReplyInfo(headers, true));
        }

        private void close(Stream stream)
        {
            stream.getSession().goAway();
        }
    }
}
