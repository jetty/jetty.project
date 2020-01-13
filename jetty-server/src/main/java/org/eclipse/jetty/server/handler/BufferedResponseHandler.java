//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.HttpOutput.Interceptor;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Buffered Response Handler
 * <p>
 * A Handler that can apply a {@link org.eclipse.jetty.server.HttpOutput.Interceptor}
 * mechanism to buffer the entire response content until the output is closed.
 * This allows the commit to be delayed until the response is complete and thus
 * headers and response status can be changed while writing the body.
 * <p>
 * Note that the decision to buffer is influenced by the headers and status at the
 * first write, and thus subsequent changes to those headers will not influence the
 * decision to buffer or not.
 * <p>
 * Note also that there are no memory limits to the size of the buffer, thus
 * this handler can represent an unbounded memory commitment if the content
 * generated can also be unbounded.
 * </p>
 */
public class BufferedResponseHandler extends HandlerWrapper
{
    static final Logger LOG = Log.getLogger(BufferedResponseHandler.class);

    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExclude<String> _mimeTypes = new IncludeExclude<>();

    public BufferedResponseHandler()
    {
        // include only GET requests

        _methods.include(HttpMethod.GET.asString());
        // Exclude images, aduio and video from buffering
        for (String type : MimeTypes.getKnownMimeTypes())
        {
            if (type.startsWith("image/") ||
                type.startsWith("audio/") ||
                type.startsWith("video/"))
                _mimeTypes.exclude(type);
        }
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

    /**
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#handle(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        ServletContext context = baseRequest.getServletContext();
        String path = context == null ? baseRequest.getRequestURI() : URIUtil.addPaths(baseRequest.getServletPath(), baseRequest.getPathInfo());
        LOG.debug("{} handle {} in {}", this, baseRequest, context);

        HttpOutput out = baseRequest.getResponse().getHttpOutput();

        // Are we already being gzipped?
        HttpOutput.Interceptor interceptor = out.getInterceptor();
        while (interceptor != null)
        {
            if (interceptor instanceof BufferedInterceptor)
            {
                LOG.debug("{} already intercepting {}", this, request);
                _handler.handle(target, baseRequest, request, response);
                return;
            }
            interceptor = interceptor.getNextInterceptor();
        }

        // If not a supported method - no Vary because no matter what client, this URI is always excluded
        if (!_methods.test(baseRequest.getMethod()))
        {
            LOG.debug("{} excluded by method {}", this, request);
            _handler.handle(target, baseRequest, request, response);
            return;
        }

        // If not a supported URI- no Vary because no matter what client, this URI is always excluded
        // Use pathInfo because this is be
        if (!isPathBufferable(path))
        {
            LOG.debug("{} excluded by path {}", this, request);
            _handler.handle(target, baseRequest, request, response);
            return;
        }

        // If the mime type is known from the path, then apply mime type filtering 
        String mimeType = context == null ? MimeTypes.getDefaultMimeByExtension(path) : context.getMimeType(path);
        if (mimeType != null)
        {
            mimeType = MimeTypes.getContentTypeWithoutCharset(mimeType);
            if (!isMimeTypeBufferable(mimeType))
            {
                LOG.debug("{} excluded by path suffix mime type {}", this, request);
                // handle normally without setting vary header
                _handler.handle(target, baseRequest, request, response);
                return;
            }
        }

        // install interceptor and handle
        out.setInterceptor(new BufferedInterceptor(baseRequest.getHttpChannel(), out.getInterceptor()));

        if (_handler != null)
            _handler.handle(target, baseRequest, request, response);
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

    private class BufferedInterceptor implements HttpOutput.Interceptor
    {
        final Interceptor _next;
        final HttpChannel _channel;
        final Queue<ByteBuffer> _buffers = new ConcurrentLinkedQueue<>();
        Boolean _aggregating;
        ByteBuffer _aggregate;

        public BufferedInterceptor(HttpChannel httpChannel, Interceptor interceptor)
        {
            _next = interceptor;
            _channel = httpChannel;
        }

        @Override
        public void resetBuffer()
        {
            _buffers.clear();
            _aggregating = null;
            _aggregate = null;
        }

        @Override
        public void write(ByteBuffer content, boolean last, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} write last={} {}", this, last, BufferUtil.toDetailString(content));
            // if we are not committed, have to decide if we should aggregate or not
            if (_aggregating == null)
            {
                Response response = _channel.getResponse();
                int sc = response.getStatus();
                if (sc > 0 && (sc < 200 || sc == 204 || sc == 205 || sc >= 300))
                    _aggregating = Boolean.FALSE;  // No body
                else
                {
                    String ct = response.getContentType();
                    if (ct == null)
                        _aggregating = Boolean.TRUE;
                    else
                    {
                        ct = MimeTypes.getContentTypeWithoutCharset(ct);
                        _aggregating = isMimeTypeBufferable(StringUtil.asciiToLowerCase(ct));
                    }
                }
            }

            // If we are not aggregating, then handle normally 
            if (!_aggregating.booleanValue())
            {
                getNextInterceptor().write(content, last, callback);
                return;
            }

            // If last
            if (last)
            {
                // Add the current content to the buffer list without a copy
                if (BufferUtil.length(content) > 0)
                    _buffers.add(content);

                if (LOG.isDebugEnabled())
                    LOG.debug("{} committing {}", this, _buffers.size());
                commit(_buffers, callback);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} aggregating", this);

                // Aggregate the content into buffer chain
                while (BufferUtil.hasContent(content))
                {
                    // Do we need a new aggregate buffer
                    if (BufferUtil.space(_aggregate) == 0)
                    {
                        int size = Math.max(_channel.getHttpConfiguration().getOutputBufferSize(), BufferUtil.length(content));
                        _aggregate = BufferUtil.allocate(size); // TODO use a buffer pool
                        _buffers.add(_aggregate);
                    }

                    BufferUtil.append(_aggregate, content);
                }
                callback.succeeded();
            }
        }

        @Override
        public Interceptor getNextInterceptor()
        {
            return _next;
        }

        @Override
        public boolean isOptimizedForDirectBuffers()
        {
            return false;
        }

        protected void commit(Queue<ByteBuffer> buffers, Callback callback)
        {
            // If only 1 buffer
            if (_buffers.size() == 0)
                getNextInterceptor().write(BufferUtil.EMPTY_BUFFER, true, callback);
            else if (_buffers.size() == 1)
                // just flush it with the last callback
                getNextInterceptor().write(_buffers.remove(), true, callback);
            else
            {
                // Create an iterating callback to do the writing
                IteratingCallback icb = new IteratingCallback()
                {
                    @Override
                    protected Action process() throws Exception
                    {
                        ByteBuffer buffer = _buffers.poll();
                        if (buffer == null)
                            return Action.SUCCEEDED;

                        getNextInterceptor().write(buffer, _buffers.isEmpty(), this);
                        return Action.SCHEDULED;
                    }

                    @Override
                    protected void onCompleteSuccess()
                    {
                        // Signal last callback
                        callback.succeeded();
                    }

                    @Override
                    protected void onCompleteFailure(Throwable cause)
                    {
                        // Signal last callback
                        callback.failed(cause);
                    }
                };
                icb.iterate();
            }
        }
    }
}
