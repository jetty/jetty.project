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

package org.eclipse.jetty.core.server.handler;

import org.eclipse.jetty.core.server.Handler;
import org.eclipse.jetty.core.server.Processor;
import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Callback;

public class DelayUntilContentHandler extends Handler.Wrapper
{
    @Override
    public void accept(Request request) throws Exception
    {
        // If no content or content available, then don't delay dispatch.
        if (request.getContentLength() <= 0 && !request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
        {
            super.accept(request);
        }
        else
        {
            super.accept(new DelayUntilContentRequest(request));
        }
    }

    private static class DelayUntilContentRequest extends Request.Wrapper implements Processor, Runnable
    {
        private Processor _processor;
        private Response _response;
        private Callback _callback;

        private DelayUntilContentRequest(Request wrapped)
        {
            super(wrapped);
        }

        @Override
        public void accept(Processor processor) throws Exception
        {
            // The nested Handler is accepting the exchange.
            _processor = processor;

            // Accept the wrapped request.
            getWrapped().accept(this);
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            _response = response;
            _callback = callback;
            // Demand for content.
            demandContent(this);
        }

        @Override
        public void run()
        {
            try
            {
                // When the content is available, process the nested exchange.
                _processor.process(this, _response, _callback);
            }
            catch (Throwable x)
            {
                // TODO: improve exception handling.
                _callback.failed(x);
            }
        }
    }
}
