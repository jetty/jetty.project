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

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
import org.eclipse.jetty.spdy.SPDYAsyncConnection;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ServerHTTPSPDYAsyncConnection extends AbstractHttpConnection implements AsyncConnection
{
    private static final Logger logger = Log.getLogger(ServerHTTPSPDYAsyncConnection.class);
    private static final ByteBuffer ZERO_BYTES = ByteBuffer.allocate(0);
    private static final DataInfo END_OF_CONTENT = new ByteBufferDataInfo(ZERO_BYTES, true);

    private final Queue<Runnable> tasks = new LinkedList<>();
    private final BlockingQueue<DataInfo> dataInfos = new LinkedBlockingQueue<>();
    private final short version;
    private final SPDYAsyncConnection connection;
    private final PushStrategy pushStrategy;
    private final Stream stream;
    private Headers headers; // No need for volatile, guarded by state
    private DataInfo dataInfo; // No need for volatile, guarded by state
    private NIOBuffer buffer; // No need for volatile, guarded by state
    private volatile State state = State.INITIAL;
    private boolean dispatched; // Guarded by synchronization on tasks

    public ServerHTTPSPDYAsyncConnection(Connector connector, AsyncEndPoint endPoint, Server server, short version, SPDYAsyncConnection connection, PushStrategy pushStrategy, Stream stream)
    {
        super(connector, endPoint, server);
        this.version = version;
        this.connection = connection;
        this.pushStrategy = pushStrategy;
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

    private void post(Runnable task)
    {
        synchronized (tasks)
        {
            logger.debug("Posting task {}", task);
            tasks.offer(task);
            dispatch();
        }
    }

    private void dispatch()
    {
        synchronized (tasks)
        {
            if (dispatched)
                return;

            final Runnable task = tasks.poll();
            if (task != null)
            {
                dispatched = true;
                logger.debug("Dispatching task {}", task);
                execute(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        logger.debug("Executing task {}", task);
                        task.run();
                        logger.debug("Completing task {}", task);
                        dispatched = false;
                        dispatch();
                    }
                });
            }
        }
    }

    protected void execute(Runnable task)
    {
        getServer().getThreadPool().dispatch(task);
    }

    @Override
    public Connection handle()
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
                    Headers.Header method = headers.get(HTTPSPDYHeader.METHOD.name(version));
                    Headers.Header uri = headers.get(HTTPSPDYHeader.URI.name(version));
                    Headers.Header version = headers.get(HTTPSPDYHeader.VERSION.name(this.version));

                    if (method == null || uri == null || version == null)
                        throw new HttpException(HttpStatus.BAD_REQUEST_400);

                    String m = method.value();
                    String u = uri.value();
                    String v = version.value();
                    logger.debug("HTTP > {} {} {}", m, u, v);
                    startRequest(new ByteArrayBuffer(m), new ByteArrayBuffer(u), new ByteArrayBuffer(v));

                    Headers.Header schemeHeader = headers.get(HTTPSPDYHeader.SCHEME.name(this.version));
                    if(schemeHeader != null)
                        _request.setScheme(schemeHeader.value());

                    updateState(State.HEADERS);
                    handle();
                    break;
                }
                case HEADERS:
                {
                    for (Headers.Header header : headers)
                    {
                        String name = header.name();

                        // Skip special SPDY headers, unless it's the "host" header
                        HTTPSPDYHeader specialHeader = HTTPSPDYHeader.from(version, name);
                        if (specialHeader != null)
                        {
                            if (specialHeader == HTTPSPDYHeader.HOST)
                                name = "host";
                            else
                                continue;
                        }

                        switch (name)
                        {
                            case "connection":
                            case "keep-alive":
                            case "proxy-connection":
                            case "transfer-encoding":
                            {
                                // Spec says to ignore these headers
                                continue;
                            }
                            default:
                            {
                                // Spec says headers must be single valued
                                String value = header.value();
                                logger.debug("HTTP > {}: {}", name, value);
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
                    if (buffer != null && buffer.length() > 0)
                        content(buffer);
                    break;
                }
                case FINAL:
                {
                    messageComplete(0);
                    break;
                }
                case ASYNC:
                {
                    handleRequest();
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
            return this;
        }
        catch (HttpException x)
        {
            respond(stream, x.getStatus());
            return this;
        }
        catch (IOException x)
        {
            close(stream);
            return this;
        }
        finally
        {
            setCurrentConnection(null);
        }
    }

    private void respond(Stream stream, int status)
    {
        if (stream.isUnidirectional())
        {
            stream.getSession().rst(new RstInfo(stream.getId(), StreamStatus.INTERNAL_ERROR));
        }
        else
        {
            Headers headers = new Headers();
            headers.put(HTTPSPDYHeader.STATUS.name(version), String.valueOf(status));
            headers.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
            stream.reply(new ReplyInfo(headers, true));
        }
    }

    private void close(Stream stream)
    {
        stream.getSession().goAway();
    }

    @Override
    public void onInputShutdown() throws IOException
    {
    }

    private void updateState(State newState)
    {
        logger.debug("State update {} -> {}", state, newState);
        state = newState;
    }

    public void beginRequest(final Headers headers, final boolean endRequest)
    {
        this.headers = headers.isEmpty() ? null : headers;
        post(new Runnable()
        {
            @Override
            public void run()
            {
                if (!headers.isEmpty())
                    updateState(State.REQUEST);
                handle();
                if (endRequest)
                    performEndRequest();
            }
        });
    }

    public void headers(Headers headers)
    {
        this.headers = headers;
        post(new Runnable()
        {
            @Override
            public void run()
            {
                updateState(state == State.INITIAL ? State.REQUEST : State.HEADERS);
                handle();
            }
        });
    }

    public void content(final DataInfo dataInfo, boolean endRequest)
    {
        // We need to copy the dataInfo since we do not know when its bytes
        // will be consumed. When the copy is consumed, we consume also the
        // original, so the implementation can send a window update.
        ByteBufferDataInfo copyDataInfo = new ByteBufferDataInfo(dataInfo.asByteBuffer(false), dataInfo.isClose(), dataInfo.isCompress())
        {
            @Override
            public void consume(int delta)
            {
                super.consume(delta);
                dataInfo.consume(delta);
            }
        };
        logger.debug("Queuing last={} content {}", endRequest, copyDataInfo);
        dataInfos.offer(copyDataInfo);
        if (endRequest)
            dataInfos.offer(END_OF_CONTENT);
        post(new Runnable()
        {
            @Override
            public void run()
            {
                logger.debug("HTTP > {} bytes of content", dataInfo.length());
                if (state == State.HEADERS)
                {
                    updateState(State.HEADERS_COMPLETE);
                    handle();
                }
                updateState(State.CONTENT);
                handle();
            }
        });
    }

    public void endRequest()
    {
        post(new Runnable()
        {
            public void run()
            {
                performEndRequest();
            }
        });
    }

    private void performEndRequest()
    {
        if (state == State.HEADERS)
        {
            updateState(State.HEADERS_COMPLETE);
            handle();
        }
        updateState(State.FINAL);
        handle();
    }

    public void async()
    {
        post(new Runnable()
        {
            @Override
            public void run()
            {
                State oldState = state;
                updateState(State.ASYNC);
                handle();
                updateState(oldState);
            }
        });
    }

    protected void reply(Stream stream, ReplyInfo replyInfo)
    {
        if (!stream.isUnidirectional())
            stream.reply(replyInfo);
        if (replyInfo.getHeaders().get(HTTPSPDYHeader.STATUS.name(version)).value().startsWith("200") &&
                !stream.isClosed())
        {
            // We have a 200 OK with some content to send

            Headers.Header scheme = headers.get(HTTPSPDYHeader.SCHEME.name(version));
            Headers.Header host = headers.get(HTTPSPDYHeader.HOST.name(version));
            Headers.Header uri = headers.get(HTTPSPDYHeader.URI.name(version));
            Set<String> pushResources = pushStrategy.apply(stream, headers, replyInfo.getHeaders());

            for (String pushResourcePath : pushResources)
            {
                final Headers requestHeaders = createRequestHeaders(scheme, host, uri, pushResourcePath);
                final Headers pushHeaders = createPushHeaders(scheme, host, pushResourcePath);

                stream.syn(new SynInfo(pushHeaders, false), getMaxIdleTime(), TimeUnit.MILLISECONDS, new Handler.Adapter<Stream>()
                {
                    @Override
                    public void completed(Stream pushStream)
                    {
                        ServerHTTPSPDYAsyncConnection pushConnection =
                                new ServerHTTPSPDYAsyncConnection(getConnector(), getEndPoint(), getServer(), version, connection, pushStrategy, pushStream);
                        pushConnection.beginRequest(requestHeaders, true);
                    }
                });
            }
        }
    }

    private Headers createRequestHeaders(Headers.Header scheme, Headers.Header host, Headers.Header uri, String pushResourcePath)
    {
        final Headers requestHeaders = new Headers();
        requestHeaders.put(HTTPSPDYHeader.METHOD.name(version), "GET");
        requestHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
        requestHeaders.put(scheme);
        requestHeaders.put(host);
        requestHeaders.put(HTTPSPDYHeader.URI.name(version), pushResourcePath);
        String referrer = scheme.value() + "://" + host.value() + uri.value();
        requestHeaders.put("referer", referrer);
        // Remember support for gzip encoding
        requestHeaders.put(headers.get("accept-encoding"));
        requestHeaders.put("x-spdy-push", "true");
        return requestHeaders;
    }

    private Headers createPushHeaders(Headers.Header scheme, Headers.Header host, String pushResourcePath)
    {
        final Headers pushHeaders = new Headers();
        if (version == SPDY.V2)
            pushHeaders.put(HTTPSPDYHeader.URI.name(version), scheme.value() + "://" + host.value() + pushResourcePath);
        else
        {
            pushHeaders.put(HTTPSPDYHeader.URI.name(version), pushResourcePath);
            pushHeaders.put(scheme);
            pushHeaders.put(host);
        }
        pushHeaders.put(HTTPSPDYHeader.STATUS.name(version), "200");
        pushHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
        return pushHeaders;
    }

    private Buffer consumeContent(long maxIdleTime) throws IOException, InterruptedException
    {
        while (true)
        {
            // Volatile read to ensure visibility
            State state = this.state;
            if (state != State.HEADERS_COMPLETE && state != State.CONTENT && state != State.FINAL)
                throw new IllegalStateException();

            if (buffer != null)
            {
                if (buffer.length() > 0)
                {
                    logger.debug("Consuming content bytes, {} available", buffer.length());
                    return buffer;
                }
                else
                {
                    // The application has consumed the buffer, so consume also the DataInfo
                    dataInfo.consume(dataInfo.length());
                    logger.debug("Consumed {} content bytes, queue size {}", dataInfo.consumed(), dataInfos.size());
                    dataInfo = null;
                    buffer = null;
                    // Loop to get content bytes from DataInfos
                }
            }
            else
            {
                logger.debug("Waiting at most {} ms for content bytes", maxIdleTime);
                long begin = System.nanoTime();
                dataInfo = dataInfos.poll(maxIdleTime, TimeUnit.MILLISECONDS);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
                logger.debug("Waited {} ms for content bytes", elapsed);
                if (dataInfo != null)
                {
                    if (dataInfo == END_OF_CONTENT)
                    {
                        logger.debug("End of content bytes, queue size {}", dataInfos.size());
                        return null;
                    }

                    ByteBuffer byteBuffer = dataInfo.asByteBuffer(false);
                    buffer = byteBuffer.isDirect() ? new DirectNIOBuffer(byteBuffer, false) : new IndirectNIOBuffer(byteBuffer, false);
                    // Loop to return the buffer
                }
                else
                {
                    stream.getSession().goAway();
                    throw new EOFException("read timeout");
                }
            }
        }
    }

    private int availableContent()
    {
        // Volatile read to ensure visibility
        State state = this.state;
        if (state != State.HEADERS_COMPLETE && state != State.CONTENT)
            throw new IllegalStateException();
        return buffer == null ? 0 : buffer.length();
    }

    @Override
    public void commitResponse(boolean last) throws IOException
    {
        // Keep the original behavior since it just delegates to the generator
        super.commitResponse(last);
    }

    @Override
    public void flushResponse() throws IOException
    {
        // Just commit the response, if necessary: flushing buffers will be taken care of in complete()
        commitResponse(false);
    }

    @Override
    public void completeResponse() throws IOException
    {
        // Keep the original behavior since it just delegates to the generator
        super.completeResponse();
    }

    private enum State
    {
        INITIAL, REQUEST, HEADERS, HEADERS_COMPLETE, CONTENT, FINAL, ASYNC
    }

    /**
     * Needed in order to override parser methods that read content.
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
            try
            {
                return consumeContent(maxIdleTime);
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
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
        private boolean closed;

        private HTTPSPDYGenerator(Buffers buffers, EndPoint endPoint)
        {
            super(buffers, endPoint);
        }

        @Override
        public void send1xx(int code) throws IOException
        {
            // TODO: not supported yet, but unlikely to be called
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendResponse(Buffer response) throws IOException
        {
            // Do not think this method is ever used.
            // Jetty calls it from Request.setAttribute() only if the attribute
            // "org.eclipse.jetty.server.ResponseBuffer", seems like a hack.
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendError(int code, String reason, String content, boolean close) throws IOException
        {
            // Keep original behavior because it's delegating to other methods that we override.
            super.sendError(code, reason, content, close);
        }

        @Override
        public void completeHeader(HttpFields fields, boolean allContentAdded) throws IOException
        {
            Headers headers = new Headers();
            String version = "HTTP/1.1";
            headers.put(HTTPSPDYHeader.VERSION.name(ServerHTTPSPDYAsyncConnection.this.version), version);
            StringBuilder status = new StringBuilder().append(_status);
            if (_reason != null)
                status.append(" ").append(_reason.toString("UTF-8"));
            headers.put(HTTPSPDYHeader.STATUS.name(ServerHTTPSPDYAsyncConnection.this.version), status.toString());
            logger.debug("HTTP < {} {}", version, status);

            if (fields != null)
            {
                for (int i = 0; i < fields.size(); ++i)
                {
                    HttpFields.Field field = fields.getField(i);
                    String name = field.getName().toLowerCase(Locale.ENGLISH);
                    String value = field.getValue();
                    headers.put(name, value);
                    logger.debug("HTTP < {}: {}", name, value);
                }
            }

            // We have to query the HttpGenerator and its buffers to know
            // whether there is content buffered and update the generator state
            Buffer content = getContentBuffer();
            reply(stream, new ReplyInfo(headers, content == null));
            if (content != null)
            {
                closed = false;
                // Update HttpGenerator fields so that they remain consistent
                _state = HttpGenerator.STATE_CONTENT;
            }
            else
            {
                closed = true;
                // Update HttpGenerator fields so that they remain consistent
                _state = HttpGenerator.STATE_END;
            }
        }

        private Buffer getContentBuffer()
        {
            if (_buffer != null && _buffer.length() > 0)
                return _buffer;
            if (_content != null && _content.length() > 0)
                return _content;
            return null;
        }

        @Override
        public void addContent(Buffer content, boolean last) throws IOException
        {
            // Keep the original behavior since adding content will
            // just accumulate bytes until the response is committed.
            super.addContent(content, last);
        }

        @Override
        public void flush(long maxIdleTime) throws IOException
        {
            try
            {
                Buffer content = getContentBuffer();
                while (content != null)
                {
                    DataInfo dataInfo = toDataInfo(content, closed);
                    logger.debug("HTTP < {} bytes of content", dataInfo.length());
                    stream.data(dataInfo).get(maxIdleTime, TimeUnit.MILLISECONDS);
                    content.clear();
                    _bypass = false;
                    content = getContentBuffer();
                }
            }
            catch (TimeoutException x)
            {
                stream.getSession().goAway();
                throw new EOFException("write timeout");
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
            catch (ExecutionException x)
            {
                throw new IOException(x.getCause());
            }
        }

        private DataInfo toDataInfo(Buffer buffer, boolean close)
        {
            if (buffer instanceof ByteArrayBuffer)
                return new BytesDataInfo(buffer.array(), buffer.getIndex(), buffer.length(), close);

            if (buffer instanceof NIOBuffer)
            {
                ByteBuffer byteBuffer = ((NIOBuffer)buffer).getByteBuffer();
                byteBuffer.limit(buffer.putIndex());
                byteBuffer.position(buffer.getIndex());
                return new ByteBufferDataInfo(byteBuffer, close);
            }

            return new BytesDataInfo(buffer.asArray(), close);
        }

        @Override
        public int flushBuffer() throws IOException
        {
            // Must never be called because it's where the HttpGenerator writes
            // the HTTP content to the EndPoint (we should write SPDY instead).
            // If it's called it's our bug.
            throw new UnsupportedOperationException();
        }

        @Override
        public void blockForOutput(long maxIdleTime) throws IOException
        {
            // The semantic of this method is weird: not only it has to block
            // but also need to flush. Since we have a blocking flush method
            // we delegate to that, because it has the same semantic.
            flush(maxIdleTime);
        }

        @Override
        public void complete() throws IOException
        {
            Buffer content = getContentBuffer();
            if (content != null)
            {
                closed = true;
                _state = STATE_END;
                flush(getMaxIdleTime());
            }
            else if (!closed)
            {
                closed = true;
                _state = STATE_END;
                // Send the last, empty, data frame
                stream.data(new ByteBufferDataInfo(ZERO_BYTES, true));
            }
        }
    }
}
