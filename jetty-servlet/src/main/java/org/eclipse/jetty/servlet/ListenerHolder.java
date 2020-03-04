//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlet;

import java.util.EventListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * ListenerHolder
 *
 * Specialization of BaseHolder for servlet listeners. This
 * allows us to record where the listener originated - web.xml,
 * annotation, api etc.
 */
public class ListenerHolder extends BaseHolder<EventListener>
{
    private EventListener _listener;

    public ListenerHolder()
    {
        this(Source.EMBEDDED);
    }

    public ListenerHolder(Source source)
    {
        super(source);
    }

    public ListenerHolder(Class<? extends EventListener> listenerClass)
    {
        super(Source.EMBEDDED);
        setHeldClass(listenerClass);
    }

    public EventListener getListener()
    {
        return _listener;
    }

    /**
     * Set an explicit instance. In this case,
     * just like ServletHolder and FilterHolder,
     * the listener will not be introspected for
     * annotations like Resource etc.
     * @param listener The listener instance
     */
    public void setListener(EventListener listener)
    {
        setInstance(listener);
    }

    @Override
    public void doStart() throws Exception
    {
        super.doStart();
        if (!java.util.EventListener.class.isAssignableFrom(getHeldClass()))
        {
            String msg = getHeldClass() + " is not a java.util.EventListener";
            super.stop();
            throw new IllegalStateException(msg);
        }

        ContextHandler contextHandler = ContextHandler.getCurrentContext().getContextHandler();
        if (contextHandler != null)
        {
            _listener = getInstance();
            if (_listener == null)
            {
                //create an instance of the listener and decorate it
                try
                {
                    _listener = createInstance();
                }
                catch (ServletException ex)
                {
                    Throwable cause = ex.getRootCause();
                    if (cause instanceof InstantiationException)
                        throw (InstantiationException)cause;
                    if (cause instanceof IllegalAccessException)
                        throw (IllegalAccessException)cause;
                    throw ex;
                }
            }
            contextHandler.addEventListener(_listener);
        }
    }

    @Override
    protected synchronized EventListener createInstance() throws Exception
    {

        EventListener listener = super.createInstance();
        if (listener == null)
        {
            ServletContext ctx = getServletContext();
            if (ctx != null)
                listener = ctx.createListener(getHeldClass());
        }
        return listener;
    }

    @Override
    public void doStop() throws Exception
    {
        super.doStop();
        if (_listener != null)
        {
            try
            {
                ContextHandler contextHandler = ContextHandler.getCurrentContext().getContextHandler();
                if (contextHandler != null)
                    contextHandler.removeEventListener(_listener);
                getServletHandler().destroyListener(_listener);
            }
            finally
            {
                _listener = null;
            }
        }
    }

    @Override
    public String toString()
    {
        return super.toString() + ": " + getClassName();
    }
}
