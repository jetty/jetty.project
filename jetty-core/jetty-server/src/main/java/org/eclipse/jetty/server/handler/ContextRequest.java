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
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextRequest extends Request.Wrapper implements Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextRequest.class);
    private final ContextHandler.ScopedContext _context;

    protected ContextRequest(ContextHandler.ScopedContext context, Request request)
    {
        super(request);
        _context = context;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        // inner class used instead of lambda for clarity in stack traces
        super.demand(new OnContextDemand(demandCallback));
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

    private class OnContextDemand implements Runnable
    {
        private final Runnable _demandCallback;

        public OnContextDemand(Runnable demandCallback)
        {
            _demandCallback = demandCallback;
        }

        @Override
        public void run()
        {
            _context.run(_demandCallback, ContextRequest.this);
        }
    }
}
