//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.servlet;

import java.util.EventListener;
import javax.servlet.ServletException;

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

        ContextHandler contextHandler = null;
        if (getServletHandler() != null)
            contextHandler = getServletHandler().getServletContextHandler();
        if (contextHandler == null && ContextHandler.getCurrentContext() != null)
            contextHandler = ContextHandler.getCurrentContext().getContextHandler();
        if (contextHandler == null)
            throw new IllegalStateException("No Context");

        _listener = getInstance();
        if (_listener == null)
        {
            //create an instance of the listener and decorate it
            try
            {
                _listener = contextHandler.getServletContext().createListener(getHeldClass());
            }
            catch (ServletException ex)
            {
                throw ex;
            }

            _listener = wrap(_listener, WrapFunction.class, WrapFunction::wrapEventListener);
        }
        contextHandler.addEventListener(_listener);
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
                getServletHandler().destroyListener(unwrap(_listener));
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
        return String.format("%s@%x{src=%s}", getClassName(), hashCode(), getSource());
    }

    /**
     * Experimental Wrapper mechanism for Servlet EventListeners.
     * <p>
     * Beans in {@code ServletContextHandler} or {@code WebAppContext} that implement this interface
     * will be called to optionally wrap any newly created Servlet EventListeners before
     * they are used for the first time.
     * </p>
     */
    public interface WrapFunction
    {
        /**
         * Optionally wrap the Servlet EventListener.
         *
         * @param listener the Servlet EventListener being passed in.
         * @return the Servlet EventListener (extend from {@link ListenerHolder.Wrapper}
         * if you do wrap the Servlet EventListener)
         */
        EventListener wrapEventListener(EventListener listener);
    }

    public static class Wrapper implements EventListener, Wrapped<EventListener>
    {
        final EventListener _listener;

        public Wrapper(EventListener listener)
        {
            _listener = listener;
        }

        @Override
        public EventListener getWrapped()
        {
            return _listener;
        }

        @Override
        public String toString()
        {
            return String.format("%s:%s", this.getClass().getSimpleName(), _listener.toString());
        }
    }
}
