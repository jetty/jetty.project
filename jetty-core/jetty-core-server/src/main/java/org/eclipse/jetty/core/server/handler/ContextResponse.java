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

import java.nio.ByteBuffer;

import org.eclipse.jetty.core.server.Context;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.util.Callback;

class ContextResponse extends Response.Wrapper
{
    private final Context _context;

    public ContextResponse(Context context, Response response)
    {
        super(response);
        _context = context;
    }

    @Override
    public void write(boolean last, Callback callback, ByteBuffer... content)
    {
        Callback contextCallback = new Callback()
        {
            @Override
            public void succeeded()
            {
                _context.run(callback::succeeded);
            }

            @Override
            public void failed(Throwable t)
            {
                _context.accept(callback::failed, t);
            }
        };
        super.write(last, contextCallback, content);
    }
}
