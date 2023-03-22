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

package org.eclipse.jetty.server.handler;

import java.nio.ByteBuffer;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;

public class ContextResponse extends Response.Wrapper
{
    private final ContextHandler.ScopedContext _context;

    public ContextResponse(ContextHandler.ScopedContext context, Request request, Response response)
    {
        super(request, response);
        _context = context;
    }

    @Override
    public void write(boolean last, ByteBuffer content, Callback callback)
    {
        Callback contextCallback = new Callback()
        {
            @Override
            public void succeeded()
            {
                _context.run(callback::succeeded, getRequest());
            }

            @Override
            public void failed(Throwable x)
            {
                _context.accept(callback::failed, x, getRequest());
            }

            @Override
            public InvocationType getInvocationType()
            {
                return Invocable.getInvocationType(callback);
            }
        };
        super.write(last, content, contextCallback);
    }
}
