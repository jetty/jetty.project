//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//



package org.eclipse.jetty.servlet;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * Holds a ServletContainerInitializer.
 *
 */
public class ServletContainerInitializerHolder extends BaseHolder<ServletContainerInitializer>
{
    private Set<String> _startupClassNames = new HashSet<>();
    private Set<Class<?>> _startupClasses = new HashSet<>();

    protected ServletContainerInitializerHolder(Source source)
    {
        super(source);
    }
    public ServletContainerInitializerHolder()
    {
        this(Source.EMBEDDED);
    }

    public ServletContainerInitializerHolder(Class<? extends ServletContainerInitializer> SCIClass)
    {
        super(Source.EMBEDDED);
        setHeldClass(SCIClass);
    }
    
    public ServletContainerInitializerHolder(Class<? extends ServletContainerInitializer> SCIClass, Set<Class<?>>startupClasses)
    {
        super(Source.EMBEDDED);
        setHeldClass(SCIClass);
        _startupClasses.addAll(startupClasses);
    }
    
    public ServletContainerInitializerHolder(ServletContainerInitializer sci, Set<Class<?>>startupClasses)
    {
        super(Source.EMBEDDED);
        setInstance(sci);
        _startupClasses.addAll(startupClasses);
    }
    
    
    //TODO add the the constructor that works with Quickstart
    
    /**
     * @param name the name of a class which should be passed to the SCI onStartup method
     */
    public void addStartupClass(String name)
    {
        _startupClassNames.add(name);
    }

    /**
     * @param clazz a class that should be passed to the SCI onStartup method
     */
    public void addStartupClass(Class<?> clazz)
    {
        _startupClasses.add(clazz);
    }
    
    @Override
    public void doStart() throws Exception
    {
        //Ensure SCI class is loaded
        super.doStart();
        
        //Ensure all startup classes are also loaded     
        Set<Class<?>> classes = new HashSet<>(_startupClasses);
        for (String name:_startupClassNames)
        {
            classes.add(Loader.loadClass(name)); //TODO catch CNFE?
        }
        
        ServletContext ctx = getServletHandler().getServletContext();
        if (ctx == null && ContextHandler.getCurrentContext() != null)
            ctx = ContextHandler.getCurrentContext();
        if (ctx == null)
            throw new IllegalStateException("No Context");
        
        
        ServletContainerInitializer initializer = getInstance();
        if (initializer == null)
        {
            //create an instance of the SCI
            initializer = createInstance();
            initializer = wrap(initializer, WrapFunction.class, WrapFunction::wrapServletContainerInitializer);
        }
        
        initializer.onStartup(classes, ctx);
    }
    

    /**
     * Experimental Wrapper mechanism for ServletContainerInitializer objects.
     * <p>
     * Beans in {@code ServletContextHandler} or {@code WebAppContext} that implement this interface
     * will be called to optionally wrap any newly created ServletContainerInitializers
     * (before their {@link ServletContainerInitializer#onStartup(Set<Class<?>>, ServletContext)} method is called)
     * </p>
     */
    public interface WrapFunction
    {
        /**
         * Optionally wrap the ServletContainerInitializer.
         *
         * @param sci the ServletContainerInitializer being passed in.
         * @return the sci(extend from {@link ServletContainerInitializerHolder.Wrapper} if you do wrap the ServletContainerInitializer)
         */
        ServletContainerInitializer wrapServletContainerInitializer(ServletContainerInitializer sci);
    }
    
    public static class Wrapper implements ServletContainerInitializer, Wrapped<ServletContainerInitializer>
    {
        private final ServletContainerInitializer _wrappedSCI;

        public Wrapper(ServletContainerInitializer sci)
        {
            _wrappedSCI = Objects.requireNonNull(sci, "ServletContainerInitializer cannot be null");
        }

        @Override
        public ServletContainerInitializer getWrapped()
        {
            return _wrappedSCI;
        }

        @Override
        public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
        {
            _wrappedSCI.onStartup(c, ctx);
        }
        
        @Override
        public String toString()
        {
            return String.format("%s:%s", this.getClass().getSimpleName(), _wrappedSCI.toString());
        }
    }
}
