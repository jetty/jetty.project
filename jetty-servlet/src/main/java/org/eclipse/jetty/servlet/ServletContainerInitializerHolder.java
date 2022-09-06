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

package org.eclipse.jetty.servlet;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds a ServletContainerInitializer.
 */
public class ServletContainerInitializerHolder extends BaseHolder<ServletContainerInitializer>
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletContainerInitializerHolder.class);
    protected Set<String> _startupClassNames = new HashSet<>();
    protected Set<Class<?>> _startupClasses = new HashSet<>();
    public static final Pattern __pattern = Pattern.compile("ContainerInitializer\\{([^,]*),interested=(\\[[^\\]]*\\])(,applicable=(\\[[^\\]]*\\]))?(,annotated=(\\[[^\\]]*\\]))?\\}");

    protected ServletContainerInitializerHolder(Source source)
    {
        super(source);
    }
    
    public ServletContainerInitializerHolder()
    {
        this(Source.EMBEDDED);
    }

    public ServletContainerInitializerHolder(Class<? extends ServletContainerInitializer> sciClass)
    {
        super(Source.EMBEDDED);
        setHeldClass(sciClass);
    }
    
    public ServletContainerInitializerHolder(Class<? extends ServletContainerInitializer> sciClass, Class<?>... startupClasses)
    {
        super(Source.EMBEDDED);
        setHeldClass(sciClass);
        _startupClasses.addAll(Arrays.asList(startupClasses));
    }
    
    public ServletContainerInitializerHolder(ServletContainerInitializer sci, Class<?>... startupClasses)
    {
        this(Source.EMBEDDED, sci, startupClasses);
    }

    public ServletContainerInitializerHolder(Source source, ServletContainerInitializer sci, Class<?>... startupClasses)
    {
        super(source);
        setInstance(sci);
        if (startupClasses != null)
            _startupClasses.addAll(Arrays.asList(startupClasses));
    }

    /**
     * @param names the names of classes which should be passed to the SCI onStartup method
     */
    public void addStartupClasses(String... names)
    {
        Collections.addAll(_startupClassNames, names);
    }

    /**
     * @param clazzes classes that should be passed to the SCI onStartup method
     */
    public void addStartupClasses(Class<?>... clazzes)
    {
        Collections.addAll(_startupClasses, clazzes);
    }

    protected Set<Class<?>> resolveStartupClasses() throws Exception
    {
        Set<Class<?>> classes = new HashSet<>();
        for (String name : _startupClassNames)
        {
            classes.add(Loader.loadClass(name)); //TODO catch CNFE?
        }
        return classes;
    }

    @Override
    public void doStart() throws Exception
    {
        //Ensure all startup classes are also loaded     
        Set<Class<?>> classes = new HashSet<>(_startupClasses);

        //Ensure SCI class is loaded
        super.doStart();

        //load all classnames
        classes.addAll(resolveStartupClasses());

        ContextHandler.Context ctx = null;
        if (getServletHandler() != null)
        {
            ctx = getServletHandler().getServletContextHandler().getServletContext();    
        }
        
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

        try
        {

            ctx.setExtendedListenerTypes(true);
            if (LOG.isDebugEnabled())
            {
                long start = NanoTime.now();
                initializer.onStartup(classes, ctx);
                LOG.debug("ServletContainerInitializer {} called in {}ms", getClassName(), NanoTime.millisSince(start));
            }
            else
                initializer.onStartup(classes, ctx);
        }
        finally
        {
            ctx.setExtendedListenerTypes(false);
        }
    }
    
    /**
     * Re-inflate a stringified ServletContainerInitializerHolder.
     * 
     * @param loader the classloader to use to load the startup classes
     * @param string the stringified representation of the ServletContainerInitializerHolder
     * 
     * @return a new ServletContainerInitializerHolder instance populated by the info in the string
     */
    public static ServletContainerInitializerHolder fromString(ClassLoader loader, String string)
    {
        Matcher m = __pattern.matcher(string);

        if (!m.matches())
            throw new IllegalArgumentException(string);

        try
        {
            //load the ServletContainerInitializer and create an instance
            String sciClassname = m.group(1);
            ServletContainerInitializer sci = (ServletContainerInitializer)loader.loadClass(sciClassname).getDeclaredConstructor().newInstance();
            ServletContainerInitializerHolder holder = new ServletContainerInitializerHolder(new Source(Source.Origin.ANNOTATION, sciClassname));
            holder.setInstance(sci);
            
            //ensure all classes to be passed to onStartup are resolved
            Set<Class<?>> classes = new HashSet<>();
            String[] classnames = StringUtil.arrayFromString(m.group(2));
            for (String name:classnames)
                classes.add(loader.loadClass(name));
            
            classnames = StringUtil.arrayFromString(m.group(4));
            for (String name:classnames)
                classes.add(loader.loadClass(name));

            classnames = StringUtil.arrayFromString(m.group(6));
            for (String name:classnames)
                classes.add(loader.loadClass(name));
            
            holder.addStartupClasses(classes.toArray(new Class<?>[0]));
            
            return holder;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(string, e);
        }
    }
    
    @Override
    public String toString()
    {
        Set<String> interested = new HashSet<>(_startupClassNames);
        _startupClasses.forEach((c) -> interested.add(c.getName()));
        //for backward compatibility the old output format must be retained
        return String.format("ContainerInitializer{%s,interested=%s,applicable=%s,annotated=%s}", getClassName(), interested, Collections.emptySet(), Collections.emptySet());
    }

    /**
     * Experimental Wrapper mechanism for ServletContainerInitializer objects.
     * <p>
     * Beans in {@code ServletContextHandler} or {@code WebAppContext} that implement this interface
     * will be called to optionally wrap any newly created ServletContainerInitializers
     * (before their onStartup method is called)
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
