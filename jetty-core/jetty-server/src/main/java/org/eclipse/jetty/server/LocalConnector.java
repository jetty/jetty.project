//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MemoryEndPointPipe;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.Aggregator;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A local connector, mostly for testing purposes.
 * <pre>
 *  HttpTester.Request request = HttpTester.newRequest();
 *  request.setURI("/some/resource");
 *  HttpTester.Response response =
 *      HttpTester.parseResponse(HttpTester.from(localConnector.getResponse(request.generate())));
 * </pre>
 */
public class LocalConnector extends MemoryConnector
{
    private static final Logger LOG = LoggerFactory.getLogger(LocalConnector.class);

    public LocalConnector(Server server)
    {
        this(server, new HttpConnectionFactory());
    }

    public LocalConnector(Server server, HttpConnectionFactory factory)
    {
        this(server, new ConnectionFactory[]{factory});
    }

    // TODO: remove this.
    public LocalConnector(Server server, ConnectionFactory... factories)
    {
        super(server, null, null, null, factories);
    }

    @Override
    public Object getTransport()
    {
        return this;
    }

    public HttpConnectionFactory getHttpConnectionFactory()
    {
        return getBean(HttpConnectionFactory.class);
    }

    /**
     * Executes a request and returns the [LocalEndPoint] through
     * which the response can be received or more input provided.
     *
     * @param rawRequest the request
     * @return the local endpoint
     */
    public LocalEndPoint executeRequest(String rawRequest)
    {
        return executeRequest(RetainableByteBuffer.wrap(rawRequest, ISO_8859_1));
    }

    private LocalEndPoint executeRequest(RetainableByteBuffer rawRequest)
    {
        if (!isStarted())
            throw new IllegalStateException("!STARTED");
        if (isShutdown())
            throw new IllegalStateException("Shutdown");
        LocalEndPoint endPoint = connectToServer();
        endPoint.write(rawRequest, Callback.NOOP);
        return endPoint;
    }

    public LocalEndPoint connectToServer()
    {
        return new LocalEndPoint((MemoryEndPointPipe)connect());
    }

    public RetainableByteBuffer getResponse(RetainableByteBuffer requestsBuffer) throws Exception
    {
        return getResponse(requestsBuffer, 10L, SECONDS);
    }

    public RetainableByteBuffer getResponse(RetainableByteBuffer requestBuffer, long time, TimeUnit unit) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("request {}", requestBuffer);
        try (LocalEndPoint endPoint = executeRequest(requestBuffer))
        {
            return endPoint.awaitResponseBuffer(false, time, unit);
        }
    }

    /**
     * Get a single response using a parser to search for the end of the message.
     *
     * @param rawRequest The request to send
     * @return ByteBuffer containing response or null.
     * @throws Exception If there is a problem
     */
    public String getResponseAsString(String rawRequest) throws Exception
    {
        return getResponseAsString(rawRequest, 10, SECONDS);
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
    public String getResponseAsString(String rawRequest, long time, TimeUnit unit) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("request {}", rawRequest);
        boolean head = rawRequest.toLowerCase(Locale.ROOT).startsWith("head ");
        RetainableByteBuffer requestsBuffer = RetainableByteBuffer.wrap(rawRequest, ISO_8859_1);
        try (LocalEndPoint endPoint = executeRequest(requestsBuffer))
        {
            RetainableByteBuffer response = endPoint.awaitResponseBuffer(head, time, unit);
            if (response == null)
                return null;
            String result = response.getString(ISO_8859_1);
            response.release();
            return result;
        }
    }

    public class LocalEndPoint extends EndPoint.Wrapper
    {
        private final MemoryEndPointPipe pipe;
        private RetainableByteBuffer.Mutable _responseBuffer;

        private LocalEndPoint(MemoryEndPointPipe pipe)
        {
            super(pipe.getLocalEndPoint());
            this.pipe = pipe;
        }

        public void setLocalEndPointMaxCapacity(int maxCapacity)
        {
            pipe.setLocalEndPointMaxCapacity(maxCapacity);
        }

        public void setRemoteEndPointMaxCapacity(int maxCapacity)
        {
            pipe.setRemoteEndPointMaxCapacity(maxCapacity);
        }

        public EndPoint getRemoteEndPoint()
        {
            return pipe.getRemoteEndPoint();
        }

        public void writeRequestBuffer(RetainableByteBuffer buffer)
        {
            write(buffer, Callback.NOOP);
        }

        public void writeRequestString(String string)
        {
            writeRequestBuffer(RetainableByteBuffer.wrap(string, ISO_8859_1));
        }

        /**
         * Wait for a response using a parser to detect the end of message
         *
         * @return Buffer containing full response or null for EOF;
         * @throws Exception if the response cannot be parsed
         */
        public String getResponse() throws Exception
        {
            return getResponse(false, 10, SECONDS);
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
            RetainableByteBuffer response = awaitResponseBuffer(head, time, unit);
            return response == null ? null : response.getString(ISO_8859_1);
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
        public RetainableByteBuffer awaitResponseBuffer(boolean head, long time, TimeUnit unit) throws Exception
        {
            HttpParser.ResponseHandler handler = new HttpParser.ResponseHandler()
            {
                @Override
                public void startResponse(HttpVersion version, int status, String reason)
                {
                }

                @Override
                public void parsedHeader(HttpField field)
                {
                }

                @Override
                public boolean headerComplete()
                {
                    return false;
                }

                @Override
                public boolean content(RetainableByteBuffer item)
                {
                    return false;
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
                public void earlyEOF()
                {
                }
            };

            HttpParser parser = new HttpParser(handler);
            parser.setHeadResponse(head);

            try (Aggregator responseAggregator = new Aggregator(true, 1024))
            {
                boolean fill = true;
                RetainableByteBuffer.Mutable responseBuffer;
                if (_responseBuffer != null)
                {
                    responseBuffer = _responseBuffer;
                    _responseBuffer = null;
                    fill = false;
                }
                else
                {
                    responseBuffer = WritableBufferPool.wrap(getByteBufferPool()).acquire(1024, false);
                }

                try
                {
                    while (true)
                    {
                        long filled = fill ? fill(responseBuffer.compact()) : responseBuffer.remaining();
                        fill = true;
                        if (LOG.isDebugEnabled())
                            LOG.debug("filled {} bytes on {}", filled, this);

                        if (filled > 0)
                        {
                            while (responseBuffer.hasRemaining())
                            {
                                long start = responseBuffer.readPosition();
                                boolean complete = parser.parseNext(responseBuffer);
                                long consumed = responseBuffer.readPosition() - start;

                                if (LOG.isDebugEnabled())
                                    LOG.debug("parsed {} bytes, complete={}, on {}", consumed, complete, this);

                                if (consumed > 0)
                                    responseBuffer.mapSlice(start, consumed, responseAggregator::append);

                                if (complete)
                                {
                                    // Save the buffer in case it contains multiple responses.
                                    if (responseBuffer.hasRemaining())
                                        _responseBuffer = responseBuffer;
                                    else
                                        responseBuffer.release();

                                    RetainableByteBuffer result = responseAggregator.remaining() > 0 ? responseAggregator.take() : null;
                                    return result;
                                }
                            }
                        }
                        else if (filled == 0)
                        {
                            try (Blocker.Callback callback = Blocker.callback())
                            {
                                fillInterested(callback);
                                callback.block(time, unit);
                            }
                            catch (TimeoutException x)
                            {
                                responseBuffer.release();
                                return null;
                            }
                        }
                        else
                        {
                            responseBuffer.release();
                            RetainableByteBuffer result = responseAggregator.remaining() > 0 ? responseAggregator.take() : null;
                            return result;
                        }
                    }
                }
                catch (Throwable x)
                {
                    responseBuffer.release();
                    throw x;
                }
            }
        }
    }
}
