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

package org.eclipse.jetty.cdi;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.Decorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Decorator that invokes the CDI provider within a webapp to decorate objects created by
 * the contexts {@link org.eclipse.jetty.util.DecoratedObjectFactory}
 * (typically Listeners, Filters and Servlets).
 * The CDI provider is invoked using {@link MethodHandle}s to avoid any CDI instance
 * or dependencies within the server scope. The code invoked is equivalent to:
 * <pre>
 * public &lt;T&gt; T decorate(T o)
 * {
 *   BeanManager manager = CDI.current().getBeanManager();
 *   manager.createInjectionTarget(manager.createAnnotatedType((Class&lt;T&gt;)o.getClass()))
 *     .inject(o,manager.createCreationalContext(null));
 *   return o;
 * }
 * </pre>
 */
public class CdiSpiDecorator implements Decorator
{
    private static final Logger LOG = LoggerFactory.getLogger(CdiServletContainerInitializer.class);
    public static final String MODE = "CdiSpiDecorator";

    private final ServletContextHandler _context;
    private final Map<Object, Decorated> _decorated = new HashMap<>();

    private final MethodHandle _current;
    private final MethodHandle _getBeanManager;
    private final MethodHandle _createAnnotatedType;
    private final MethodHandle _createInjectionTarget;
    private final MethodHandle _createCreationalContext;
    private final MethodHandle _inject;
    private final MethodHandle _dispose;
    private final MethodHandle _release;
    private final Set<String> _undecorated = new HashSet<>(Collections.singletonList("org.jboss.weld.environment.servlet.Listener"));

    public CdiSpiDecorator(ServletContextHandler context) throws UnsupportedOperationException
    {
        _context = context;
        context.setAttribute(CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, MODE);
        ClassLoader classLoader = _context.getClassLoader();
        if (classLoader == null)
            classLoader = this.getClass().getClassLoader();

        try
        {
            Class<?> cdiClass = classLoader.loadClass("jakarta.enterprise.inject.spi.CDI");
            Class<?> beanManagerClass = classLoader.loadClass("jakarta.enterprise.inject.spi.BeanManager");
            Class<?> annotatedTypeClass = classLoader.loadClass("jakarta.enterprise.inject.spi.AnnotatedType");
            Class<?> injectionTargetClass = classLoader.loadClass("jakarta.enterprise.inject.spi.InjectionTarget");
            Class<?> creationalContextClass = classLoader.loadClass("jakarta.enterprise.context.spi.CreationalContext");
            Class<?> contextualClass = classLoader.loadClass("jakarta.enterprise.context.spi.Contextual");

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            _current = lookup.findStatic(cdiClass, "current", MethodType.methodType(cdiClass));
            _getBeanManager = lookup.findVirtual(cdiClass, "getBeanManager", MethodType.methodType(beanManagerClass));
            _createAnnotatedType = lookup.findVirtual(beanManagerClass, "createAnnotatedType", MethodType.methodType(annotatedTypeClass, Class.class));
            _createInjectionTarget = lookup.findVirtual(beanManagerClass, "createInjectionTarget", MethodType.methodType(injectionTargetClass, annotatedTypeClass));
            _createCreationalContext = lookup.findVirtual(beanManagerClass, "createCreationalContext", MethodType.methodType(creationalContextClass, contextualClass));
            _inject = lookup.findVirtual(injectionTargetClass, "inject", MethodType.methodType(Void.TYPE, Object.class, creationalContextClass));
            _dispose = lookup.findVirtual(injectionTargetClass, "dispose", MethodType.methodType(Void.TYPE, Object.class));
            _release = lookup.findVirtual(creationalContextClass, "release", MethodType.methodType(Void.TYPE));
        }
        catch (Exception e)
        {
            throw new UnsupportedOperationException(e);
        }
    }

    /**
     * Test if a class can be decorated.
     * The default implementation checks the set from  {@link #getUndecoratable()}
     * on the class and all it's super classes.
     * @param clazz The class to check
     * @return True if the class and all it's super classes can be decorated
     */
    protected boolean isDecoratable(Class<?> clazz)
    {
        if (Object.class == clazz)
            return true;
        if (getUndecoratable().contains(clazz.getName()))
            return false;
        return isDecoratable(clazz.getSuperclass());
    }

    /**
     * Get the set of classes that will not be decorated. The default set includes the listener from Weld that will itself
     * setup decoration.
     * @return The modifiable set of class names that will not be decorated (ie {@link #isDecoratable(Class)} will return false.
     * @see #isDecoratable(Class)
     */
    public Set<String> getUndecoratable()
    {
        return _undecorated;
    }

    /**
     * @param classnames The set of class names that will not be decorated.
     * @see #isDecoratable(Class)
     */
    public void setUndecoratable(Set<String> classnames)
    {
        _undecorated.clear();
        if (classnames != null)
            _undecorated.addAll(classnames);
    }

    /**
     * @param classname A class name that will be added to the undecoratable classes set.
     * @see #getUndecoratable()
     * @see #isDecoratable(Class)
     */
    public void addUndecoratable(String... classname)
    {
        _undecorated.addAll(Arrays.asList());
    }

    /**
     * Decorate an object.
     * <p>The signature of this method must match what is introspected for by the
     * Jetty DecoratingListener class.  It is invoked dynamically.</p>
     *
     * @param o The object to be decorated
     * @param <T> The type of the object to be decorated
     * @return The decorated object
     */
    public <T> T decorate(T o)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decorate {} in {}", o, _context);

            if (isDecoratable(o.getClass()))
                _decorated.put(o, new Decorated(o));
        }
        catch (Throwable th)
        {
            LOG.warn("Unable to decorate {}", o, th);
        }
        return o;
    }

    /**
     * Destroy a decorated object.
     * <p>The signature of this method must match what is introspected for by the
     * Jetty DecoratingListener class.  It is invoked dynamically.</p>
     *
     * @param o The object to be destroyed
     */
    public void destroy(Object o)
    {
        try
        {
            Decorated decorated = _decorated.remove(o);
            if (decorated != null)
                decorated.destroy(o);
        }
        catch (Throwable th)
        {
            LOG.warn("Unable to destroy {}", o, th);
        }
    }

    private class Decorated
    {
        private final Object _injectionTarget;
        private final Object _creationalContext;

        Decorated(Object o) throws Throwable
        {
            // BeanManager manager = CDI.current().getBeanManager();
            Object manager = _getBeanManager.invoke(_current.invoke());
            // AnnotatedType annotatedType = manager.createAnnotatedType((Class<T>)o.getClass());
            Object annotatedType = _createAnnotatedType.invoke(manager, o.getClass());
            // CreationalContext creationalContext = manager.createCreationalContext(null);
            _creationalContext = _createCreationalContext.invoke(manager, null);
            // InjectionTarget injectionTarget = manager.createInjectionTarget();
            _injectionTarget = _createInjectionTarget.invoke(manager, annotatedType);
            // injectionTarget.inject(o, creationalContext);
            _inject.invoke(_injectionTarget, o, _creationalContext);
        }

        public void destroy(Object o) throws Throwable
        {
            _dispose.invoke(_injectionTarget, o);
            _release.invoke(_creationalContext);
        }
    }
}
