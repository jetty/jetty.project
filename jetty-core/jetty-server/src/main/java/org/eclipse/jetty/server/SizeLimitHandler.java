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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;

/**
 * A handler that can limit the size of message bodies in requests and responses.
 *
 * <p>The optional request and response limits are imposed by checking the {@code Content-Length}
 * header or observing the actual bytes seen by the handler. Handler order is important, in as much
 * as if this handler is before a the {@link org.eclipse.jetty.server.handler.gzip.GzipHandler},
 * then it will limit compressed sized, if it as after the {@link
 * org.eclipse.jetty.server.handler.gzip.GzipHandler} then the limit is applied to uncompressed
 * bytes. If a size limit is exceeded then {@link BadMessageException} is thrown with a {@link
 * org.eclipse.jetty.http.HttpStatus#PAYLOAD_TOO_LARGE_413} status.
 */
public class SizeLimitHandler extends Handler.Wrapper
{
    private final long _requestLimit;
    private final long _responseLimit;
    private long _read = 0;
    private long _written = 0;

    /**
     * @param requestLimit The request body size limit in bytes or -1 for no limit
     * @param responseLimit The response body size limit in bytes or -1 for no limit
     */
    public SizeLimitHandler(long requestLimit, long responseLimit)
    {
        _requestLimit = requestLimit;
        _responseLimit = responseLimit;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        HttpField contentLengthField = request.getHeaders().getField(HttpHeader.CONTENT_LENGTH);
        if (contentLengthField != null)
        {
            long contentLength = contentLengthField.getLongValue();
            if (_requestLimit >= 0 && contentLength > _requestLimit)
            {
                String s = "Request body is too large: " + contentLength + ">" + _requestLimit;
                Response.writeError(request, response, callback, HttpStatus.PAYLOAD_TOO_LARGE_413, s);
                return true;
            }
        }

        HttpFields.Mutable.Wrapper httpFields = new HttpFields.Mutable.Wrapper(response.getHeaders())
        {
            @Override
            public HttpField onAddField(HttpField field)
            {
                if (field.getHeader().is(HttpHeader.CONTENT_LENGTH.asString()))
                {
                    long contentLength = field.getLongValue();
                    if (_responseLimit >= 0 && contentLength > _responseLimit)
                        throw new HttpException.RuntimeException(HttpStatus.INTERNAL_SERVER_ERROR_500, "Response body is too large: " + contentLength + ">" + _responseLimit);
                }
                return super.onAddField(field);
            }
        };

        response = new Response.Wrapper(request, response)
        {
            @Override
            public HttpFields.Mutable getHeaders()
            {
                return httpFields;
            }
        };

        request.addHttpStreamWrapper(httpStream -> new HttpStream.Wrapper(httpStream)
        {
            @Override
            public Content.Chunk read()
            {
                Content.Chunk chunk = super.read();
                if (chunk == null)
                    return null;
                if (chunk.getFailure() != null)
                    return chunk;

                // Check request content limit.
                ByteBuffer content = chunk.getByteBuffer();
                if (content != null && content.remaining() > 0)
                {
                    _read += content.remaining();
                    if (_requestLimit >= 0 && _read > _requestLimit)
                    {
                        BadMessageException e = new BadMessageException(HttpStatus.PAYLOAD_TOO_LARGE_413, "Request body is too large: " + _read + ">" + _requestLimit);
                        request.fail(e);
                        return null;
                    }
                }

                return chunk;
            }

            @Override
            public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
            {
                // Check response content limit.
                if (content != null && content.remaining() > 0)
                {
                    if (_responseLimit >= 0 && (_written + content.remaining())  > _responseLimit)
                    {
                        callback.failed(new HttpException.RuntimeException(HttpStatus.INTERNAL_SERVER_ERROR_500, "Response body is too large: " +
                            _written + content.remaining() + ">" + _responseLimit));
                        return;
                    }
                    _written += content.remaining();
                }

                super.send(request, response, last, content, callback);
            }
        });

        return super.handle(request, response, callback);
    }
}
