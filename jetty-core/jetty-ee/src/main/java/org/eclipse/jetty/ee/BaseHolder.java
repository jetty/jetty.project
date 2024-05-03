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

package org.eclipse.jetty.ee;

import java.io.IOException;
import java.util.function.BiFunction;

import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all servlet-related classes that may be lazily instantiated  (eg servlet, filter,
 * listener), and/or require metadata to be held regarding their origin
 * (web.xml, annotation, programmatic api etc.).
 *
 * @param <T> the type of holder
 */
public abstract class BaseHolder<T> extends AbstractLifeCycle implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(BaseHolder.class);

    private final AutoLock _lock = new AutoLock();
    private final Source _source;
    private Class<? extends T> _class;
    private String _className;
    private T _instance;
    private ContextHandler _contextHandler;

    protected BaseHolder(Source source)
    {
        _source = source;
    }

    public Source getSource()
    {
        return _source;
    }

    protected AutoLock lock()
    {
        return _lock.lock();
    }

    protected boolean lockIsHeldByCurrentThread()
    {
        return _lock.isHeldByCurrentThread();
    }

    protected abstract Exception newUnavailableException(String message);

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
        if (_class == null && (_className == null || _className.isEmpty()))
            throw newUnavailableException("No class in holder " + this);

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
                LOG.warn("Unable to load class {}", _className, e);
                throw newUnavailableException("Class loading error for holder " + this);
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
     * @return Returns the contextHandler.
     */
    public ContextHandler getContextHandler()
    {
        if (_contextHandler == null)
        {
            Context context = ContextHandler.getCurrentContext();
            if (context instanceof ContextHandler.ScopedContext scopedContext)
                _contextHandler = scopedContext.getContextHandler();
        }

        return _contextHandler;
    }

    /**
     * @param contextHandler The {@link ContextHandler}
     */
    public void setContextHandler(ContextHandler contextHandler)
    {
        _contextHandler = contextHandler;
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
        ContextHandler contextHandler = getContextHandler();
        if (contextHandler != null && contextHandler.isStarted())
            throw new IllegalStateException("Started");
    }

    protected void setInstance(T instance)
    {
        try (AutoLock ignored = lock())
        {
            _instance = instance;
            if (instance == null)
            {
                setHeldClass(null);
            }
            else
            {
                @SuppressWarnings("unchecked")
                Class<? extends T> clazz = (Class<T>)instance.getClass();
                setHeldClass(clazz);
            }
        }
    }

    protected T getInstance()
    {
        try (AutoLock ignored = lock())
        {
            return _instance;
        }
    }

    protected T createInstance() throws Exception
    {
        ContextHandler contextHandler = getContextHandler();
        if (contextHandler == null)
            return getHeldClass().getDeclaredConstructor().newInstance();

        Context context = contextHandler.getContext();
        DecoratedObjectFactory.associateInfo(this);
        try
        {
            T t = getHeldClass().getDeclaredConstructor().newInstance();
            context.decorate(t);
            return t;
        }
        finally
        {
            //unset the thread local
            DecoratedObjectFactory.disassociateInfo();
        }
    }

    /**
     * @return True if this holder was created for a specific instance.
     */
    public boolean isInstance()
    {
        try (AutoLock ignored = lock())
        {
            return _instance != null;
        }
    }

    /**
     * Wrap component using component specific Wrapper Function beans.
     *
     * @param component the component to optionally wrap
     * @param wrapperFunctionType the bean class type to look for in the {@link ContextHandler}
     * @param function the BiFunction to execute for each {@code wrapperFunctionType} Bean found (passing in the component and component type)
     * @param <W> the "wrapper function" implementation. (eg: {@code ServletHolder.WrapperFunction} or {@code FilterHolder.WrapperFunction}, etc)
     * @return the component that has passed through all Wrapper Function beans found.
     */
    protected <W> T wrap(final T component, final Class<W> wrapperFunctionType, final BiFunction<W, T, T> function)
    {
        T ret = component;
        ContextHandler contextHandler = getContextHandler();
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

    public interface Wrapped<C>
    {
        C getWrapped();
    }
}
