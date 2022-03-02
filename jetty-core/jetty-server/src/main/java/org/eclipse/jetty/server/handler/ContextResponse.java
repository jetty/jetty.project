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

import java.nio.ByteBuffer;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

class ContextResponse extends Response.Wrapper
{
    private final ContextHandler.ScopedContext _context;
    private final Request _request;

    public ContextResponse(ContextHandler.ScopedContext context, Request request, Response response)
    {
        super(response);
        _context = context;
        _request = request;
    }

    @Override
    public void write(boolean last, Callback callback, ByteBuffer... content)
    {
        Callback contextCallback = new Callback()
        {
            @Override
            public void succeeded()
            {
                _context.run(callback::succeeded, _request);
            }

            @Override
            public void failed(Throwable t)
            {
                _context.accept(callback::failed, t, _request);
            }
        };
        super.write(last, contextCallback, content);
    }
}
