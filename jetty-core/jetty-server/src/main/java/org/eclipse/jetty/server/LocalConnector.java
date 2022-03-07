//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ByteArrayOutputStream2;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * A local connector, mostly for testing purposes.
 * <pre>
 *  HttpTester.Request request = HttpTester.newRequest();
 *  request.setURI("/some/resource");
 *  HttpTester.Response response =
 *      HttpTester.parseResponse(HttpTester.from(localConnector.getResponse(request.generate())));
 * </pre>
 */
public class LocalConnector extends AbstractConnector
{
    private final BlockingQueue<LocalEndPoint> _connects = new LinkedBlockingQueue<>();

    public LocalConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool pool, int acceptors, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, pool, acceptors, factories);
        setIdleTimeout(30000);
    }

    public LocalConnector(Server server)
    {
        this(server, null, null, null, -1, new HttpConnectionFactory());
    }

    public LocalConnector(Server server, SslContextFactory.Server sslContextFactory)
    {
        this(server, null, null, null, -1, AbstractConnectionFactory.getFactories(sslContextFactory, new HttpConnectionFactory()));
    }

    public LocalConnector(Server server, ConnectionFactory connectionFactory)
    {
        this(server, null, null, null, -1, connectionFactory);
    }

    public LocalConnector(Server server, ConnectionFactory connectionFactory, SslContextFactory.Server sslContextFactory)
    {
        this(server, null, null, null, -1, AbstractConnectionFactory.getFactories(sslContextFactory, connectionFactory));
    }

    @Override
    public Object getTransport()
    {
        return this;
    }

    /**
     * Execute a request and return the EndPoint through which
     * multiple responses can be received or more input provided.
     *
     * @param rawRequest the request
     * @return the local endpoint
     */
    public LocalEndPoint executeRequest(String rawRequest)
    {
        return executeRequest(BufferUtil.toBuffer(rawRequest, StandardCharsets.UTF_8));
    }

    private LocalEndPoint executeRequest(ByteBuffer rawRequest)
    {
        if (!isStarted())
            throw new IllegalStateException("!STARTED");
        if (isShutdown())
            throw new IllegalStateException("Shutdown");
        LocalEndPoint endp = new LocalEndPoint();
        endp.addInput(rawRequest);
        _connects.add(endp);
        return endp;
    }

    public LocalEndPoint connect()
    {
        LocalEndPoint endp = new LocalEndPoint();
        _connects.add(endp);
        return endp;
    }

    @Override
    protected void accept(int acceptorID) throws InterruptedException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("accepting {}", acceptorID);
        LocalEndPoint endPoint = _connects.take();

        Connection connection = getDefaultConnectionFactory().newConnection(this, endPoint);
        endPoint.setConnection(connection);

        endPoint.onOpen();
        onEndPointOpened(endPoint);

        connection.onOpen();
    }

    /**
     * Get a single response using a parser to search for the end of the message.
     *
     * @param requestsBuffer The request to send
     * @return ByteBuffer containing response or null.
     * @throws Exception If there is a problem
     */
    public ByteBuffer getResponse(ByteBuffer requestsBuffer) throws Exception
    {
        return getResponse(requestsBuffer, false, 10, TimeUnit.SECONDS);
    }

    /**
     * Get a single response using a parser to search for the end of the message.
     *
     * @param requestBuffer The request to send
     * @param time The time to wait
     * @param unit The units of the wait
     * @return ByteBuffer containing response or null.
     * @throws Exception If there is a problem
     */
    public ByteBuffer getResponse(ByteBuffer requestBuffer, long time, TimeUnit unit) throws Exception
    {
        boolean head = BufferUtil.toString(requestBuffer).toLowerCase().startsWith("head ");
        if (LOG.isDebugEnabled())
            LOG.debug("requests {}", BufferUtil.toUTF8String(requestBuffer));
        LocalEndPoint endp = executeRequest(requestBuffer);
        return endp.waitForResponse(head, time, unit);
    }

    /**
     * Get a single response using a parser to search for the end of the message.
     *
     * @param requestBuffer The request to send
     * @param head True if the response is for a head request
     * @param time The time to wait
     * @param unit The units of the wait
     * @return ByteBuffer containing response or null.
     * @throws Exception If there is a problem
     */
    public ByteBuffer getResponse(ByteBuffer requestBuffer, boolean head, long time, TimeUnit unit) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("requests {}", BufferUtil.toUTF8String(requestBuffer));
        LocalEndPoint endp = executeRequest(requestBuffer);
        return endp.waitForResponse(head, time, unit);
    }

    /**
     * Get a single response using a parser to search for the end of the message.
     *
     * @param rawRequest The request to send
     * @return ByteBuffer containing response or null.
     * @throws Exception If there is a problem
     */
    public String getResponse(String rawRequest) throws Exception
    {
        return getResponse(rawRequest, false, 30, TimeUnit.SECONDS);
    }

    /**
     * Get a single response using a parser to search for the end of the message.
     *
     * @param rawRequest The request to send
     * @param time The time to wait
     * @param unit The units of the wait
     * @return ByteBuffer containing response or null.
     * @throws Exception If there is a problem
     */
    public String getResponse(String rawRequest, long time, TimeUnit unit) throws Exception
    {
        boolean head = rawRequest.toLowerCase().startsWith("head ");
        ByteBuffer requestsBuffer = BufferUtil.toBuffer(rawRequest, StandardCharsets.ISO_8859_1);
        if (LOG.isDebugEnabled())
            LOG.debug("request {}", BufferUtil.toUTF8String(requestsBuffer));
        LocalEndPoint endp = executeRequest(requestsBuffer);

        return BufferUtil.toString(endp.waitForResponse(head, time, unit), StandardCharsets.ISO_8859_1);
    }

    /**
     * Get a single response using a parser to search for the end of the message.
     *
     * @param rawRequest The request to send
     * @param head True if the response is for a head request
     * @param time The time to wait
     * @param unit The units of the wait
     * @return ByteBuffer containing response or null.
     * @throws Exception If there is a problem
     */
    public String getResponse(String rawRequest, boolean head, long time, TimeUnit unit) throws Exception
    {
        ByteBuffer requestsBuffer = BufferUtil.toBuffer(rawRequest, StandardCharsets.ISO_8859_1);
        if (LOG.isDebugEnabled())
            LOG.debug("request {}", BufferUtil.toUTF8String(requestsBuffer));
        LocalEndPoint endp = executeRequest(requestsBuffer);

        return BufferUtil.toString(endp.waitForResponse(head, time, unit), StandardCharsets.ISO_8859_1);
    }

    /**
     * Local EndPoint
     */
    public class LocalEndPoint extends ByteArrayEndPoint
    {
        private final CountDownLatch _closed = new CountDownLatch(1);
        private ByteBuffer _responseData;

        public LocalEndPoint()
        {
            super(LocalConnector.this.getScheduler(), LocalConnector.this.getIdleTimeout());
            setGrowOutput(true);
        }

        @Override
        protected void execute(Runnable task)
        {
            getExecutor().execute(task);
        }

        @Override
        public void onClose(Throwable cause)
        {
            Connection connection = getConnection();
            if (connection != null)
                connection.onClose(cause);
            LocalConnector.this.onEndPointClosed(this);
            super.onClose(cause);
            _closed.countDown();
        }

        @Override
        public void doShutdownOutput()
        {
            super.shutdownOutput();
            close();
        }

        public void waitUntilClosed()
        {
            while (isOpen())
            {
                try
                {
                    if (!_closed.await(10, TimeUnit.SECONDS))
                        break;
                }
                catch (Exception e)
                {
                    LOG.warn("Close wait failed", e);
                }
            }
        }

        public void waitUntilClosedOrIdleFor(long idleFor, TimeUnit units)
        {
            Thread.yield();
            int size = getOutput().remaining();
            while (isOpen())
            {
                try
                {
                    if (!_closed.await(idleFor, units))
                    {
                        if (size == getOutput().remaining())
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("idle for {} {}", idleFor, units);
                            return;
                        }
                        size = getOutput().remaining();
                    }
                }
                catch (Exception e)
                {
                    LOG.warn("Close wait failed", e);
                }
            }
        }

        /**
         * Remaining output ByteBuffer after calls to {@link #getResponse()} or {@link #waitForResponse(boolean, long, TimeUnit)}
         *
         * @return the remaining response data buffer
         */
        public ByteBuffer getResponseData()
        {
            return _responseData;
        }

        /**
         * Wait for a response using a parser to detect the end of message
         *
         * @return Buffer containing full response or null for EOF;
         * @throws Exception if the response cannot be parsed
         */
        public String getResponse() throws Exception
        {
            return getResponse(false, 30, TimeUnit.SECONDS);
        }

        /**
         * Wait for a response using a parser to detect the end of message
         *
         * @param head whether the request is a HEAD request
         * @param time the maximum time to wait
         * @param unit the time unit of the {@code timeout} argument
         * @return Buffer containing full response or null for EOF;
         * @throws Exception if the response cannot be parsed
         */
        public String getResponse(boolean head, long time, TimeUnit unit) throws Exception
        {
            ByteBuffer response = waitForResponse(head, time, unit);
            if (response != null)
                return BufferUtil.toString(response);
            return null;
        }

        /**
         * Wait for a response using a parser to detect the end of message
         *
         * @param head whether the request is a HEAD request
         * @param time the maximum time to wait
         * @param unit the time unit of the {@code timeout} argument
         * @return Buffer containing full response or null for EOF;
         * @throws Exception if the response cannot be parsed
         */
        public ByteBuffer waitForResponse(boolean head, long time, TimeUnit unit) throws Exception
        {
            return waitForResponse(head, time, unit, i -> {});
        }

        /**
         * Wait for a response using a parser to detect the end of message
         *
         * @param head whether the request is a HEAD request
         * @param time the maximum time to wait
         * @param unit the time unit of the {@code timeout} argument
         * @param statusConsumer a consumer to be called with the response's status code
         * @return Buffer containing full response or null for EOF;
         * @throws Exception if the response cannot be parsed
         */
        public ByteBuffer waitForResponse(boolean head, long time, TimeUnit unit, Consumer<Integer> statusConsumer) throws Exception
        {
            HttpParser.ResponseHandler handler = new HttpParser.ResponseHandler()
            {
                @Override
                public void parsedHeader(HttpField field)
                {
                }

                @Override
                public boolean contentComplete()
                {
                    return false;
                }

                @Override
                public boolean messageComplete()
                {
                    return true;
                }

                @Override
                public boolean headerComplete()
                {
                    return false;
                }

                @Override
                public void earlyEOF()
                {
                }

                @Override
                public boolean content(ByteBuffer item)
                {
                    return false;
                }

                @Override
                public void startResponse(HttpVersion version, int status, String reason)
                {
                    statusConsumer.accept(status);
                }
            };

            HttpParser parser = new HttpParser(handler);
            parser.setHeadResponse(head);
            try (ByteArrayOutputStream2 bout = new ByteArrayOutputStream2())
            {
                loop:
                while (true)
                {
                    // read a chunk of response
                    ByteBuffer chunk;
                    if (BufferUtil.hasContent(_responseData))
                        chunk = _responseData;
                    else
                    {
                        chunk = waitForOutput(time, unit);
                        if (BufferUtil.isEmpty(chunk) && (!isOpen() || isOutputShutdown() || isShutdown()))
                        {
                            parser.atEOF();
                            parser.parseNext(BufferUtil.EMPTY_BUFFER);
                            break;
                        }
                    }

                    // Parse the content of this chunk
                    while (BufferUtil.hasContent(chunk))
                    {
                        int pos = chunk.position();
                        boolean complete = parser.parseNext(chunk);
                        if (chunk.position() == pos)
                        {
                            // Nothing consumed
                            if (BufferUtil.isEmpty(chunk))
                                break;
                            return null;
                        }

                        // Add all consumed bytes to the output stream
                        bout.write(chunk.array(), chunk.arrayOffset() + pos, chunk.position() - pos);

                        // If we are complete then break the outer loop
                        if (complete)
                        {
                            if (BufferUtil.hasContent(chunk))
                                _responseData = chunk;
                            break loop;
                        }
                    }
                }

                if (bout.getCount() == 0 && isOutputShutdown())
                    return null;
                return ByteBuffer.wrap(bout.getBuf(), 0, bout.getCount());
            }
        }
    }
}
