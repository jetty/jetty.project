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
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
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
 * Note also that the size of the buffer can be controlled by setting the
 * {@link #BUFFER_SIZE_ATTRIBUTE_NAME} request attribute to an integer.
 * In the absence of such header, the {@link HttpConfiguration#getOutputBufferSize()}
 * config setting is used.
 * </p>
 */
public class BufferedResponseHandler extends ConditionalHandler.Abstract
{
    /**
     * The name of the request attribute used to control the buffer size of a particular request.
     */
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
                    Request request = getRequest();
                    _bufferedContentSink = Response.asBufferedSink(request, getWrapped(), getBufferSize(request), useDirectBuffers(request));
                }
                _firstWrite = false;
            }
            _lastWritten |= last;
            Content.Sink destSink = _bufferedContentSink != null ? _bufferedContentSink : getWrapped();
            destSink.write(last, byteBuffer, callback);
        }

        private static boolean useDirectBuffers(Request request)
        {
            return request.getConnectionMetaData().getHttpConfiguration().isUseOutputDirectByteBuffers();
        }

        private static int getBufferSize(Request request)
        {
            Object attribute = request.getAttribute(BufferedResponseHandler.BUFFER_SIZE_ATTRIBUTE_NAME);
            return attribute instanceof Integer ? (int)attribute : request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
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
