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

package org.eclipse.jetty.server.handler;

import java.util.function.Consumer;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextRequest extends Request.Wrapper implements Invocable.Task
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
    public Response accept()
    {
        Response response = super.accept();
        if (response == null)
            return null;
        _response = new ContextResponse(this, response);
        return _response;
    }

    @Override
    public Response getResponse()
    {
        return _response;
    }

    @Override
    public void run() throws Exception
    {
        try
        {
            _contextHandler.getHandler().handle(this);
        }
        catch (Throwable t)
        {
            // Let's be less verbose with BadMessageExceptions & QuietExceptions
            if (!LOG.isDebugEnabled() && (t instanceof BadMessageException || t instanceof QuietException))
                LOG.warn("context bad message {}", t.getMessage());
            else
                LOG.warn("context handle failed {}", this, t);

            // Only handle exception if request was accepted
            if (isAccepted())
            {
                Response response = _response;
                if (!response.isCommitted())
                {
                    response.writeError(t, response.getCallback());
                    return;
                }
                throw t;
            }
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
