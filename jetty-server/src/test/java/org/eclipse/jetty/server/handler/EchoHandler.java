//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.StringUtil;

/**
 * Dump request handler.
 * Dumps GET and POST requests.
 * Useful for testing and debugging.
 */
public class EchoHandler extends Handler.Abstract
{
    @Override
    public boolean handle(Request request, Response response) throws Exception
    {
        response.setStatus(200);
        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (StringUtil.isNotBlank(contentType))
            response.setContentType(contentType);
        long contentLength = request.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
        if (contentLength >= 0)
            response.setContentLength(contentLength);
        if (request.getHeaders().contains(HttpHeader.TRAILER))
            response.getTrailers();
        new Echo(request, response).iterate();
        return true;
    }

    static class Echo extends IteratingCallback
    {
        private final Request _request;
        private final Response _response;

        Echo(Request request, Response response)
        {
            _request = request;
            _response = response;
        }

        @Override
        protected Action process() throws Throwable
        {
            Content content = _request.readContent();
            if (content == null)
            {
                _request.demandContent(this::succeeded);
                return Action.SCHEDULED;
            }

            if (content instanceof Content.Trailers)
            {
                _response.getTrailers()
                    .add("Echo", "Trailers")
                    .add(((Content.Trailers)content).getTrailers());
                this.succeeded();
                return Action.SCHEDULED;
            }

            if (!content.hasRemaining() && content.isLast())
                return Action.SUCCEEDED;

            _response.write(content.isLast(), this, content.getByteBuffer());
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            _request.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            _request.failed(cause);
        }
    }
}
