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

package org.eclipse.jetty.fcgi.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.parser.ServerParser;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerFCGIConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerFCGIConnection.class);

    private final Connector connector;
    private final RetainableByteBufferPool networkByteBufferPool;
    private final boolean sendStatus200;
    private final Flusher flusher;
    private final HttpConfiguration configuration;
    private final ServerParser parser;
    private boolean useInputDirectByteBuffers;
    private boolean useOutputDirectByteBuffers;
    private RetainableByteBuffer networkBuffer;
    private HttpChannelOverFCGI channel;

    public ServerFCGIConnection(Connector connector, EndPoint endPoint, HttpConfiguration configuration, boolean sendStatus200)
    {
        super(endPoint, connector.getExecutor());
        this.connector = connector;
        this.networkByteBufferPool = connector.getRetainableByteBufferPool();
        this.flusher = new Flusher(endPoint);
        this.configuration = configuration;
        this.sendStatus200 = sendStatus200;
        this.parser = new ServerParser(new ServerListener());
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    public void onFillable()
    {
        acquireInputBuffer();
        try
        {
            while (true)
            {
                int read = fillInputBuffer();
                if (LOG.isDebugEnabled())
                    LOG.debug("Read {} bytes from {} {}", read, getEndPoint(), this);
                if (read > 0)
                {
                    if (parse(networkBuffer.getBuffer()))
                        return;
                }
                else if (read == 0)
                {
                    releaseInputBuffer();
                    fillInterested();
                    break;
                }
                else
                {
                    releaseInputBuffer();
                    shutdown();
                    break;
                }
            }
        }
        catch (Exception x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to fill endpoint", x);
            networkBuffer.clear();
            releaseInputBuffer();
            // TODO: fail and close ?
        }
    }

    /**
     * This is just a "consume" method, so it must not call
     * fillInterested(), but just consume what's in the network
     * for the current request.
     */
    void parseAndFill()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parseAndFill {}", this);
        // This loop must run only until the request is completed.
        // See also HttpConnection.parseAndFillForContent().
        while (channel != null)
        {
            if (parse(networkBuffer.getBuffer()))
                return;
            // Check if the request was completed by the parsing.
            if (channel == null)
                return;
            if (fillInputBuffer() <= 0)
                break;
        }
    }

    private void acquireInputBuffer()
    {
        if (networkBuffer == null)
            networkBuffer = networkByteBufferPool.acquire(configuration.getResponseHeaderSize(), isUseInputDirectByteBuffers());
    }

    private void releaseInputBuffer()
    {
        boolean released = networkBuffer.release();
        if (LOG.isDebugEnabled())
            LOG.debug("releaseInputBuffer {} {}", released, this);
        if (released)
            networkBuffer = null;
    }

    private int fillInputBuffer()
    {
        try
        {
            return getEndPoint().fill(networkBuffer.getBuffer());
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not fill from {}", this, x);
            return -1;
        }
    }

    @Override
    protected boolean onReadTimeout(Throwable timeout)
    {
        if (channel != null)
            return channel.onIdleTimeout(timeout);
        return true;
    }

    private boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            boolean result = parser.parse(buffer);
            if (result)
                return true;
        }
        return false;
    }

    private void shutdown()
    {
        flusher.shutdown();
    }

    void onCompleted(boolean fillMore)
    {
        releaseInputBuffer();
        if (getEndPoint().isOpen() && fillMore)
            fillInterested();
    }

    private class ServerListener implements ServerParser.Listener
    {
        @Override
        public void onStart(int request, FCGI.Role role, int flags)
        {
            // TODO: handle flags
            if (channel != null)
                throw new UnsupportedOperationException("FastCGI Multiplexing");
            channel = new HttpChannelOverFCGI(ServerFCGIConnection.this, connector, configuration, getEndPoint(),
                new HttpTransportOverFCGI(connector.getByteBufferPool(), isUseOutputDirectByteBuffers(), sendStatus200, flusher, request));
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} start on {}", request, channel);
        }

        @Override
        public void onHeader(int request, HttpField field)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} header {} on {}", request, field, channel);
            if (channel != null)
                channel.header(field);
        }

        @Override
        public boolean onHeaders(int request)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} headers on {}", request, channel);
            if (channel != null)
            {
                channel.onRequest();
                channel.dispatch();
                // We have dispatched to the application, so we must stop the fill & parse loop.
                return true;
            }
            return false;
        }

        @Override
        public boolean onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} {} content {} on {}", request, stream, buffer, channel);
            if (channel != null)
            {
                channel.onContent(new FastCGIContent(buffer));
                // Signal that the content is processed asynchronously, to ensure backpressure.
                return true;
            }
            return false;
        }

        @Override
        public void onEnd(int request)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} end on {}", request, channel);
            if (channel != null)
            {
                channel.onContentComplete();
                channel.onRequestComplete();
                // Nulling out the channel signals that the
                // request is complete, see also parseAndFill().
                channel = null;
            }
        }

        @Override
        public void onFailure(int request, Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} failure on {}: {}", request, channel, failure);
            if (channel != null)
                channel.onBadMessage(new BadMessageException(HttpStatus.BAD_REQUEST_400, null, failure));
            channel = null;
        }

        private class FastCGIContent extends HttpInput.Content
        {
            public FastCGIContent(ByteBuffer content)
            {
                super(content);
                networkBuffer.retain();
            }

            @Override
            public void succeeded()
            {
                release();
            }

            @Override
            public void failed(Throwable x)
            {
                release();
            }

            private void release()
            {
                networkBuffer.release();
            }
        }
    }
}
