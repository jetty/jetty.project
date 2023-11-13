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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * A <code>HandlerWrapper</code> acts as a {@link Handler} but delegates the {@link Handler#handle handle} method and
 * {@link LifeCycle life cycle} events to a delegate. This is primarily used to implement the <i>Decorator</i> pattern.
 */
@ManagedObject("Handler wrapping another Handler")
public class HandlerWrapper extends AbstractHandlerContainer
{
    protected Handler _handler;

    /**
     *
     */
    public HandlerWrapper()
    {
    }

    /**
     * @return Returns the handlers.
     */
    @ManagedAttribute(value = "Wrapped Handler", readonly = true)
    public Handler getHandler()
    {
        return _handler;
    }

    /**
     * @return Returns the handlers.
     */
    @Override
    public Handler[] getHandlers()
    {
        if (_handler == null)
            return new Handler[0];
        return new Handler[]{_handler};
    }

    /**
     * @param handler Set the {@link Handler} which should be wrapped.
     */
    public void setHandler(Handler handler)
    {
        if (isStarted())
            throw new IllegalStateException(getState());

        // check for loops
        if (handler == this || (handler instanceof HandlerContainer &&
            Arrays.asList(((HandlerContainer)handler).getChildHandlers()).contains(this)))
            throw new IllegalStateException("setHandler loop");

        if (handler != null)
            handler.setServer(getServer());

        Handler old = _handler;
        _handler = handler;
        updateBean(old, _handler, true);
    }

    /**
     * Get the tail of a chain of {@link HandlerWrapper}s.
     * @return The last {@link HandlerWrapper} in a chain of {@link HandlerWrapper}s
     */
    public HandlerWrapper getTail()
    {
        HandlerWrapper tail = this;
        while (tail.getHandler() instanceof HandlerWrapper handlerWrapper)
            tail = handlerWrapper;
        return tail;
    }

    /**
     * Replace the current handler with another HandlerWrapper
     * linked to the current handler.
     * <p>
     * This is equivalent to:
     * <pre>
     *   wrapper.setHandler(getHandler());
     *   setHandler(wrapper);
     * </pre>
     *
     * @param wrapper the wrapper to insert
     */
    public void insertHandler(HandlerWrapper wrapper)
    {
        if (wrapper == null)
            throw new IllegalArgumentException();

        HandlerWrapper tail = wrapper.getTail();
        Handler next = getHandler();
        setHandler(wrapper);
        tail.setHandler(next);
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        Handler handler = _handler;
        if (handler != null)
            handler.handle(target, baseRequest, request, response);
    }

    @Override
    protected void expandChildren(List<Handler> list, Class<?> byClass)
    {
        expandHandler(_handler, list, byClass);
    }

    @Override
    public void destroy()
    {
        if (!isStopped())
            throw new IllegalStateException("!STOPPED");
        Handler child = getHandler();
        if (child != null)
        {
            setHandler(null);
            child.destroy();
        }
        super.destroy();
    }

    /**
     * <p>Make a {@link org.eclipse.jetty.server.Handler.Container} the parent of a {@link org.eclipse.jetty.server.Handler}</p>
     * @param parent The {@link org.eclipse.jetty.server.Handler.Container} that will be the parent
     * @param handler The {@link org.eclipse.jetty.server.Handler} that will be the child
     */
    public static void setAsParent(org.eclipse.jetty.server.Handler.Container parent, org.eclipse.jetty.server.Handler handler)
    {
        if (parent instanceof org.eclipse.jetty.server.Handler.Collection collection)
            collection.addHandler(handler);
        else if (parent instanceof org.eclipse.jetty.server.Handler.Singleton wrapper)
            wrapper.setHandler(handler);
        else if (parent != null)
            throw new IllegalArgumentException("Unknown parent type: " + parent);
    }
}
