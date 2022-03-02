//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * Dump request handler.
 * Dumps GET and POST requests.
 * Useful for testing and debugging.
 */
public class EchoHandler extends Handler.Processor
{
    @Override
    public void process(Request request, Response response, Callback callback)
    {
        response.setStatus(200);
        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (StringUtil.isNotBlank(contentType))
            response.setContentType(contentType);
        HttpFields.Mutable trailers = null;
        if (request.getHeaders().contains(HttpHeader.TRAILER))
            trailers = response.getTrailers();
        long contentLength = request.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
        if (contentLength >= 0)
            response.setContentLength(contentLength);
        if (contentLength > 0 || contentLength == -1 && request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
            new Echo(request, response, trailers, callback).run();
        else
            callback.succeeded();
    }

    static class Echo implements Runnable, Invocable, Callback
    {
        private static final Content ITERATING = new Content.Abstract(true, false){};
        private final Request _request;
        private final Response _response;
        private final HttpFields.Mutable _trailers;
        private final Callback _callback;
        private final AtomicReference<Content> _content = new AtomicReference<>();

        Echo(Request request, Response response, HttpFields.Mutable trailers, Callback callback)
        {
            _request = request;
            _response = response;
            _trailers = trailers;
            _callback = callback;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        public void run()
        {
            while (true)
            {
                Content content = _request.readContent();
                if (content == null)
                {
                    _request.demandContent(this);
                    return;
                }

                if (content instanceof Content.Trailers && _trailers != null)
                    _trailers.add("Echo", "Trailers").add(((Content.Trailers)content).getTrailers());

                if (!content.hasRemaining() && content.isLast())
                {
                    content.release();
                    _callback.succeeded();
                    return;
                }

                _content.set(ITERATING);
                _response.write(content.isLast(), this, content.getByteBuffer());
                if (_content.compareAndSet(ITERATING, content))
                    return;
                content.release();
            }
        }

        @Override
        public void succeeded()
        {
            Content content = _content.getAndSet(null);
            if (content == ITERATING)
                return;
            content.release();
            run();
        }

        @Override
        public void failed(Throwable x)
        {
            _callback.failed(x);
        }
    }
}
