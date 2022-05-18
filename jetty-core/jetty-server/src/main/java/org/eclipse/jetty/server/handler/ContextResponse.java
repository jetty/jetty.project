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

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

public class ContextResponse extends Response.Wrapper
{
    private final ContextHandler.Context _context;

    public ContextResponse(ContextHandler.Context context, Request request, Response response)
    {
        super(request, response);
        _context = context;
    }

    @Override
    public void write(Content.Chunk chunk, Callback callback)
    {
        Callback contextCallback = new Callback()
        {
            @Override
            public void succeeded()
            {
                _context.run(callback::succeeded, getRequest());
            }

            @Override
            public void failed(Throwable t)
            {
                _context.accept(callback::failed, t, getRequest());
            }
        };
        super.write(chunk, contextCallback);
    }

    @Override
    public void write(boolean last, Callback callback, ByteBuffer... content)
    {
        Callback contextCallback = new Callback()
        {
            @Override
            public void succeeded()
            {
                _context.run(callback::succeeded, getRequest());
            }

            @Override
            public void failed(Throwable t)
            {
                _context.accept(callback::failed, t, getRequest());
            }
        };
        super.write(last, contextCallback, content);
    }
}
