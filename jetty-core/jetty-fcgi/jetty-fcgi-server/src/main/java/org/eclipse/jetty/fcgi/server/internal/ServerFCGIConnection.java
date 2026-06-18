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
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.server.AbstractMetaDataConnection;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
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
    private ReadableBuffer inputBuffer;
    private HttpStreamOverFCGI stream;
    private State state = State.IDLE;
    private Content.Chunk chunk;
    private Throwable failure;

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
            LOG.debug("onFillable {} {} {}", this, stream, inputBuffer);

        process(true);
    }

    void process(boolean setFillInterest)
    {
        fillAndParse(setFillInterest);

        switch (state)
        {
            case HEADERS ->
            {
                state = State.CONTENT;
                stream.onHeaders();
            }
            case CONTENT ->
            {
                if (chunk != null)
                {
                    stream.onContent(chunk);
                    chunk.release();
                    chunk = null;
                }
            }
            case COMPLETE ->
            {
                stream.onComplete();
                stream = null;
                state =  State.IDLE;
            }
            case FAILED ->
            {
                stream.onFailure(failure);
                stream = null;
                getEndPoint().close(failure);
            }
        }
    }

    private void fillAndParse(boolean setFillInterest)
    {
        ReadableBuffer readable = null;
        WritableBuffer writable = null;
        if (inputBuffer != null)
        {
            readable = inputBuffer;
            inputBuffer = null;
        }
        else
        {
            writable = bufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
        }

        try
        {
            while (true)
            {
                if (writable != null)
                {
                    int read = fillInputBuffer(writable);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Read {} bytes from {} {}", read, getEndPoint(), this);

                    if (read <= 0)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Releasing {}", writable);
                        writable.release();

                        if (read == 0)
                        {
                            if (setFillInterest)
                                fillInterested(fillableCallback);
                        }
                        else
                        {
                            parser.eof();
                        }
                        return;
                    }

                    readable = writable.toReadable();
                }

                assert readable != null;

                if (parse(readable))
                {
                    if (readable.remaining() == 0)
                        readable.release();
                    else
                        inputBuffer = readable;
                    return;
                }

                // Check if the buffer has been retained by the application.
                // This may happen when the buffer read from the network
                // a "data frame" and then some bytes of the next "data frame":
                // reusing the buffer would overwrite the first "data frame" bytes.
                if (readable.isRetained())
                {
                    readable.release();
                    writable = bufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
                    if (LOG.isDebugEnabled())
                        LOG.debug("Reacquired {}", writable);
                }
                else
                {
                    writable = readable.toWritable();
                }
            }
        }
        catch (Exception x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to fill endpoint", x);
            if (writable != null)
                writable.release();
            else
                readable.release();
            parser.eof();
        }
    }

    private int fillInputBuffer(WritableBuffer buffer)
    {
        try
        {
            return getEndPoint().fill(buffer);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not fill from {}", this, x);
            return -1;
        }
    }

    private boolean parse(ReadableBuffer buffer)
    {
        while (buffer.remaining() > 0)
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
        HttpChannel.IdleTimeoutTask task = stream.getHttpChannel().onIdleTimeout(timeoutException);
        boolean handlingRequest = task.handlingRequest();
        if (handlingRequest)
            ThreadPool.executeImmediately(getExecutor(), task.action());
        return !handlingRequest;
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

            state = State.HEADERS;
            // We will call the application, stop the fill & parse loop.
            return true;
        }

        @Override
        public boolean onContent(int request, FCGI.StreamType streamType, ReadableBuffer buffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} {} content {} on {}", request, streamType, buffer, stream);

            if (stream == null)
                return false;

            state = State.CONTENT;
            chunk = Content.Chunk.from(buffer, false);
            // Signal that the content is processed asynchronously, to ensure backpressure.
            return true;
        }

        @Override
        public boolean onEnd(int request)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Request {} end on {}", request, stream);

            if (stream == null)
                return false;

            state = State.COMPLETE;
            return true;
        }

        @Override
        public void onFailure(int request, Throwable cause)
        {
            if (stream == null)
                return;

            if (LOG.isDebugEnabled())
                LOG.debug("Request {} failure on {}", request, stream, cause);

            state = State.FAILED;
            failure = cause;
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

    private enum State
    {
        IDLE,
        HEADERS,
        CONTENT,
        COMPLETE,
        FAILED
    }
}
