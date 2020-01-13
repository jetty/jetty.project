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
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * A collection of handlers.
 * <p>
 * The default implementations  calls all handlers in list order,
 * regardless of the response status or exceptions. Derived implementation
 * may alter the order or the conditions of calling the contained
 * handlers.
 */
@ManagedObject("Handler of multiple handlers")
public class HandlerCollection extends AbstractHandlerContainer
{
    private final boolean _mutableWhenRunning;
    protected final AtomicReference<Handlers> _handlers = new AtomicReference<>();

    public HandlerCollection()
    {
        this(false);
    }

    public HandlerCollection(Handler... handlers)
    {
        this(false, handlers);
    }

    public HandlerCollection(boolean mutableWhenRunning, Handler... handlers)
    {
        _mutableWhenRunning = mutableWhenRunning;
        if (handlers.length > 0)
            setHandlers(handlers);
    }

    /**
     * @return the array of handlers.
     */
    @Override
    @ManagedAttribute(value = "Wrapped handlers", readonly = true)
    public Handler[] getHandlers()
    {
        Handlers handlers = _handlers.get();
        return handlers == null ? null : handlers._handlers;
    }

    /**
     * @param handlers the array of handlers to set.
     */
    public void setHandlers(Handler[] handlers)
    {
        if (!_mutableWhenRunning && isStarted())
            throw new IllegalStateException(STARTED);

        while (true)
        {
            if (updateHandlers(_handlers.get(), newHandlers(handlers)))
                break;
        }
    }

    protected Handlers newHandlers(Handler[] handlers)
    {
        if (handlers == null || handlers.length == 0)
            return null;
        return new Handlers(handlers);
    }

    protected boolean updateHandlers(Handlers old, Handlers handlers)
    {
        if (handlers != null)
        {
            // check for loops
            for (Handler handler : handlers._handlers)
            {
                if (handler == this || (handler instanceof HandlerContainer &&
                    Arrays.asList(((HandlerContainer)handler).getChildHandlers()).contains(this)))
                    throw new IllegalStateException("setHandler loop");
            }

            // Set server
            for (Handler handler : handlers._handlers)
            {
                if (handler.getServer() != getServer())
                    handler.setServer(getServer());
            }
        }

        if (_handlers.compareAndSet(old, handlers))
        {
            Handler[] oldBeans = old == null ? null : old._handlers;
            Handler[] newBeans = handlers == null ? null : handlers._handlers;
            updateBeans(oldBeans, newBeans);
            return true;
        }
        return false;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        if (isStarted())
        {
            Handlers handlers = _handlers.get();
            if (handlers == null)
                return;

            MultiException mex = null;
            for (Handler handler : handlers._handlers)
            {
                try
                {
                    handler.handle(target, baseRequest, request, response);
                }
                catch (IOException | RuntimeException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    if (mex == null)
                        mex = new MultiException();
                    mex.add(e);
                }
            }
            if (mex != null)
            {
                if (mex.size() == 1)
                    throw new ServletException(mex.getThrowable(0));
                else
                    throw new ServletException(mex);
            }
        }
    }

    /**
     * Adds a handler.
     * This implementation adds the passed handler to the end of the existing collection of handlers.
     * If the handler is already added, it is removed and readded
     */
    public void addHandler(Handler handler)
    {
        while (true)
        {
            Handlers old = _handlers.get();
            Handlers handlers = newHandlers(ArrayUtil.addToArray(old == null ? null : ArrayUtil.removeFromArray(old._handlers, handler), handler, Handler.class));
            if (updateHandlers(old, handlers))
                break;
        }
    }

    /**
     * Prepends a handler.
     * This implementation adds the passed handler to the start of the existing collection of handlers.
     */
    public void prependHandler(Handler handler)
    {
        while (true)
        {
            Handlers old = _handlers.get();
            Handlers handlers = newHandlers(ArrayUtil.prependToArray(handler, old == null ? null : old._handlers, Handler.class));
            if (updateHandlers(old, handlers))
                break;
        }
    }

    public void removeHandler(Handler handler)
    {
        while (true)
        {
            Handlers old = _handlers.get();
            if (old == null || old._handlers.length == 0)
                break;
            Handlers handlers = newHandlers(ArrayUtil.removeFromArray(old._handlers, handler));
            if (updateHandlers(old, handlers))
                break;
        }
    }

    @Override
    protected void expandChildren(List<Handler> list, Class<?> byClass)
    {
        Handler[] handlers = getHandlers();
        if (handlers != null)
            for (Handler h : handlers)
            {
                expandHandler(h, list, byClass);
            }
    }

    @Override
    public void destroy()
    {
        if (!isStopped())
            throw new IllegalStateException("!STOPPED");
        Handler[] children = getChildHandlers();
        setHandlers(null);
        for (Handler child : children)
        {
            child.destroy();
        }
        super.destroy();
    }

    protected static class Handlers
    {
        private final Handler[] _handlers;

        protected Handlers(Handler[] handlers)
        {
            this._handlers = handlers;
        }

        public Handler[] getHandlers()
        {
            return _handlers;
        }
    }
}
