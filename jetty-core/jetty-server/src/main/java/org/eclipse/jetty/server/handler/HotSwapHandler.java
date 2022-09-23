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

import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * A <code>HandlerContainer</code> that allows a hot swap of a wrapped handler.
 */
public class HotSwapHandler extends Handler.AbstractContainer implements Handler.Nested
{
    // TODO unit tests

    private volatile Handler _handler;

    /**
     *
     */
    public HotSwapHandler()
    {
    }

    /**
     * @return Returns the handlers.
     */
    public Handler getHandler()
    {
        return _handler;
    }

    /**
     * @return Returns the handlers.
     */
    @Override
    public List<Handler> getHandlers()
    {
        Handler next = _handler;
        return (next == null) ? Collections.emptyList() : Collections.singletonList(next);
    }

    /**
     * @param handler Set the {@link Handler} which should be wrapped.
     */
    public void setHandler(Handler handler)
    {
        // check state
        Server server1 = ((Nested)this).getServer();
        if (server1 != null && server1.isStarted() && handler != null &&
            server1.getInvocationType() != Invocable.combine(server1.getInvocationType(), handler.getInvocationType()))
            throw new IllegalArgumentException("Cannot change invocation type of started server");

        // Check for loops.
        if (handler == this || (handler instanceof Container container &&
            container.getDescendants().contains(this)))
            throw new IllegalStateException("setHandler loop");

        try
        {
            Server server = getServer();
            if (handler == _handler)
                return;

            Handler oldHandler = _handler;
            if (handler != null)
            {
                handler.setServer(server);
                addBean(handler, true);
                if (oldHandler != null && oldHandler.isStarted())
                    handler.start();
            }
            _handler = handler;
            if (oldHandler != null)
                removeBean(oldHandler);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        Handler next = _handler;
        return next == null ? null : next.handle(request);
    }

    @Override
    public InvocationType getInvocationType()
    {
        Handler next = getHandler();
        return next == null ? InvocationType.NON_BLOCKING : next.getInvocationType();
    }

    @Override
    public void destroy()
    {
        if (!isStopped())
            throw new IllegalStateException("!STOPPED");
        Handler child = getHandler();
        if (child != null)
        {
            setHandler((Handler)null);
            child.destroy();
        }
        super.destroy();
    }
}
