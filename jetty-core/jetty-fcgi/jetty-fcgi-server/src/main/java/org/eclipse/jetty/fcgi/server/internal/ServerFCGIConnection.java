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

import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.Flusher;
import org.eclipse.jetty.fcgi.generator.ServerGenerator;
import org.eclipse.jetty.fcgi.parser.ServerParser;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.server.AbstractMetaDataConnection;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerFCGIConnection extends AbstractMetaDataConnection implements ConnectionMetaData
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerFCGIConnection.class);

    private final Callback fillableCallback = new FillableCallback();
    private final HttpChannel.Factory httpChannelFactory = new HttpChannel.DefaultFactory();
    private final Attributes attributes = new Lazy();
    private final Connector connector;
    private final WritableBufferPool bufferPool;
    private final boolean sendStatus200;
    private final Flusher flusher;
    private final ServerParser parser;
    private final String id;
    private boolean useInputDirectByteBuffers;
    private boolean useOutputDirectByteBuffers;
    private RetainableByteBuffer.Mutable networkBuffer;
    private HttpStreamOverFCGI stream;
    private Runnable onRequest;

    public ServerFCGIConnection(Connector connector, EndPoint endPoint, HttpConfiguration configuration, boolean sendStatus200)
    {
        super(connector, configuration, endPoint);
        this.connector = connector;
        this.bufferPool = WritableBufferPool.wrap(connector.getByteBufferPool());
        this.flusher = new Flusher(endPoint);
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
        fillInterested(fillableCallback);
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFillable() {} {}", this, stream);

        try (RetainableByteBuffer.Mutable buffer = acquireBuffer())
        {
            while (true)
            {
                long read = buffer.remaining();
                if (read == 0)
                {
                    read = fillBuffer(buffer);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Read {} bytes from {} {}", read, getEndPoint(), this);
                }

                if (read > 0)
                {
                    if (parse(buffer))
                    {
                        if (stream == null)
                            return;

                        if (buffer.hasRemaining())
                        {
                            buffer.retain();
                            networkBuffer = buffer;
                        }

                        Runnable task = onRequest;
                        onRequest = null;
                        if (task != null)
                            getExecutor().execute(task);
                        return;
                    }
                }
                else if (read == 0)
                {
                    fillInterested(fillableCallback);
                    return;
                }
                else
                {
                    shutdown();
                    return;
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onFillable() failure", x);
            parser.eof();
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
            LOG.debug("parseAndFill {} {}", this, stream);

        try (RetainableByteBuffer.Mutable buffer = acquireBuffer())
        {
            // This loop must run only until the request is completed.
            while (stream != null)
            {
                if (parse(buffer))
                {
                    if (buffer.hasRemaining())
                    {
                        buffer.retain();
                        networkBuffer = buffer;
                    }
                    return;
                }

                // Check if the request was completed by the parsing; parse()
                // sets stream to null when the end of the stream is reached.
                int filled = 0;
                if (stream == null || (filled = fillBuffer(buffer)) <= 0)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("parseAndFill completed the request by parsing {}", this);
                    if (filled < 0)
                        stream.onContent(Content.Chunk.from(new EofException()));
                    return;
                }
            }
        }
    }

    private RetainableByteBuffer.Mutable acquireBuffer()
    {
        if (networkBuffer == null)
            return bufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
        RetainableByteBuffer.Mutable result = networkBuffer;
        networkBuffer = null;
        return result;
    }

    private int fillBuffer(RetainableByteBuffer.Mutable buffer)
    {
        try
        {
            return getEndPoint().fill(buffer.clear());
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not fill from {}", this, x);
            return -1;
        }
    }

    private boolean parse(RetainableByteBuffer buffer)
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
        if (failure == null)
            fillInterested(fillableCallback);
        else
            getFlusher().shutdown();
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeoutException)
    {
        HttpStreamOverFCGI stream = this.stream;
        if (stream == null)
            return true;
        return stream.onIdleTimeout(timeoutException);
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
            ServerGenerator generator = new ServerGenerator(WritableBufferPool.wrap(connector.getByteBufferPool()), isUseOutputDirectByteBuffers(), sendStatus200);
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

            if (stream == null)
                return false;

            onRequest = stream.onHeaders();
            // Return to onFillable() before dispatching to the application.
            return true;
        }

        @Override
        public boolean onContent(int request, FCGI.StreamType streamType, RetainableByteBuffer buffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} {} content {} on {}", request, streamType, buffer, stream);

            if (stream == null)
                return false;

            try (Content.Chunk chunk = Content.Chunk.from(buffer, false))
            {
                stream.onContent(chunk);
                // Signal that the content is processed asynchronously, to ensure backpressure.
                return true;
            }
        }

        @Override
        public boolean onEnd(int request)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} end on {}", request, stream);
            if (stream == null)
                return false;

            stream.onComplete();
            // Nulling out the stream signals that the
            // request is complete, see also parseAndFill().
            stream = null;
            return true;
        }

        @Override
        public void onFailure(int request, Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} failure on {}", request, stream, failure);
            if (stream != null)
                ThreadPool.executeImmediately(getExecutor(), stream.getHttpChannel().onFailure(new HttpException.IllegalStateException(HttpStatus.BAD_REQUEST_400, null, failure)));
            stream = null;
        }
    }

    @Override
    public void close()
    {
        try
        {
            if (stream != null)
            {
                Runnable task = stream.getHttpChannel().onClose();
                if (task != null)
                    task.run();
            }
        }
        finally
        {
            super.close();
        }
    }

    private class FillableCallback implements Callback
    {
        private final InvocationType invocationType = getConnector().getServer().getInvocationType();

        @Override
        public void succeeded()
        {
            onFillable();
        }

        @Override
        public void failed(Throwable x)
        {
            onFillInterestedFailed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return invocationType;
        }
    }
}
