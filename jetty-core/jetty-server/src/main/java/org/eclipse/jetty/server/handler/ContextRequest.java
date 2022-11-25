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

import java.util.function.Predicate;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextRequest extends Request.WrapperProcessor implements Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextRequest.class);
    private final ContextHandler _contextHandler;
    private final ContextHandler.ScopedContext _context;

    protected ContextRequest(ContextHandler contextHandler, ContextHandler.ScopedContext context, Request wrapped)
    {
        super(wrapped);
        _contextHandler = contextHandler;
        _context = context;
    }

    @Override
    public void process(Request request, Response response, Callback callback) throws Exception
    {
        assert this.getWrapped() == request;
        ContextResponse contextResponse = newContextResponse(this, response);
        ClassLoader lastLoader = _contextHandler.enterScope(this);
        try
        {
            super.process(this, contextResponse, callback);
        }
        catch (Throwable t)
        {
            Response.writeError(this, contextResponse, callback, t);
        }
        finally
        {
            // We exit scope here, even though process is asynchronous, as we have wrapped
            // all our callbacks to re-enter the scope.
            _contextHandler.exitScope(this, request.getContext(), lastLoader);
        }
    }

    protected ContextResponse newContextResponse(Request request, Response response)
    {
        return new ContextResponse(_context, request, response);
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        super.demand(() -> _context.run(demandCallback, this));
    }

    @Override
    public boolean addErrorListener(Predicate<Throwable> onError)
    {
        return super.addErrorListener(t ->
        {
            // TODO: implement the line below
            // return _context.apply(onError::test, t, ContextRequest.this);
            _context.accept(onError::test, t, ContextRequest.this);
            return true;
        });
    }

    @Override
    public ContextHandler.ScopedContext getContext()
    {
        return _context;
    }

    @Override
    public Object getAttribute(String name)
    {
        // return some hidden attributes for requestLog
        return switch (name)
        {
            case "o.e.j.s.h.ScopedRequest.contextPath" -> _context.getContextPath();
            case "o.e.j.s.h.ScopedRequest.pathInContext" -> Request.getPathInContext(this);
            default -> super.getAttribute(name);
        };
    }
}
