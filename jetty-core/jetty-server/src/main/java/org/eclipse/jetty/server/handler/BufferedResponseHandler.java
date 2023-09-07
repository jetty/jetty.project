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
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
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
public class BufferedResponseHandler extends Handler.Wrapper
{
    public static final String BUFFER_SIZE_ATTRIBUTE_NAME = BufferedResponseHandler.class.getName() + ".buffer-size";

    private static final int DEFAULT_BUFFER_SIZE = 16384;

    private static final Logger LOG = LoggerFactory.getLogger(BufferedResponseHandler.class);

    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExclude<String> _mimeTypes = new IncludeExclude<>();
    private int bufferSize = DEFAULT_BUFFER_SIZE;

    public BufferedResponseHandler()
    {
        this(null);
    }

    public BufferedResponseHandler(Handler handler)
    {
        super(handler);
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

    public int getBufferSize()
    {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize)
    {
        this.bufferSize = bufferSize;
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
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        if (LOG.isDebugEnabled())
            LOG.debug("{} handle {} in {}", this, request, request.getContext());

        // If not a supported method this URI is always excluded.
        if (!_methods.test(request.getMethod()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded by method {}", this, request);
            return super.handle(request, response, callback);
        }

        // If not a supported path this URI is always excluded.
        String path = Request.getPathInContext(request);
        if (!isPathBufferable(path))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded by path {}", this, request);
            return super.handle(request, response, callback);
        }

        // If the mime type is known from the path then apply mime type filtering.
        String mimeType = request.getContext().getMimeTypes().getMimeByExtension(path);
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

    private class BufferedResponse extends Response.Wrapper implements Callback
    {
        private final Callback _callback;
        private Content.Sink _bufferedContentSink;
        private boolean _firstWrite = true;
        private boolean _lastWritten;

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
                    int bufferSize = getBufferSize();
                    _bufferedContentSink = Content.Sink.asBuffered(getWrapped(), bufferPool, useOutputDirectByteBuffers, bufferSize, bufferSize);
                }
                _firstWrite = false;
            }
            _lastWritten |= last;
            Content.Sink destSink = _bufferedContentSink != null ? _bufferedContentSink : getWrapped();
            destSink.write(last, byteBuffer, callback);
        }

        private int getBufferSize()
        {
            Object attribute = getRequest().getAttribute(BufferedResponseHandler.BUFFER_SIZE_ATTRIBUTE_NAME);
            return attribute instanceof Integer ? (int)attribute : bufferSize;
        }

        @Override
        public void succeeded()
        {
            if (_bufferedContentSink != null && !_lastWritten)
                _bufferedContentSink.write(true, null, _callback);
            else
                _callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            if (_bufferedContentSink != null && !_lastWritten)
                _bufferedContentSink.write(true, null, Callback.NOOP);
            _callback.failed(x);
        }
    }
}
