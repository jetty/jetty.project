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

import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.thread.Invocable;

public class HandleOnContentHandler extends Handler.Wrapper
{
    @Override
    public void handle(Request request) throws Exception
    {
        // If no content or content available, then don't delay dispatch
        if (request.getContentLength() <= 0 && !request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
            super.handle(request);
        else
        {
            Response response = request.accept();
            if (response == null)
                return;
            request.demandContent(new OnContentRunner(request, response));
        }
    }

    private class OnContentRunner implements Runnable, Invocable
    {
        private final Request _request;
        private final Response _response;

        public OnContentRunner(Request request, Response response)
        {
            _request = request;
            _response = response;
        }

        @Override
        public void run()
        {
            try
            {
                AtomicBoolean accepted = new AtomicBoolean();
                Request request = new Request.Wrapper(_request)
                {
                    @Override
                    public Response accept()
                    {
                        if (accepted.compareAndSet(false, true))
                            return _response;
                        return null;
                    }

                    @Override
                    public boolean isAccepted()
                    {
                        return accepted.get();
                    }
                };
                HandleOnContentHandler.super.handle(request);
                if (!request.isAccepted())
                    _response.getCallback().failed(new IllegalStateException("Not accepted after content"));
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.EITHER;
        }
    }
}
