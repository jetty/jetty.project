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

package org.eclipse.jetty.server.handler;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * A Handler that can apply a
 * mechanism to buffer the entire response content until the output is closed.
 * This allows the commit to be delayed until the response is complete and thus
 * headers and response status can be changed while writing the body.
 * </p>
 * <p>
 * Note that the decision to buffer is influenced by the headers and status at the
 * first write, and thus subsequent changes to those headers will not influence the
 * decision to buffer or not.
 * </p>
 * <p>
 * Note also that there are no memory limits to the size of the buffer, thus
 * this handler can represent an unbounded memory commitment if the content
 * generated can also be unbounded.
 * </p>
 */
public class BufferedResponseHandler extends ConditionalHandler.Abstract
{
    public static final String BUFFER_SIZE_ATTRIBUTE_NAME = BufferedResponseHandler.class.getName() + ".buffer-size";

    private static final Logger LOG = LoggerFactory.getLogger(BufferedResponseHandler.class);

    private final IncludeExclude<String> _mimeTypes = new IncludeExclude<>();

    public BufferedResponseHandler()
    {
        this(null);
    }

    public BufferedResponseHandler(Handler handler)
    {
        super(handler);

        includeMethod(HttpMethod.GET.asString());

        // Mimetypes are not a condition on the ConditionalHandler as they
        // are also check during response generation, once the type is known.
        for (String type : MimeTypes.DEFAULTS.getMimeMap().values())
        {
            if (type.startsWith("image/") ||
                type.startsWith("audio/") ||
                type.startsWith("video/"))
                _mimeTypes.exclude(type);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} mime types {}", this, _mimeTypes);
    }

    public void includeMimeType(String... mimeTypes)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _mimeTypes.include(mimeTypes);
    }

    public void excludeMimeType(String... mimeTypes)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _mimeTypes.exclude(mimeTypes);
    }

    protected boolean isMimeTypeBufferable(String mimetype)
    {
        return _mimeTypes.test(mimetype);
    }

    protected boolean shouldBuffer(Response response, boolean last)
    {
        if (last)
            return false;

        int status = response.getStatus();
        if (HttpStatus.hasNoBody(status) || HttpStatus.isRedirection(status))
            return false;

        String ct = response.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (ct == null)
            return true;

        ct = MimeTypes.getContentTypeWithoutCharset(ct);
        return isMimeTypeBufferable(StringUtil.asciiToLowerCase(ct));
    }

    @Override
    public boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        if (LOG.isDebugEnabled())
            LOG.debug("{} doHandle {} in {}", this, request, request.getContext());

        // If the mime type is known from the path then apply mime type filtering.
        String mimeType = request.getContext().getMimeTypes().getMimeByExtension(request.getHttpURI().getCanonicalPath());
        if (mimeType != null)
        {
            mimeType = MimeTypes.getContentTypeWithoutCharset(mimeType);
            if (!isMimeTypeBufferable(mimeType))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} excluded by path suffix mime type {}", this, request);

                // handle normally
                return super.handle(request, response, callback);
            }
        }

        BufferedResponse bufferedResponse = new BufferedResponse(request, response, callback);
        return next.handle(request, bufferedResponse, bufferedResponse);
    }

    @Override
    protected boolean onConditionsNotMet(Request request, Response response, Callback callback) throws Exception
    {
        return nextHandler(request, response, callback);
    }

    private class BufferedResponse extends Response.Wrapper implements Callback
    {
        private final Callback _callback;
        private CountingByteBufferAccumulator _accumulator;
        private boolean _firstWrite = true;

        private BufferedResponse(Request request, Response response, Callback callback)
        {
            super(request, response);
            _callback = callback;
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            if (_firstWrite)
            {
                if (shouldBuffer(this, last))
                {
                    ConnectionMetaData connectionMetaData = getRequest().getConnectionMetaData();
                    ByteBufferPool bufferPool = connectionMetaData.getConnector().getByteBufferPool();
                    boolean useOutputDirectByteBuffers = connectionMetaData.getHttpConfiguration().isUseOutputDirectByteBuffers();
                    _accumulator = new CountingByteBufferAccumulator(bufferPool, useOutputDirectByteBuffers, getBufferSize());
                }
                _firstWrite = false;
            }

            if (_accumulator != null)
            {
                ByteBuffer current = byteBuffer != null ? byteBuffer : BufferUtil.EMPTY_BUFFER;
                IteratingNestedCallback writer = new IteratingNestedCallback(callback)
                {
                    private boolean complete;

                    @Override
                    protected Action process()
                    {
                        if (complete)
                            return Action.SUCCEEDED;
                        boolean write = _accumulator.copyBuffer(current);
                        complete = last && !current.hasRemaining();
                        if (write || complete)
                        {
                            RetainableByteBuffer buffer = _accumulator.takeRetainableByteBuffer();
                            BufferedResponse.super.write(complete, buffer.getByteBuffer(), Callback.from(this, buffer::release));
                            return Action.SCHEDULED;
                        }
                        return Action.SUCCEEDED;
                    }
                };
                writer.iterate();
            }
            else
            {
                super.write(last, byteBuffer, callback);
            }
        }

        private int getBufferSize()
        {
            Object attribute = getRequest().getAttribute(BufferedResponseHandler.BUFFER_SIZE_ATTRIBUTE_NAME);
            return attribute instanceof Integer ? (int)attribute : Integer.MAX_VALUE;
        }

        @Override
        public void succeeded()
        {
            // TODO pass all accumulated buffers as an array instead of allocating & copying into a single one.
            if (_accumulator != null)
            {
                RetainableByteBuffer buffer = _accumulator.takeRetainableByteBuffer();
                super.write(true, buffer.getByteBuffer(), Callback.from(_callback, () ->
                {
                    buffer.release();
                    _accumulator.close();
                }));
            }
            else
            {
                _callback.succeeded();
            }
        }

        @Override
        public void failed(Throwable x)
        {
            if (_accumulator != null)
                _accumulator.close();
            _callback.failed(x);
        }
    }

    private static class CountingByteBufferAccumulator implements AutoCloseable
    {
        private final ByteBufferAccumulator _accumulator;
        private final int _maxSize;
        private int _accumulatedCount;

        private CountingByteBufferAccumulator(ByteBufferPool bufferPool, boolean direct, int maxSize)
        {
            if (maxSize <= 0)
                throw new IllegalArgumentException("maxSize must be > 0, was: " + maxSize);
            _maxSize = maxSize;
            _accumulator = new ByteBufferAccumulator(bufferPool, direct);
        }

        private boolean copyBuffer(ByteBuffer buffer)
        {
            int remainingCapacity = space();
            if (buffer.remaining() >= remainingCapacity)
            {
                _accumulatedCount += remainingCapacity;
                int end = buffer.position() + remainingCapacity;
                _accumulator.copyBuffer(buffer.duplicate().limit(end));
                buffer.position(end);
                return true;
            }
            else
            {
                _accumulatedCount += buffer.remaining();
                _accumulator.copyBuffer(buffer);
                return false;
            }
        }

        private int space()
        {
            return _maxSize - _accumulatedCount;
        }

        private RetainableByteBuffer takeRetainableByteBuffer()
        {
            _accumulatedCount = 0;
            return _accumulator.takeRetainableByteBuffer();
        }

        @Override
        public void close()
        {
            _accumulatedCount = 0;
            _accumulator.close();
        }
    }
}
