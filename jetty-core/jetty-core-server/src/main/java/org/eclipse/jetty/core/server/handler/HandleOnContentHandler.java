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
import org.eclipse.jetty.http.HttpHeader;

public class HandleOnContentHandler extends Handler.Wrapper
{
    @Override
    public void offer(Request request, Acceptor acceptor) throws Exception
    {
        // If no content or content available, then don't delay dispatch
        if (request.getContentLength() <= 0 && !request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
            super.offer(request, acceptor);
        else
            super.offer(request, (req, processor) -> acceptor.accept(req, exchange -> exchange.demandContent(() ->
            {
                try
                {
                    processor.process(exchange);
                }
                catch (Throwable t)
                {
                    exchange.failed(t);
                }
            })));
    }
}
