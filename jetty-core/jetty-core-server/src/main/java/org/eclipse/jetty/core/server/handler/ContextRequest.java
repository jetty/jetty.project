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
import java.util.function.Supplier;

import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextRequest extends Request.ProcessingWrapper implements Invocable, Supplier<Request.Processor>, Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextRequest.class);
    private final String _pathInContext;
    private final ContextHandler _contextHandler;
    private Response _response;
    private Callback _callback;

    protected ContextRequest(ContextHandler contextHandler, Request wrapped, String pathInContext)
    {
        super(wrapped);
        _pathInContext = pathInContext;
        _contextHandler = contextHandler;
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
        _contextHandler.getContext().run(this);
    }

    @Override
    public void run()
    {
        try
        {
            getProcessor().process(this, new ContextResponse(this, _response), _callback);
        }
        catch (Throwable t)
        {
            _response.writeError(this, t, _callback);
        }
    }

    @Override
    public void execute(Runnable task)
    {
        super.execute(() -> _contextHandler.getContext().run(task));
    }

    @Override
    public void demandContent(Runnable onContentAvailable)
    {
        super.demandContent(() -> _contextHandler.getContext().run(onContentAvailable));
    }

    @Override
    public void addErrorListener(Consumer<Throwable> onError)
    {
        super.addErrorListener(t -> _contextHandler.getContext().accept(onError, t));
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
                _contextHandler.getContext().accept(onComplete::failed, t);
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
        return switch (name)
        {
            case "o.e.j.s.h.ScopedRequest.contextPath" -> _contextHandler.getContext().getContextPath();
            case "o.e.j.s.h.ScopedRequest.pathInContext" -> _pathInContext;
            default -> super.getAttribute(name);
        };
    }
}
