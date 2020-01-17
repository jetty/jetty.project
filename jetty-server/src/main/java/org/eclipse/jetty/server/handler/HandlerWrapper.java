//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Request;
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
            throw new IllegalStateException(STARTED);

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

        HandlerWrapper tail = wrapper;
        while (tail.getHandler() instanceof HandlerWrapper)
        {
            tail = (HandlerWrapper)tail.getHandler();
        }
        if (tail.getHandler() != null)
            throw new IllegalArgumentException("bad tail of inserted wrapper chain");

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
}
