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

package org.eclipse.jetty.fcgi.server.internal;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.generator.ServerGenerator;
import org.eclipse.jetty.fcgi.parser.ServerParser;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.AbstractMetaDataConnection;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerFCGIConnection extends AbstractMetaDataConnection implements ConnectionMetaData
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerFCGIConnection.class);

    private final HttpChannel.Factory httpChannelFactory = new HttpChannel.DefaultFactory();
    private final Attributes attributes = new Lazy();
    private final Connector connector;
    private final ByteBufferPool networkByteBufferPool;
    private final boolean sendStatus200;
    private final Flusher flusher;
    private final HttpConfiguration configuration;
    private final ServerParser parser;
    private final String id;
    private boolean useInputDirectByteBuffers;
    private boolean useOutputDirectByteBuffers;
    private RetainableByteBuffer networkBuffer;
    private HttpStreamOverFCGI stream;

    public ServerFCGIConnection(Connector connector, EndPoint endPoint, HttpConfiguration configuration, boolean sendStatus200)
    {
        super(connector, configuration, endPoint);
        this.connector = connector;
        this.networkByteBufferPool = connector.getByteBufferPool();
        this.flusher = new Flusher(endPoint);
        this.configuration = configuration;
        this.sendStatus200 = sendStatus200;
        this.parser = new ServerParser(new ServerListener());
        this.id = StringUtil.randomAlphaNumeric(16);
    }

    public long getBeginNanoTime()
    {
        return parser.getBeginNanoTime();
    }

    Flusher getFlusher()
    {
        return flusher;
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
    public String getId()
    {
        return id;
    }

    @Override
    public HttpVersion getHttpVersion()
    {
        return HttpVersion.HTTP_1_1;
    }

    @Override
    public String getProtocol()
    {
        return "fcgi/1.0";
    }

    @Override
    public boolean isPersistent()
    {
        return true;
    }

    @Override
    public boolean isSecure()
    {
        return false;
    }

    @Override
    public Object removeAttribute(String name)
    {
        return attributes.removeAttribute(name);
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return attributes.setAttribute(name, attribute);
    }

    @Override
    public Object getAttribute(String name)
    {
        return attributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return attributes.getAttributeNameSet();
    }

    @Override
    public void clearAttributes()
    {
        attributes.clearAttributes();
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
                    if (parse(networkBuffer.getByteBuffer()))
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
        while (stream != null)
        {
            if (parse(networkBuffer.getByteBuffer()))
                return;
            // Check if the request was completed by the parsing.
            if (stream == null)
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
        if (networkBuffer == null)
            return;
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
            return getEndPoint().fill(networkBuffer.getByteBuffer());
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not fill from {}", this, x);
            return -1;
        }
    }

    @Override
    protected boolean onReadTimeout(TimeoutException timeout)
    {
        if (stream != null)
            return stream.onIdleTimeout(timeout);
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

    void onCompleted(Throwable failure)
    {
        releaseInputBuffer();
        if (failure == null)
            fillInterested();
        else
            getFlusher().shutdown();
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeoutException)
    {
        HttpStreamOverFCGI stream = this.stream;
        if (stream == null)
            return true;
        Runnable task = stream.getHttpChannel().onIdleTimeout(timeoutException);
        if (task != null)
            getExecutor().execute(task);
        return false;
    }

    private class ServerListener implements ServerParser.Listener
    {
        @Override
        public void onStart(int request, FCGI.Role role, int flags)
        {
            // TODO: handle flags
            if (stream != null)
                throw new UnsupportedOperationException("FastCGI Multiplexing");
            HttpChannel channel = httpChannelFactory.newHttpChannel(ServerFCGIConnection.this);
            ServerGenerator generator = new ServerGenerator(connector.getByteBufferPool(), isUseOutputDirectByteBuffers(), sendStatus200);
            stream = new HttpStreamOverFCGI(ServerFCGIConnection.this, generator, channel, request);
            channel.setHttpStream(stream);
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} start on {}", request, channel);
        }

        @Override
        public void onHeader(int request, HttpField field)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} header {} on {}", request, field, stream);
            if (stream != null)
                stream.onHeader(field);
        }

        @Override
        public boolean onHeaders(int request)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} headers on {}", request, stream);
            if (stream != null)
            {
                stream.onHeaders();
                // We have dispatched to the application,
                // so we must stop the fill & parse loop.
                return true;
            }
            return false;
        }

        @Override
        public boolean onContent(int request, FCGI.StreamType streamType, ByteBuffer buffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} {} content {} on {}", request, streamType, buffer, stream);
            if (stream != null)
            {
                // No need to call networkBuffer.retain() here.
                // The receiver of the chunk decides whether to consume/retain it.
                Content.Chunk chunk = Content.Chunk.asChunk(buffer, false, networkBuffer);
                stream.onContent(chunk);
                // Signal that the content is processed asynchronously, to ensure backpressure.
                return true;
            }
            return false;
        }

        @Override
        public void onEnd(int request)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} end on {}", request, stream);
            if (stream != null)
            {
                stream.onComplete();
                // Nulling out the stream signals that the
                // request is complete, see also parseAndFill().
                stream = null;
            }
        }

        @Override
        public void onFailure(int request, Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} failure on {}: {}", request, stream, failure);
            if (stream != null)
            {
                Runnable runnable = stream.getHttpChannel().onFailure(new BadMessageException(null, failure));
                if (runnable != null)
                    getExecutor().execute(runnable);
            }
            stream = null;
        }
    }
}
