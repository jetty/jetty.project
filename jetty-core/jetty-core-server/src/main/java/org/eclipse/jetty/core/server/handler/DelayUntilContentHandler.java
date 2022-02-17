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
import org.eclipse.jetty.http.HttpHeader;

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

    private static class DelayUntilContentRequest extends Request.Wrapper
    {
        private DelayUntilContentRequest(Request wrapped)
        {
            super(wrapped);
        }

        @Override
        public void accept(Processor processor) throws Exception
        {
            // The nested Handler is accepting the exchange.

            // Accept the wrapped request.
            getWrapped().accept((rq, rs, cb) ->
            {
                // Demand for content.
                demandContent(() ->
                {
                    try
                    {
                        // When the content is available, process the nested exchange.
                        processor.process(this, rs, cb);
                    }
                    catch (Throwable x)
                    {
                        // TODO: improve exception handling.
                        cb.failed(x);
                    }
                });
            });
        }
    }
}
