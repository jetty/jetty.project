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

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressSet;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * InetAddress Access Handler
 * <p>
 * Controls access to the wrapped handler using the real remote IP. Control is
 * provided by and {@link IncludeExcludeSet} over a {@link InetAddressSet}. This
 * handler uses the real internet address of the connection, not one reported in
 * the forwarded for headers, as this cannot be as easily forged.
 * <p>
 * Additionally, there may be times when you want to only apply this handler to
 * a subset of your connectors. In this situation you can use
 * <b>connectorNames</b> to specify the connector names that you want this IP
 * access filter to apply to.
 */
public class SizeLimitHandler extends HandlerWrapper
{
    private static final Logger LOG = Log.getLogger(SizeLimitHandler.class);

    private final long _requestLimit;
    private final long _responseLimit;

    public SizeLimitHandler(long requestLimit, long responseLimit)
    {
        _requestLimit = requestLimit;
        _responseLimit = responseLimit;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (_requestLimit >= 0)
        {
            long contentLength = baseRequest.getContentLengthLong();
            if (contentLength > _requestLimit)
                throw new BadMessageException(413, "Request Body too large: " + contentLength + ">" + _requestLimit);
            else if (contentLength < 0)
            {
                baseRequest.getHttpInput().addInterceptor(new HttpInput.Interceptor()
                {
                    long _bytesRead;

                    @Override
                    public HttpInput.Content readFrom(HttpInput.Content content)
                    {
                        if (content.hasContent())
                        {
                            _bytesRead += content.remaining();
                            if (_bytesRead > _requestLimit)
                                throw new BadMessageException(413, "Request body too large:" +
                                    _bytesRead + ">" + _requestLimit);
                        }
                        return content;
                    }
                });
            }
        }

        if (_responseLimit > 0)
        {
            HttpOutput httpOutput = baseRequest.getResponse().getHttpOutput();
            HttpOutput.Interceptor interceptor = httpOutput.getInterceptor();
            httpOutput.setInterceptor(new HttpOutput.Interceptor()
            {
                long _written;

                @Override
                public void write(ByteBuffer content, boolean last, Callback callback)
                {
                    if (content.hasRemaining())
                    {
                        _written += content.remaining();
                        if (_written > _responseLimit)
                            throw new BadMessageException(413, "Response body too large: " +
                                _written + ">" + _responseLimit);
                    }
                    getNextInterceptor().write(content, last, callback);
                }

                @Override
                public HttpOutput.Interceptor getNextInterceptor()
                {
                    return interceptor;
                }

                @Override
                public boolean isOptimizedForDirectBuffers()
                {
                    return interceptor.isOptimizedForDirectBuffers();
                }
            });
        }

        super.handle(target, baseRequest, request, response);
    }
}
