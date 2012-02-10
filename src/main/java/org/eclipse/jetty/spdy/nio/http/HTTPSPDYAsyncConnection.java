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

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

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
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.nio.SPDYAsyncConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTPSPDYAsyncConnection extends AbstractHttpConnection implements AsyncConnection
{
    private static final Logger logger = LoggerFactory.getLogger(HTTPSPDYAsyncConnection.class);
    private final SPDYAsyncConnection connection;
    private final Stream stream;
    private Headers headers;
    private NIOBuffer buffer;
    private boolean complete;
    private volatile State state = State.INITIAL;

    public HTTPSPDYAsyncConnection(Connector connector, AsyncEndPoint endPoint, Server server, SPDYAsyncConnection connection, Stream stream)
    {
        super(connector, endPoint, server);
        this.connection = connection;
        this.stream = stream;
        getParser().setPersistent(true);
    }

    @Override
    protected HttpParser newHttpParser(Buffers requestBuffers, EndPoint endPoint, HttpParser.EventHandler requestHandler)
    {
        return new HTTPSPDYParser(requestBuffers, endPoint);
    }

    @Override
    protected HttpGenerator newHttpGenerator(Buffers responseBuffers, EndPoint endPoint)
    {
        return new HTTPSPDYGenerator(responseBuffers, endPoint);
    }

    @Override
    public AsyncEndPoint getEndPoint()
    {
        return (AsyncEndPoint)super.getEndPoint();
    }

    @Override
    public Connection handle() throws IOException
    {
        setCurrentConnection(this);
        try
        {
            switch (state)
            {
                case INITIAL:
                {
                    break;
                }
                case REQUEST:
                {
                    Headers.Header method = headers.get("method");
                    Headers.Header uri = headers.get("url");
                    Headers.Header version = headers.get("version");

                    if (method == null || uri == null || version == null)
                        throw new HttpException(HttpStatus.BAD_REQUEST_400);

                    String m = method.value();
                    String u = uri.value();
                    String v = version.value();
                    logger.debug("HTTP {} {} {}", new Object[]{m, u, v});
                    startRequest(new ByteArrayBuffer(m), new ByteArrayBuffer(u), new ByteArrayBuffer(v));

                    state = State.HEADERS;
                    handle();
                    break;
                }
                case HEADERS:
                {
                    for (Headers.Header header : headers)
                    {
                        String name = header.name();
                        switch (name)
                        {
                            case "method":
                            case "version":
                            {
                                // Skip request line headers
                                continue;
                            }
                            case "url":
                            {
                                // Mangle the URL if the host header is missing
                                String host = parseHost(header.value());
                                // Jetty needs the host header, although HTTP 1.1 does not
                                // require it if it can be parsed from an absolute URI
                                if (host != null)
                                {
                                    logger.debug("HTTP {}: {}", "host", host);
                                    parsedHeader(new ByteArrayBuffer("host"), new ByteArrayBuffer(host));
                                }
                                break;
                            }
                            case "connection":
                            case "keep-alive":
                            case "host":
                            {
                                // Spec says to ignore these headers
                                continue;
                            }
                            default:
                            {
                                // Spec says headers must be single valued
                                String value = header.value();
                                logger.debug("HTTP {}: {}", name, value);
                                parsedHeader(new ByteArrayBuffer(name), new ByteArrayBuffer(value));
                                break;
                            }
                        }
                    }
                    break;
                }
                case HEADERS_COMPLETE:
                {
                    headerComplete();
                    break;
                }
                case CONTENT:
                {
                    final Buffer buffer = this.buffer;
                    if (buffer.length() > 0)
                        content(buffer);
                    break;
                }
                case FINAL:
                {
                    // TODO: compute content-length parameter
                    messageComplete(0);
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
            return this;
        }
        finally
        {
            setCurrentConnection(null);
        }
    }

    @Override
    public void onInputShutdown() throws IOException
    {
        // TODO
    }

    public void beginRequest(Headers headers) throws IOException
    {
        if (!headers.isEmpty())
        {
            this.headers = headers;
            state = State.REQUEST;
        }
        handle();
    }

    public void headers(Headers headers) throws IOException
    {
        this.headers = headers;
        state = state == State.INITIAL ? State.REQUEST : State.HEADERS;
        handle();
    }

    public void content(ByteBuffer byteBuffer, boolean endRequest) throws IOException
    {
        buffer = byteBuffer.isDirect() ? new DirectNIOBuffer(byteBuffer, false) : new IndirectNIOBuffer(byteBuffer, false);
        complete = endRequest;
        logger.debug("HTTP {} bytes of content", buffer.length());
        if (state == State.HEADERS)
        {
            state = State.HEADERS_COMPLETE;
            handle();
        }
        state = State.CONTENT;
        handle();
    }

    public void endRequest() throws IOException
    {
        if (state == State.HEADERS)
        {
            state = State.HEADERS_COMPLETE;
            handle();
        }
        state = State.FINAL;
        handle();
    }

    private Buffer consumeContent(long maxIdleTime) throws IOException
    {
        boolean filled = false;
        while (true)
        {
            State state = this.state;
            if (state != State.HEADERS_COMPLETE && state != State.CONTENT && state != State.FINAL)
                throw new IllegalStateException();

            Buffer buffer = this.buffer;
            logger.debug("Consuming {} content bytes", buffer.length());
            if (buffer.length() > 0)
                return buffer;

            if (complete)
                return null;

            if (filled)
            {
                // Wait for content
                logger.debug("Waiting at most {} ms for content bytes", maxIdleTime);
                long begin = System.nanoTime();
                boolean expired = !connection.getEndPoint().blockReadable(maxIdleTime);
                if (expired)
                {
                    stream.getSession().goAway(stream.getVersion());
                    throw new EOFException("read timeout");
                }
                logger.debug("Waited {} ms for content bytes", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
            }

            // We need to parse more bytes; this may change the state
            // therefore we need to re-read the fields
            connection.fill();
            filled = true;
        }
    }

    private int availableContent()
    {
        // Volatile read to ensure visibility
        State state = this.state;
        if (state != State.HEADERS_COMPLETE && state != State.CONTENT)
            throw new IllegalStateException();
        return buffer.length();
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

    private enum State
    {
        INITIAL, REQUEST, HEADERS, HEADERS_COMPLETE, CONTENT, FINAL
    }

    /**
     * Needed in order to override parser methods that read content.
     * TODO: DESIGN: having the parser to block for content is messy, since the
     * TODO: DESIGN: state machine for that should be in the connection/interpreter
     */
    private class HTTPSPDYParser extends HttpParser
    {
        public HTTPSPDYParser(Buffers buffers, EndPoint endPoint)
        {
            super(buffers, endPoint, new HTTPSPDYParserHandler());
        }

        @Override
        public Buffer blockForContent(long maxIdleTime) throws IOException
        {
            return consumeContent(maxIdleTime);
        }

        @Override
        public int available() throws IOException
        {
            return availableContent();
        }
    }

    /**
     * Empty implementation, since it won't parse anything
     */
    private static class HTTPSPDYParserHandler extends HttpParser.EventHandler
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

    /**
     * Needed in order to override generator methods that would generate HTTP,
     * since we must generate SPDY instead.
     */
    private class HTTPSPDYGenerator extends HttpGenerator
    {
        private HTTPSPDYGenerator(Buffers buffers, EndPoint endPoint)
        {
            super(buffers, endPoint);
        }

        @Override
        public void send1xx(int code) throws IOException
        {
            Headers headers = new Headers();
            headers.put("status", String.valueOf(code));
            headers.put("version", "HTTP/1.1");
            stream.reply(new ReplyInfo(headers, false));
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
            if (fields != null)
            {
                for (int i = 0; i < fields.size(); ++i)
                {
                    HttpFields.Field field = fields.getField(i);
                    headers.put(field.getName(), field.getValue());
                }
            }
            stream.reply(new ReplyInfo(headers, allContentAdded));
        }

        @Override
        public void addContent(Buffer content, boolean last) throws IOException
        {
            // TODO
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete() throws IOException
        {
            // Nothing to do
        }
    }
}
