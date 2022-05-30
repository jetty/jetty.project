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
import java.util.function.Supplier;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextRequest extends Request.WrapperProcessor implements Invocable, Supplier<Request.Processor>, Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextRequest.class);
    private final String _pathInContext;
    private final ContextHandler _contextHandler;
    private final ContextHandler.Context _context;
    private Response _response;
    private Callback _callback;

    protected ContextRequest(ContextHandler contextHandler, ContextHandler.Context context, Request wrapped, String pathInContext)
    {
        super(wrapped);
        _pathInContext = pathInContext;
        _contextHandler = contextHandler;
        _context = context;
    }

    @Override
    public Processor get()
    {
        try
        {
            return _contextHandler.getHandler().handle(this);
        }
        catch (Throwable t)
        {
            // Let's be less verbose with BadMessageExceptions & QuietExceptions
            if (!LOG.isDebugEnabled() && (t instanceof BadMessageException || t instanceof QuietException))
                LOG.warn("context bad message {}", t.getMessage());
            else
                LOG.warn("context handle failed {}", this, t);
        }
        return null;
    }

    @Override
    public void process(Request request, Response response, Callback callback) throws Exception
    {
        _response = response;
        _callback = callback;
        _context.run(this, this);
    }

    public Callback getCallback()
    {
        return _callback;
    }

    protected ContextResponse newContextResponse(Request request, Response response)
    {
        return new ContextResponse(_context, request, response);
    }

    @Override
    public void run()
    {
        try
        {
            super.process(this, newContextResponse(this, _response), _callback);
        }
        catch (Throwable t)
        {
            Response.writeError(this, _response, _callback, t);
        }
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
    public org.eclipse.jetty.server.Context getContext()
    {
        return _context;
    }

    public String getPathInContext()
    {
        return _pathInContext;
    }

    @Override
    public Object getAttribute(String name)
    {
        // return some hidden attributes for requestLog
        return switch (name)
        {
            case "o.e.j.s.h.ScopedRequest.contextPath" -> _context.getContextPath();
            case "o.e.j.s.h.ScopedRequest.pathInContext" -> _pathInContext;
            default -> super.getAttribute(name);
        };
    }
}
