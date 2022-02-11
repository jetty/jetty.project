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

import java.util.function.Consumer;

import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextRequest extends Request.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextRequest.class);
    private final String _pathInContext;
    private final ContextHandler _contextHandler;
    private volatile Response _response;

    protected ContextRequest(ContextHandler contextHandler, Request wrapped, String pathInContext)
    {
        super(wrapped);
        _pathInContext = pathInContext;
        _contextHandler = contextHandler;
    }

    @Override
    public void addErrorListener(Consumer<Throwable> onError)
    {
        super.addErrorListener(t -> _contextHandler.getContext().consumeThrowableInContext(onError, t));
    }

    @Override
    public void addCompletionListener(Callback onComplete)
    {
        super.addCompletionListener(new Callback()
        {
            @Override
            public void succeeded()
            {
                _contextHandler.getContext().run(onComplete::succeeded);
            }

            @Override
            public void failed(Throwable t)
            {
                _contextHandler.getContext().consumeThrowableInContext(onComplete::failed, t);
            }
        });
    }

    public ContextHandler.Context getContext()
    {
        return _contextHandler.getContext();
    }

    public String getPath()
    {
        return _pathInContext;
    }

    @Override
    public Object getAttribute(String name)
    {
        // return some hidden attributes for requestLog
        switch (name)
        {
            case "o.e.j.s.h.ScopedRequest.contextPath":
                return _contextHandler.getContext().getContextPath();
            case "o.e.j.s.h.ScopedRequest.pathInContext":
                return _pathInContext;
            default:
                return super.getAttribute(name);
        }
    }
}
