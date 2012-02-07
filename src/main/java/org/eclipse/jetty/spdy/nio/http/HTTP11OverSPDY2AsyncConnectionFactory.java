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

package org.eclipse.jetty.spdy.nio.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.ServerSPDY2AsyncConnectionFactory;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.nio.EmptyAsyncEndPoint;

public class HTTP11OverSPDY2AsyncConnectionFactory extends ServerSPDY2AsyncConnectionFactory
{
    private final Connector connector;

    public HTTP11OverSPDY2AsyncConnectionFactory(Connector connector)
    {
        this.connector = connector;
    }

    @Override
    protected ServerSessionFrameListener newServerSessionFrameListener(AsyncEndPoint endPoint, Object attachment)
    {
        return new HTTPServerSessionFrameListener();
    }

    private class HTTPServerSessionFrameListener extends ServerSessionFrameListener.Adapter implements Stream.FrameListener
    {
        @Override
        public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
        {
            // Every time we have a SYN, it maps to a HTTP request.
            // We can have multiple concurrent SYNs on the same connection,
            // and this is very different from HTTP, where only one request/response
            // cycle is processed at a time, so we need to fake an http connection
            // for each SYN in order to run concurrently.

            try
            {
                HTTPSPDYConnection connection = new HTTPSPDYConnection(connector, new HTTPSPDYAsyncEndPoint(stream), connector.getServer(), stream);
                stream.setAttribute("connection", connection);

                Headers headers = synInfo.getHeaders();
                if (headers.isEmpty())
                {
                    // SYN with no headers, perhaps they'll come in a HEADER frame
                    return this;
                }
                else
                {
                    boolean processed = processRequest(stream, headers);
                    if (!processed)
                    {
                        respond(stream, HttpStatus.BAD_REQUEST_400);
                        return null;
                    }

                    if (synInfo.isClose())
                    {
                        forwardHeadersComplete(stream);
                        forwardRequestComplete(stream);
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

        private boolean processRequest(Stream stream, Headers headers) throws IOException
        {
            Boolean requestSeen = (Boolean)stream.getAttribute("request");
            if (requestSeen == null || !requestSeen)
            {
                stream.setAttribute("request", Boolean.TRUE);

                Headers.Header method = headers.get("method");
                Headers.Header uri = headers.get("url");
                Headers.Header version = headers.get("version");

                if (method == null || uri == null || version == null)
                    return false;

                forwardRequest(stream, method.value(), uri.value(), version.value());
            }
            forwardHeaders(stream, headers);
            return true;
        }

        @Override
        public void onReply(Stream stream, ReplyInfo replyInfo)
        {
            // Do nothing, servers cannot get replies
        }

        @Override
        public void onHeaders(Stream stream, HeadersInfo headersInfo)
        {
            // TODO: support trailers
            Boolean dataSeen = (Boolean)stream.getAttribute("data");
            if (dataSeen != null && dataSeen)
                return;

            try
            {
                processRequest(stream, headersInfo.getHeaders());

                if (headersInfo.isClose())
                {
                    forwardHeadersComplete(stream);
                    forwardRequestComplete(stream);
                }
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
            try
            {
                forwardHeadersComplete(stream);

                stream.setAttribute("data", Boolean.TRUE);

                ByteBuffer buffer = ByteBuffer.allocate(dataInfo.getBytesCount());
                dataInfo.getBytes(buffer);
                forwardContent(stream, buffer);
                if (dataInfo.isClose())
                    forwardRequestComplete(stream);
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

        private void forwardRequest(Stream stream, String method, String uri, String version) throws IOException
        {
            HTTPSPDYConnection connection = (HTTPSPDYConnection)stream.getAttribute("connection");
            connection.startRequest(new ByteArrayBuffer(method), new ByteArrayBuffer(uri), new ByteArrayBuffer(version));
        }

        private void forwardHeaders(Stream stream, Headers headers) throws IOException
        {
            HTTPSPDYConnection connection = (HTTPSPDYConnection)stream.getAttribute("connection");
            for (Headers.Header header : headers)
            {
                String name = header.name();
                switch (name)
                {
                    case "method":
                    case "version":
                        // Skip request line headers
                        continue;
                    case "url":
                        // Mangle the URL if the host header is missing
                        String host = parseHost(header.value());
                        // Jetty needs the host header, although HTTP 1.1 does not
                        // require it if it can be parsed from an absolute URI
                        if (host != null)
                            connection.parsedHeader(new ByteArrayBuffer("host"), new ByteArrayBuffer(host));
                        break;
                    case "connection":
                    case "keep-alive":
                    case "host":
                        // Spec says to ignore these headers
                        continue;
                    default:
                        // Spec says headers must be single valued
                        String value = header.value();
                        connection.parsedHeader(new ByteArrayBuffer(name), new ByteArrayBuffer(value));
                        break;
                }
            }
        }

        private String parseHost(String url)
        {
            try
            {
                URI uri = new URI(url);
                return uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            }
            catch (URISyntaxException x)
            {
                return null;
            }
        }

        private void forwardHeadersComplete(Stream stream) throws IOException
        {
            HTTPSPDYConnection connection = (HTTPSPDYConnection)stream.getAttribute("connection");
            connection.headerComplete();
        }

        private void forwardContent(Stream stream, ByteBuffer buffer) throws IOException
        {
            HTTPSPDYConnection connection = (HTTPSPDYConnection)stream.getAttribute("connection");
            connection.content(new IndirectNIOBuffer(buffer, false));
        }

        private void forwardRequestComplete(Stream stream) throws IOException
        {
            HTTPSPDYConnection connection = (HTTPSPDYConnection)stream.getAttribute("connection");
            connection.messageComplete(0); // TODO: content length
        }

        private void close(Stream stream)
        {
            stream.getSession().goAway(stream.getVersion());
        }
    }

    private class HTTPSPDYConnection extends AbstractHttpConnection
    {
        private HTTPSPDYConnection(Connector connector, EndPoint endPoint, Server server, Stream stream)
        {
            super(connector, endPoint, server,
                    new HttpParser(connector.getRequestBuffers(), endPoint, new HTTPSPDYParserHandler()),
                    new HTTPSPDYGenerator(connector.getResponseBuffers(), endPoint, stream), new HTTPSPDYRequest());
            ((HTTPSPDYRequest)getRequest()).setConnection(this);
            getParser().setPersistent(true);
        }

        @Override
        public Connection handle() throws IOException
        {
            return this;
        }

        public void startRequest(Buffer method, Buffer uri, Buffer version) throws IOException
        {
            super.startRequest(method, uri, version);
        }

        public void parsedHeader(Buffer name, Buffer value) throws IOException
        {
            super.parsedHeader(name, value);
        }

        public void headerComplete() throws IOException
        {
            super.headerComplete();
        }

        public void content(Buffer buffer) throws IOException
        {
            super.content(buffer);
        }

        public void messageComplete(long contentLength) throws IOException
        {
            super.messageComplete(contentLength);
        }
    }

    private class HTTPSPDYAsyncEndPoint extends EmptyAsyncEndPoint
    {
        private final Stream stream;

        private HTTPSPDYAsyncEndPoint(Stream stream)
        {
            this.stream = stream;
        }
    }

    /**
     * Empty implementation, since it won't parse anything
     */
    private class HTTPSPDYParserHandler extends HttpParser.EventHandler
    {
        @Override
        public void startRequest(Buffer method, Buffer url, Buffer version) throws IOException
        {
        }

        @Override
        public void content(Buffer ref) throws IOException
        {
        }

        @Override
        public void startResponse(Buffer version, int status, Buffer reason) throws IOException
        {
        }
    }

    private class HTTPSPDYGenerator extends HttpGenerator
    {
        private final Stream stream;

        private HTTPSPDYGenerator(Buffers buffers, EndPoint endPoint, Stream stream)
        {
            super(buffers, endPoint);
            this.stream = stream;
        }

        @Override
        public void send1xx(int code) throws IOException
        {
            // TODO
        }

        @Override
        public void completeHeader(HttpFields fields, boolean allContentAdded) throws IOException
        {
            Headers headers = new Headers();
            StringBuilder status = new StringBuilder().append(_status);
            if (_reason != null)
                status.append(" ").append(_reason.toString("UTF-8"));
            headers.put("status", status.toString());
            headers.put("version", "HTTP/1.1");
            for (int i = 0; i < fields.size(); ++i)
            {
                HttpFields.Field field = fields.getField(i);
                headers.put(field.getName(), field.getValue());
            }
            stream.reply(new ReplyInfo(headers, allContentAdded));
        }

        @Override
        public void addContent(Buffer content, boolean last) throws IOException
        {
            // TODO
        }

        @Override
        public void complete() throws IOException
        {
            // Nothing to do
        }
    }

    /**
     * Needed only to please the compiler
     */
    private class HTTPSPDYRequest extends Request
    {
        private void setConnection(HTTPSPDYConnection connection)
        {
            super.setConnection(connection);
        }
    }
}
