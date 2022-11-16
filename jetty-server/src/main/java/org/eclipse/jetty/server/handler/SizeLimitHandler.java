//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A handler that can limit the size of message bodies in requests and responses.</p>
 * <p>The optional request and response limits are imposed by checking the {@code Content-Length}
 * header or observing the actual bytes seen by the handler. Handler order is important, in as
 * much as if this handler is before a the {@link org.eclipse.jetty.server.handler.gzip.GzipHandler},
 * then it will limit compressed sized, if it as after the {@link org.eclipse.jetty.server.handler.gzip.GzipHandler}
 * then the limit is applied to uncompressed bytes.
 * If a size limit is exceeded then {@link BadMessageException} is thrown with a
 * {@link org.eclipse.jetty.http.HttpStatus#PAYLOAD_TOO_LARGE_413} status.</p>
 */
public class SizeLimitHandler extends HandlerWrapper
{
    private static final Logger LOG = Log.getLogger(SizeLimitHandler.class);

    private final long _requestLimit;
    private final long _responseLimit;

    /**
     * @param requestLimit The request body size limit in bytes or -1 for no limit
     * @param responseLimit The response body size limit in bytes or -1 for no limit
     */
    public SizeLimitHandler(long requestLimit, long responseLimit)
    {
        _requestLimit = requestLimit;
        _responseLimit = responseLimit;
    }

    protected void checkRequestLimit(long size)
    {
        if (_requestLimit >= 0 && size > _requestLimit)
            throw new BadMessageException(413, "Request body is too large: " + size + ">" + _requestLimit);
    }

    protected void checkResponseLimit(long size)
    {
        if (_responseLimit >= 0 && size > _responseLimit)
            throw new BadMessageException(500, "Response body is too large: " + size + ">" + _responseLimit);
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (_requestLimit >= 0 || _responseLimit >= 0)
        {
            HttpOutput httpOutput = baseRequest.getResponse().getHttpOutput();
            HttpOutput.Interceptor interceptor = httpOutput.getInterceptor();
            LimitInterceptor limit = new LimitInterceptor(interceptor);

            if (_requestLimit >= 0)
            {
                long contentLength = baseRequest.getContentLengthLong();
                checkRequestLimit(contentLength);
                if (contentLength < 0)
                    baseRequest.getHttpInput().addInterceptor(limit);
            }

            if (_responseLimit > 0)
            {
                httpOutput.setInterceptor(limit);
                response = new LimitResponse(response);
            }
        }

        super.handle(target, baseRequest, request, response);
    }

    private class LimitInterceptor implements HttpOutput.Interceptor, HttpInput.Interceptor
    {
        private final HttpOutput.Interceptor _nextOutput;
        long _read;
        long _written;

        public LimitInterceptor(HttpOutput.Interceptor nextOutput)
        {
            _nextOutput = nextOutput;
        }

        @Override
        public HttpOutput.Interceptor getNextInterceptor()
        {
            return _nextOutput;
        }

        @Override
        public boolean isOptimizedForDirectBuffers()
        {
            return _nextOutput.isOptimizedForDirectBuffers();
        }

        @Override
        public HttpInput.Content readFrom(HttpInput.Content content)
        {
            if (content == null)
                return null;

            if (content.hasContent())
            {
                _read += content.remaining();
                checkResponseLimit(_read);
            }
            return content;
        }

        @Override
        public void write(ByteBuffer content, boolean last, Callback callback)
        {
            if (content.hasRemaining())
            {
                _written += content.remaining();

                try
                {
                    checkResponseLimit(_written);
                }
                catch (Throwable t)
                {
                    callback.failed(t);
                    return;
                }
            }
            getNextInterceptor().write(content, last, callback);
        }

        @Override
        public void resetBuffer()
        {
            _written = 0;
            getNextInterceptor().resetBuffer();
        }
    }

    private class LimitResponse extends HttpServletResponseWrapper
    {
        public LimitResponse(HttpServletResponse response)
        {
            super(response);
        }

        @Override
        public void setContentLength(int len)
        {
            checkResponseLimit(len);
            super.setContentLength(len);
        }

        @Override
        public void setContentLengthLong(long len)
        {
            checkResponseLimit(len);
            super.setContentLengthLong(len);
        }

        @Override
        public void setHeader(String name, String value)
        {
            if (HttpHeader.CONTENT_LENGTH.is(name))
                checkResponseLimit(Long.parseLong(value));
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value)
        {
            if (HttpHeader.CONTENT_LENGTH.is(name))
                checkResponseLimit(Long.parseLong(value));
            super.addHeader(name, value);
        }

        @Override
        public void setIntHeader(String name, int value)
        {
            if (HttpHeader.CONTENT_LENGTH.is(name))
                checkResponseLimit(value);
            super.setIntHeader(name, value);
        }

        @Override
        public void addIntHeader(String name, int value)
        {
            if (HttpHeader.CONTENT_LENGTH.is(name))
                checkResponseLimit(value);
            super.addIntHeader(name, value);
        }
    }
}
