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
import java.util.function.Function;

import org.eclipse.jetty.core.server.Context;
import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextRequest extends Request.WrapperProcessor implements Invocable, Function<Request, Request.Processor>, Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextRequest.class);
    private final String _pathInContext;
    private final ContextHandler _contextHandler;
    private final ContextHandler.ScopedContext _context;
    private Response _response;
    private Callback _callback;

    protected ContextRequest(ContextHandler contextHandler, ContextHandler.ScopedContext context, Request wrapped, String pathInContext)
    {
        super(wrapped);
        _pathInContext = pathInContext;
        _contextHandler = contextHandler;
        _context = context;
    }

    @Override
    public Processor apply(Request request)
    {
        try
        {
            return _contextHandler.getHandler().handle(request);
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
        _context.run(this);
    }

    @Override
    public void run()
    {
        try
        {
            super.process(this, new ContextResponse(_context, _response), _callback);
        }
        catch (Throwable t)
        {
            Response.writeError(this, _response, _callback, t);
        }
    }

    @Override
    public void demandContent(Runnable onContentAvailable)
    {
        super.demandContent(() -> _context.run(onContentAvailable));
    }

    @Override
    public void addErrorListener(Consumer<Throwable> onError)
    {
        super.addErrorListener(t -> _context.accept(onError, t));
    }

    @Override
    public void addCompletionListener(Callback onComplete)
    {
        super.addCompletionListener(new Callback()
        {
            @Override
            public void succeeded()
            {
                _context.run(onComplete::succeeded);
            }

            @Override
            public void failed(Throwable t)
            {
                _context.accept(onComplete::failed, t);
            }
        });
    }

    @Override
    public Context getContext()
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
