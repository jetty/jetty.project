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

package org.eclipse.jetty.cdi;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
    private static final Logger LOG = Log.getLogger(CdiServletContainerInitializer.class);
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

    public CdiSpiDecorator(ServletContextHandler context) throws UnsupportedOperationException
    {
        _context = context;
        ClassLoader classLoader = _context.getClassLoader();

        try
        {
            Class<?> cdiClass = classLoader.loadClass("javax.enterprise.inject.spi.CDI");
            Class<?> beanManagerClass = classLoader.loadClass("javax.enterprise.inject.spi.BeanManager");
            Class<?> annotatedTypeClass = classLoader.loadClass("javax.enterprise.inject.spi.AnnotatedType");
            Class<?> injectionTargetClass = classLoader.loadClass("javax.enterprise.inject.spi.InjectionTarget");
            Class<?> creationalContextClass = classLoader.loadClass("javax.enterprise.context.spi.CreationalContext");
            Class<?> contextualClass = classLoader.loadClass("javax.enterprise.context.spi.Contextual");

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

            _decorated.put(o, new Decorated(o));
        }
        catch (Throwable th)
        {
            LOG.warn("Unable to decorate " + o, th);
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
            LOG.warn("Unable to destroy " + o, th);
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
