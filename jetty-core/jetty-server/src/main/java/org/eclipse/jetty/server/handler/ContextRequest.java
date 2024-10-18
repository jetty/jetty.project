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

import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.eclipse.jetty.server.Context;
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
    public void addIdleTimeoutListener(Predicate<TimeoutException> onIdleTimeout)
    {
        super.addIdleTimeoutListener(t -> _context.test(onIdleTimeout, t, ContextRequest.this));
    }

    @Override
    public void addFailureListener(Consumer<Throwable> onFailure)
    {
        super.addFailureListener(t -> _context.accept(onFailure, t, ContextRequest.this));
    }

    @Override
    public Context getContext()
    {
        return _context;
    }

    private class OnContextDemand implements Invocable.Task
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

        @Override
        public InvocationType getInvocationType()
        {
            return Invocable.getInvocationType(_demandCallback);
        }
    }
}
