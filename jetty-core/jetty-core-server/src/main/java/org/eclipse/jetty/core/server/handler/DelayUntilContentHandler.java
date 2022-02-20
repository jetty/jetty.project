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
import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Callback;

public class DelayUntilContentHandler extends Handler.Wrapper
{
    @Override
    public Processor offer(Request request) throws Exception
    {
        // If no content or content available, then don't delay dispatch.
        if (request.getContentLength() < 0 && !request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
            return super.offer(request);

        Processor processor = super.offer(request);
        if (processor == null)
            return null;
        return new DelayedProcessor(processor);
    }

    static class DelayedProcessor implements Processor, Runnable
    {
        private final Processor _processor;
        private Request _request;
        private Response _response;
        private Callback _callback;

        DelayedProcessor(Processor processor)
        {
            _processor = processor;
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            _request = request;
            _response = response;
            _callback = callback;
            request.demandContent(this);
        }

        @Override
        public void run()
        {
            try
            {
                _processor.process(_request, _response, _callback);
            }
            catch (Throwable t)
            {
                _response.writeError(_request, t, _callback);
            }
        }
    }
}
