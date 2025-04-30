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

package org.eclipse.jetty.ee11.servlet;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.eclipse.jetty.ee.Source;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all servlet-related classes that may be lazily instantiated  (eg servlet, filter,
 * listener), and/or require metadata to be held regarding their origin
 * (web.xml, annotation, programmatic api etc).
 *
 * @param <T> the type of holder
 */
public abstract class Holder<T> extends AbstractLifeCycle implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(Holder.class);

    private final AutoLock _lock = new AutoLock();
    private final Source _source;
    private Class<? extends T> _class;
    private String _className;
    private T _instance;
    private ContextHandler _contextHandler;

    protected Holder(Source source)
    {
        _source = source;
    }

    public Source getSource()
    {
        return _source;
    }

    AutoLock lock()
    {
        return _lock.lock();
    }

    boolean lockIsHeldByCurrentThread()
    {
        return _lock.isHeldByCurrentThread();
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
        if (_class == null && (_className == null || _className.isEmpty()))
            throw new IllegalStateException("No class in holder " + this);

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
                throw new IllegalStateException("Class loading error for holder " + this);
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
     * @param contextHandler The {@link ServletHandler} that will handle requests dispatched to this servlet.
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
        if (_contextHandler != null && _contextHandler.isStarted())
            throw new IllegalStateException("Started");
    }

    protected void setInstance(T instance)
    {
        try (AutoLock ignored = lock())
        {
            _instance = instance;
            if (instance == null)
                setHeldClass(null);
            else
            {
                @SuppressWarnings("unchecked")
                Class<? extends T> clazz = (Class<? extends T>)instance.getClass();
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
        try (AutoLock ignored = lock())
        {
            try
            {
                //set a thread local
                DecoratedObjectFactory.associateInfo(this);
                T t = getHeldClass().getDeclaredConstructor().newInstance();
                ContextHandler contextHandler = getContextHandler();
                return contextHandler == null ? t : contextHandler.getContext().decorate(t);
            }
            finally
            {
                //unset the thread local
                DecoratedObjectFactory.disassociateInfo();
            }
        }
    }

    public ContextHandler getContextHandler()
    {
        ContextHandler contextHandler = _contextHandler;
        return contextHandler == null ? ContextHandler.getCurrentContextHandler() : contextHandler;
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
     * @param wrapperFunctionType the bean class type to look for in the {@link ServletContextHandler}
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

    interface Wrapped<C>
    {
        C getWrapped();
    }

    /**
     * Specialization of AbstractHolder for servlet-related classes that
     * have init-params etc
     *
     * @param <T> the type of holder
     */
    @ManagedObject("NamedHolder - a container for servlets and the like")
    public abstract static class NamedHolder<T> extends Holder<T>
    {
        private static final Logger LOG = LoggerFactory.getLogger(NamedHolder.class);

        private final Map<String, String> _initParams = new HashMap<>(3);
        private String _displayName;
        private boolean _asyncSupported;
        private String _name;

        protected NamedHolder(Source source)
        {
            super(source);
            switch (getSource().getOrigin())
            {
                case JAKARTA_API:
                case DESCRIPTOR:
                case ANNOTATION:
                    _asyncSupported = false;
                    break;
                default:
                    _asyncSupported = true;
            }
        }

        @ManagedAttribute(value = "Display Name", readonly = true)
        public String getDisplayName()
        {
            return _displayName;
        }

        public String getInitParameter(String param)
        {
            return _initParams.get(param);
        }

        public Enumeration<String> getInitParameterNames()
        {
            return Collections.enumeration(_initParams.keySet());
        }

        @ManagedAttribute(value = "Initial Parameters", readonly = true)
        public Map<String, String> getInitParameters()
        {
            return _initParams;
        }

        @ManagedAttribute(value = "Name", readonly = true)
        public String getName()
        {
            return _name;
        }

        @Override
        protected void setInstance(T instance)
        {
            try (AutoLock ignored = lock())
            {
                super.setInstance(instance);
                if (getName() == null)
                    setName(String.format("%s@%x", instance.getClass().getName(), instance.hashCode()));
            }
        }

        public void destroyInstance(Object instance)
            throws Exception
        {
        }

        /**
         * @param className The className to set.
         */
        @Override
        public void setClassName(String className)
        {
            super.setClassName(className);
            if (_name == null)
                _name = className + "-" + Integer.toHexString(this.hashCode());
        }

        /**
         * @param held The class to hold
         */
        @Override
        public void setHeldClass(Class<? extends T> held)
        {
            super.setHeldClass(held);
            if (held != null)
            {
                if (_name == null)
                    _name = held.getName() + "-" + Integer.toHexString(this.hashCode());
            }
        }

        public void setDisplayName(String name)
        {
            _displayName = name;
        }

        public void setInitParameter(String param, String value)
        {
            _initParams.put(param, value);
        }

        public void setInitParameters(Map<String, String> map)
        {
            _initParams.clear();
            _initParams.putAll(map);
        }

        /**
         * The name is a primary key for the held object.
         * Ensure that the name is set BEFORE adding a NamedHolder
         * (eg ServletHolder or FilterHolder) to a ServletHandler.
         *
         * @param name The name to set.
         */
        public void setName(String name)
        {
            _name = name;
        }

        public void setAsyncSupported(boolean suspendable)
        {
            _asyncSupported = suspendable;
        }

        public boolean isAsyncSupported()
        {
            return _asyncSupported;
        }

        @Override
        public String dump()
        {
            return super.dump();
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x==%s", _name, hashCode(), getClassName());
        }

        protected class HolderConfig
        {
            public String getInitParameter(String param)
            {
                return NamedHolder.this.getInitParameter(param);
            }

            public Enumeration<String> getInitParameterNames()
            {
                return NamedHolder.this.getInitParameterNames();
            }
        }

        protected class HolderRegistration
        {
            public void setAsyncSupported(boolean isAsyncSupported)
            {
                illegalStateIfContextStarted();
                NamedHolder.this.setAsyncSupported(isAsyncSupported);
            }

            public void setDescription(String description)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} is {}", this, description);
            }

            public String getClassName()
            {
                return NamedHolder.this.getClassName();
            }

            public String getInitParameter(String name)
            {
                return NamedHolder.this.getInitParameter(name);
            }

            public Map<String, String> getInitParameters()
            {
                return NamedHolder.this.getInitParameters();
            }

            public String getName()
            {
                return NamedHolder.this.getName();
            }

            public boolean setInitParameter(String name, String value)
            {
                illegalStateIfContextStarted();
                if (name == null)
                {
                    throw new IllegalArgumentException("init parameter name required");
                }
                if (value == null)
                {
                    throw new IllegalArgumentException("non-null value required for init parameter " + name);
                }
                if (NamedHolder.this.getInitParameter(name) != null)
                    return false;
                NamedHolder.this.setInitParameter(name, value);
                return true;
            }

            public Set<String> setInitParameters(Map<String, String> initParameters)
            {
                illegalStateIfContextStarted();
                Set<String> clash = null;
                for (Map.Entry<String, String> entry : initParameters.entrySet())
                {
                    if (entry.getKey() == null)
                    {
                        throw new IllegalArgumentException("init parameter name required");
                    }
                    if (entry.getValue() == null)
                    {
                        throw new IllegalArgumentException("non-null value required for init parameter " + entry.getKey());
                    }
                    if (NamedHolder.this.getInitParameter(entry.getKey()) != null)
                    {
                        if (clash == null)
                            clash = new HashSet<>();
                        clash.add(entry.getKey());
                    }
                }
                if (clash != null)
                    return clash;
                NamedHolder.this.getInitParameters().putAll(initParameters);
                return Collections.emptySet();
            }
        }
    }
}
