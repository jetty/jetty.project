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
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerHTTPSPDYAsyncConnectionFactory extends ServerSPDYAsyncConnectionFactory
{
    private static final Logger logger = LoggerFactory.getLogger(ServerHTTPSPDYAsyncConnectionFactory.class);
    private final Connector connector;

    public ServerHTTPSPDYAsyncConnectionFactory(Connector connector)
    {
        this.connector = connector;
    }

    @Override
    protected ServerSessionFrameListener newServerSessionFrameListener(AsyncEndPoint endPoint, Object attachment)
    {
        return new HTTPServerSessionFrameListener(endPoint);
    }

    private class HTTPServerSessionFrameListener extends ServerSessionFrameListener.Adapter implements Stream.FrameListener
    {
        private final AsyncEndPoint endPoint;

        public HTTPServerSessionFrameListener(AsyncEndPoint endPoint)
        {
            this.endPoint = endPoint;
        }

        @Override
        public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
        {
            // Every time we have a SYN, it maps to a HTTP request.
            // We can have multiple concurrent SYNs on the same connection,
            // and this is very different from HTTP, where only one request/response
            // cycle is processed at a time, so we need to fake an http connection
            // for each SYN in order to run concurrently.

            logger.debug("Received {} on {}", synInfo, stream);

            try
            {
                ServerHTTPSPDYAsyncConnection connection = new ServerHTTPSPDYAsyncConnection(connector,
                        new EmptyAsyncEndPoint(), connector.getServer(),
                        (SPDYAsyncConnection)endPoint.getConnection(), stream);
                stream.setAttribute("connection", connection);

                Headers headers = synInfo.getHeaders();
                connection.beginRequest(headers);

                if (headers.isEmpty())
                {
                    // SYN with no headers, perhaps they'll come later in a HEADER frame
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
            catch (HttpException x)
            {
                respond(stream, x.getStatus());
                return null;
            }
            catch (IOException x)
            {
                close(stream);
                return null;
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

            try
            {
                ServerHTTPSPDYAsyncConnection connection = (ServerHTTPSPDYAsyncConnection)stream.getAttribute("connection");
                connection.headers(headersInfo.getHeaders());

                if (headersInfo.isClose())
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

        @Override
        public void onData(Stream stream, DataInfo dataInfo)
        {
            logger.debug("Received {} on {}", dataInfo, stream);

            try
            {
                ByteBuffer buffer = ByteBuffer.allocate(dataInfo.getBytesCount());
                dataInfo.getBytes(buffer);
                buffer.flip();

                ServerHTTPSPDYAsyncConnection connection = (ServerHTTPSPDYAsyncConnection)stream.getAttribute("connection");
                connection.content(buffer, dataInfo.isClose());

                if (dataInfo.isClose())
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

        private void respond(Stream stream, int status)
        {
            Headers headers = new Headers();
            headers.put("status", String.valueOf(status));
            headers.put("version", "HTTP/1.1");
            stream.reply(new ReplyInfo(headers, true));
        }

        private void close(Stream stream)
        {
            stream.getSession().goAway(stream.getVersion());
        }
    }
}
