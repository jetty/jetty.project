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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.HttpOutput.Interceptor;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * A Handler that can apply a {@link HttpOutput.Interceptor}
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
public class BufferedResponseHandler extends HandlerWrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(BufferedResponseHandler.class);

    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExclude<String> _mimeTypes = new IncludeExclude<>();

    public BufferedResponseHandler()
    {
        _methods.include(HttpMethod.GET.asString());
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

    public IncludeExclude<String> getMethodIncludeExclude()
    {
        return _methods;
    }

    public IncludeExclude<String> getPathIncludeExclude()
    {
        return _paths;
    }

    public IncludeExclude<String> getMimeIncludeExclude()
    {
        return _mimeTypes;
    }

    protected boolean isMimeTypeBufferable(String mimetype)
    {
        return _mimeTypes.test(mimetype);
    }

    protected boolean isPathBufferable(String requestURI)
    {
        if (requestURI == null)
            return true;

        return _paths.test(requestURI);
    }

    protected boolean shouldBuffer(HttpChannel channel, boolean last)
    {
        if (last)
            return false;

        Response response = channel.getResponse();
        int status = response.getStatus();
        if (HttpStatus.hasNoBody(status) || HttpStatus.isRedirectionWithLocation(status))
            return false;

        String ct = response.getContentType();
        if (ct == null)
            return true;

        ct = MimeTypes.getContentTypeWithoutCharset(ct);
        return isMimeTypeBufferable(StringUtil.asciiToLowerCase(ct));
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        final ServletContext context = baseRequest.getServletContext();
        final String path = baseRequest.getPathInContext();

        if (LOG.isDebugEnabled())
            LOG.debug("{} handle {} in {}", this, baseRequest, context);

        // Are we already buffering?
        HttpOutput out = baseRequest.getResponse().getHttpOutput();
        HttpOutput.Interceptor interceptor = out.getInterceptor();
        while (interceptor != null)
        {
            if (interceptor instanceof BufferedInterceptor)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} already intercepting {}", this, request);
                _handler.handle(target, baseRequest, request, response);
                return;
            }
            interceptor = interceptor.getNextInterceptor();
        }

        // If not a supported method this URI is always excluded.
        if (!_methods.test(baseRequest.getMethod()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded by method {}", this, request);
            _handler.handle(target, baseRequest, request, response);
            return;
        }

        // If not a supported path this URI is always excluded.
        if (!isPathBufferable(path))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded by path {}", this, request);
            _handler.handle(target, baseRequest, request, response);
            return;
        }

        // If the mime type is known from the path then apply mime type filtering.
        String mimeType = context == null ? MimeTypes.DEFAULTS.getMimeByExtension(path) : context.getMimeType(path);
        if (mimeType != null)
        {
            mimeType = MimeTypes.getContentTypeWithoutCharset(mimeType);
            if (!isMimeTypeBufferable(mimeType))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} excluded by path suffix mime type {}", this, request);

                // handle normally without setting vary header
                _handler.handle(target, baseRequest, request, response);
                return;
            }
        }

        // Install buffered interceptor and handle.
        out.setInterceptor(newBufferedInterceptor(baseRequest.getHttpChannel(), out.getInterceptor()));
        if (_handler != null)
            _handler.handle(target, baseRequest, request, response);
    }

    protected BufferedInterceptor newBufferedInterceptor(HttpChannel httpChannel, Interceptor interceptor)
    {
        return new ArrayBufferedInterceptor(httpChannel, interceptor);
    }

    /**
     * An {@link HttpOutput.Interceptor} which is created by {@link #newBufferedInterceptor(HttpChannel, Interceptor)}
     * and is used by the implementation to buffer outgoing content.
     */
    protected interface BufferedInterceptor extends HttpOutput.Interceptor
    {
    }

    class ArrayBufferedInterceptor implements BufferedInterceptor
    {
        private final Interceptor _next;
        private final HttpChannel _channel;
        private Boolean _aggregating;
        private RetainableByteBuffer.Mutable _aggregate;

        public ArrayBufferedInterceptor(HttpChannel httpChannel, Interceptor interceptor)
        {
            _next = interceptor;
            _channel = httpChannel;
        }

        @Override
        public Interceptor getNextInterceptor()
        {
            return _next;
        }

        @Override
        public void resetBuffer()
        {
            _aggregating = null;
            _aggregate = null;
            BufferedInterceptor.super.resetBuffer();
        }

        @Override
        public void write(ByteBuffer content, boolean last, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} write last={} {}", this, last, BufferUtil.toDetailString(content));

            // If we are not committed, have to decide if we should aggregate or not.
            if (_aggregating == null)
                _aggregating = shouldBuffer(_channel, last);

            // If we are not aggregating, then handle normally.
            if (!_aggregating)
            {
                getNextInterceptor().write(content, last, callback);
                return;
            }

            if (last)
            {
                // Add the current content to the buffer list without a copy.
                if (BufferUtil.length(content) > 0)
                {
                    if (_aggregate == null)
                    {
                        getNextInterceptor().write(content, true, callback);
                        return;
                    }
                    RetainableByteBuffer retainable = RetainableByteBuffer.wrap(content, () -> {});
                    _aggregate.append(retainable);
                    retainable.release();
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("{} committing {}", this, _aggregate);
                commit(callback);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} aggregating", this);

                while (BufferUtil.hasContent(content))
                {
                    if (_aggregate == null)
                        _aggregate = new RetainableByteBuffer.DynamicCapacity(_channel.getByteBufferPool(), false, -1, Math.max(_channel.getHttpConfiguration().getOutputBufferSize(), BufferUtil.length(content)));

                    if (!_aggregate.append(content))
                    {
                        _aggregate.release();
                        _aggregate = null;
                        callback.failed(new BufferOverflowException());
                        return;
                    }
                }
                callback.succeeded();
            }
        }

        private void commit(Callback callback)
        {
            _aggregate.writeTo(getNextInterceptor(), true, Callback.from(this::completed, callback));
        }

        private void completed()
        {
            _aggregate.release();
            _aggregate = null;
        }
    }
}
