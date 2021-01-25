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

import java.io.IOException;
import java.util.function.BiFunction;
import javax.servlet.ServletContext;
import javax.servlet.UnavailableException;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * AbstractHolder
 *
 * Base class for all servlet-related classes that may be lazily instantiated  (eg servlet, filter,
 * listener), and/or require metadata to be held regarding their origin
 * (web.xml, annotation, programmatic api etc).
 *
 * @param <T> the type of holder
 */
public abstract class BaseHolder<T> extends AbstractLifeCycle implements Dumpable
{
    private static final Logger LOG = Log.getLogger(BaseHolder.class);

    private final Source _source;
    private Class<? extends T> _class;
    private String _className;
    private T _instance;
    private ServletHandler _servletHandler;

    protected BaseHolder(Source source)
    {
        _source = source;
    }

    public Source getSource()
    {
        return _source;
    }

    /**
     * Do any setup necessary after starting
     *
     * @throws Exception if unable to initialize
     */
    public void initialize()
        throws Exception
    {
        if (!isStarted())
            throw new IllegalStateException("Not started: " + this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void doStart()
        throws Exception
    {
        //if no class already loaded and no classname, make permanently unavailable
        if (_class == null && (_className == null || _className.equals("")))
            throw new UnavailableException("No class in holder " + toString());

        //try to load class
        if (_class == null)
        {
            try
            {
                _class = Loader.loadClass(_className);
                if (LOG.isDebugEnabled())
                    LOG.debug("Holding {} from {}", _class, _class.getClassLoader());
            }
            catch (Exception e)
            {
                LOG.warn(e);
                throw new UnavailableException("Class loading error for holder " + toString());
            }
        }
    }

    @Override
    public void doStop()
        throws Exception
    {
        if (_instance == null)
            _class = null;
    }

    @ManagedAttribute(value = "Class Name", readonly = true)
    public String getClassName()
    {
        return _className;
    }

    public Class<? extends T> getHeldClass()
    {
        return _class;
    }

    /**
     * @return Returns the servletHandler.
     */
    public ServletHandler getServletHandler()
    {
        return _servletHandler;
    }

    /**
     * @param servletHandler The {@link ServletHandler} that will handle requests dispatched to this servlet.
     */
    public void setServletHandler(ServletHandler servletHandler)
    {
        _servletHandler = servletHandler;
    }

    /**
     * @param className The className to set.
     */
    public void setClassName(String className)
    {
        _className = className;
        _class = null;
    }

    /**
     * @param held The class to hold
     */
    public void setHeldClass(Class<? extends T> held)
    {
        _class = held;
        if (held != null)
        {
            _className = held.getName();
        }
    }

    protected void illegalStateIfContextStarted()
    {
        if (_servletHandler != null)
        {
            ServletContext context = _servletHandler.getServletContext();
            if ((context instanceof ContextHandler.Context) && ((ContextHandler.Context)context).getContextHandler().isStarted())
                throw new IllegalStateException("Started");
        }
    }

    protected synchronized void setInstance(T instance)
    {
        _instance = instance;
        if (instance == null)
            setHeldClass(null);
        else
            setHeldClass((Class<T>)instance.getClass());
    }

    protected synchronized T getInstance()
    {
        return _instance;
    }

    /**
     * @return True if this holder was created for a specific instance.
     */
    public synchronized boolean isInstance()
    {
        return _instance != null;
    }

    /**
     * Wrap component using component specific Wrapper Function beans.
     *
     * @param component the component to optionally wrap
     * @param wrapperFunctionType the bean class type to look for in the {@link ServletContextHandler}
     * @param function the BiFunction to execute for each {@code wrapperFunctionType} Bean found (passing in the component and component type)
     * @param <W> the "wrapper function" implementation. (eg: {@code ServletHolder.WrapperFunction} or {@code FilterHolder.WrapperFunction}, etc)
     * @return the component that has passed through all Wrapper Function beans found.
     */
    protected <W> T wrap(final T component, final Class<W> wrapperFunctionType, final BiFunction<W, T, T> function)
    {
        T ret = component;
        ServletContextHandler contextHandler = getServletHandler().getServletContextHandler();
        if (contextHandler == null)
        {
            ContextHandler.Context context = ContextHandler.getCurrentContext();
            contextHandler = (ServletContextHandler)(context == null ? null : context.getContextHandler());
        }

        if (contextHandler != null)
        {
            for (W wrapperFunction : contextHandler.getBeans(wrapperFunctionType))
            {
                ret = function.apply(wrapperFunction, ret);
            }
        }
        return ret;
    }

    protected T unwrap(final T component)
    {
        T ret = component;

        while (ret instanceof Wrapped)
        {
            // noinspection unchecked,rawtypes
            ret = (T)((Wrapped)ret).getWrapped();
        }
        return ret;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObject(out, this);
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    interface Wrapped<C>
    {
        C getWrapped();
    }
}
