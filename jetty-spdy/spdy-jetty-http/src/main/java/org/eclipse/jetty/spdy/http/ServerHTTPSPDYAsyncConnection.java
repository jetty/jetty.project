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

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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
import org.eclipse.jetty.spdy.SPDYAsyncConnection;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerHTTPSPDYAsyncConnection extends AbstractHttpConnection implements AsyncConnection
{
    private static final Logger logger = LoggerFactory.getLogger(ServerHTTPSPDYAsyncConnection.class);
    private static final ByteBuffer ZERO_BYTES = ByteBuffer.allocate(0);

    private final Queue<Runnable> tasks = new LinkedList<>();
    private final BlockingQueue<DataInfo> dataInfos = new LinkedBlockingQueue<>();
    private final SPDYAsyncConnection connection;
    private final Stream stream;
    private Headers headers; // No need for volatile, guarded by state
    private DataInfo dataInfo; // No need for volatile, guarded by state
    private NIOBuffer buffer; // No need for volatile, guarded by state
    private boolean complete; // No need for volatile, guarded by state
    private volatile State state = State.INITIAL;
    private boolean dispatched; // Guarded by synchronization on tasks

    public ServerHTTPSPDYAsyncConnection(Connector connector, AsyncEndPoint endPoint, Server server, SPDYAsyncConnection connection, Stream stream)
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
                getServer().getThreadPool().dispatch(new Runnable()
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
                    Headers.Header method = headers.get("method");
                    Headers.Header uri = headers.get("url");
                    Headers.Header version = headers.get("version");

                    if (method == null || uri == null || version == null)
                        throw new HttpException(HttpStatus.BAD_REQUEST_400);

                    String m = method.value();
                    String u = uri.value();
                    String v = version.value();
                    logger.debug("HTTP > {} {} {}", new Object[]{m, u, v});
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
                            case "url":
                            {
                                // Skip request line headers
                                continue;
                            }
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
        Headers headers = new Headers();
        headers.put("status", String.valueOf(status));
        headers.put("version", "HTTP/1.1");
        stream.reply(new ReplyInfo(headers, true));
    }

    private void close(Stream stream)
    {
        stream.getSession().goAway();
    }

    @Override
    public void onInputShutdown() throws IOException
    {
    }

    public void beginRequest(final Headers headers)
    {
        this.headers = headers.isEmpty() ? null : headers;
        post(new Runnable()
        {
            @Override
            public void run()
            {
                if (!headers.isEmpty())
                    state = State.REQUEST;
                handle();
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
                state = state == State.INITIAL ? State.REQUEST : State.HEADERS;
                handle();
            }
        });
    }

    public void content(final DataInfo dataInfo, boolean endRequest)
    {
        dataInfos.offer(new ByteBufferDataInfo(dataInfo.asByteBuffer(false), dataInfo.isClose(), dataInfo.isCompress())
        {
            @Override
            public void consume(int delta)
            {
                super.consume(delta);
                dataInfo.consume(delta);
            }
        });
        complete = endRequest;
        post(new Runnable()
        {
            @Override
            public void run()
            {
                logger.debug("HTTP > {} bytes of content", dataInfo.length());
                if (state == State.HEADERS)
                {
                    state = State.HEADERS_COMPLETE;
                    handle();
                }
                state = State.CONTENT;
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
                if (state == State.HEADERS)
                {
                    state = State.HEADERS_COMPLETE;
                    handle();
                }
                state = State.FINAL;
                handle();
            }
        });
    }

    public void async()
    {
        post(new Runnable()
        {
            @Override
            public void run()
            {
                State currentState = state;
                state = State.ASYNC;
                handle();
                state = currentState;
            }
        });
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
                    if (dataInfo.consumed() == 0)
                        dataInfo.consume(dataInfo.length());
                    dataInfo = null;
                    buffer = null;
                    if (complete && dataInfos.isEmpty())
                        return null;
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
                    // Only consume if it's the last DataInfo
                    boolean consume = complete && dataInfos.isEmpty();
                    ByteBuffer byteBuffer = dataInfo.asByteBuffer(consume);
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
            headers.put("version", version);
            StringBuilder status = new StringBuilder().append(_status);
            if (_reason != null)
                status.append(" ").append(_reason.toString("UTF-8"));
            headers.put("status", status.toString());
            logger.debug("HTTP < {} {}", version, status);

            if (fields != null)
            {
                for (int i = 0; i < fields.size(); ++i)
                {
                    HttpFields.Field field = fields.getField(i);
                    String name = field.getName().toLowerCase();
                    String value = field.getValue();
                    headers.put(name, value);
                    logger.debug("HTTP < {}: {}", name, value);
                }
            }

            // We have to query the HttpGenerator and its buffers to know
            // whether there is content buffered; if so, send the data frame
            Buffer content = getContentBuffer();
            stream.reply(new ReplyInfo(headers, content == null));
            if (content != null)
            {
                closed = allContentAdded || isAllContentWritten();
                ByteBuffer buffer = ByteBuffer.wrap(content.asArray());
                logger.debug("HTTP < {} bytes of content", buffer.remaining());
                // Send the data frame
                stream.data(new ByteBufferDataInfo(buffer, closed));
                // Update HttpGenerator fields so that they remain consistent
                content.clear();
                _state = closed ? HttpGenerator.STATE_END : HttpGenerator.STATE_CONTENT;
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
            if (_bypass && _content != null && _content.length() > 0)
                return _content;
            return null;
        }

        @Override
        public boolean addContent(byte b) throws IOException
        {
            // In HttpGenerator, writing one byte only has a different path than
            // writing a buffer. Here we normalize these path to keep it simpler.
            addContent(new ByteArrayBuffer(new byte[]{b}), false);
            return false;
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
            while (_content != null && _content.length() > 0)
            {
                _content.skip(_buffer.put(_content));
                ByteBuffer buffer = ByteBuffer.wrap(_buffer.asArray());
                logger.debug("HTTP < {} bytes of content", buffer.remaining());
                _buffer.clear();
                closed = _content.length() == 0 && _last;
                stream.data(new ByteBufferDataInfo(buffer, closed));

                boolean expired = !connection.getEndPoint().blockWritable(maxIdleTime);
                if (expired)
                {
                    stream.getSession().goAway();
                    throw new EOFException("write timeout");
                }
            }
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
                ByteBuffer buffer = ByteBuffer.wrap(content.asArray());
                logger.debug("HTTP < {} bytes of content", buffer.remaining());
                // Update HttpGenerator fields so that they remain consistent
                content.clear();
                _state = STATE_END;
                // Send the data frame
                stream.data(new ByteBufferDataInfo(buffer, true));
            }
            else if (!closed)
            {
                closed = true;
                _state = STATE_END;
                // Send the data frame
                stream.data(new ByteBufferDataInfo(ZERO_BYTES, true));
            }
        }
    }
}
