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

package org.eclipse.jetty.ee10.cdi;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.util.Decorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Decorator that invokes the CDI provider within a webapp to decorate objects created by
 * the contexts {@link org.eclipse.jetty.util.DecoratedObjectFactory}
 * (typically Listeners, Filters and Servlets).
 * The CDI provider is invoked using reflection to avoid any CDI instance
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

    private static final Object NULL_SINGLETON_ARG = null;
    private static final Object[] NULL_ARRAY_ARG = new Object[]{null};

    public static final String MODE = "CdiSpiDecorator";

    private final ServletContextHandler _context;
    private final Map<Object, Decorated> _decorated = new HashMap<>();

    private final Method _current;
    private final Method _getBeanManager;
    private final Method _createAnnotatedType;
    private final Method _createInjectionTarget;
    private final Method _getInjectionTargetFactory;
    private final Method _createCreationalContext;
    private final Method _inject;
    private final Method _dispose;
    private final Method _release;
    private final Set<String> _undecorated = new HashSet<>(List.of("org.jboss.weld.environment.servlet.Listener", "org.jboss.weld.environment.servlet.EnhancedListener"));

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
            Class<?> beanClass = classLoader.loadClass("jakarta.enterprise.inject.spi.Bean");
            Class<?> beanManagerClass = classLoader.loadClass("jakarta.enterprise.inject.spi.BeanManager");
            Class<?> annotatedTypeClass = classLoader.loadClass("jakarta.enterprise.inject.spi.AnnotatedType");
            Class<?> injectionTargetClass = classLoader.loadClass("jakarta.enterprise.inject.spi.InjectionTarget");
            Class<?> injectionTargetFactoryClass = classLoader.loadClass("jakarta.enterprise.inject.spi.InjectionTargetFactory");
            Class<?> creationalContextClass = classLoader.loadClass("jakarta.enterprise.context.spi.CreationalContext");
            Class<?> contextualClass = classLoader.loadClass("jakarta.enterprise.context.spi.Contextual");

            //Use reflection rather than MethodHandles. Reflection respects the classloader that loaded the class, which means
            //that as it's a WebAppClassLoader it will do hiding of the cdi spi classes that are on the server classpath. MethodHandles
            //see both the cdi api classes from the server classpath and the webapp classpath and throws an exception.
            _current = cdiClass.getMethod("current", null);
            _getBeanManager = cdiClass.getMethod("getBeanManager", null);
            _createAnnotatedType = beanManagerClass.getMethod("createAnnotatedType", Class.class);
            _getInjectionTargetFactory = beanManagerClass.getMethod("getInjectionTargetFactory", annotatedTypeClass);
            _createInjectionTarget = injectionTargetFactoryClass.getMethod("createInjectionTarget", beanClass);
            _createCreationalContext = beanManagerClass.getMethod("createCreationalContext", contextualClass);
            _inject = injectionTargetClass.getMethod("inject", Object.class, creationalContextClass);
            _dispose = injectionTargetClass.getMethod("dispose", Object.class);
            _release = creationalContextClass.getMethod("release", null);
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
            Object manager = _getBeanManager.invoke(_current.invoke(null));
            // AnnotatedType annotatedType = manager.createAnnotatedType((Class<T>)o.getClass());
            Object annotatedType = _createAnnotatedType.invoke(manager, o.getClass());
            // CreationalContext creationalContext = manager.createCreationalContext(null);
            _creationalContext = _createCreationalContext.invoke(manager, NULL_SINGLETON_ARG);
            //InjectionTargetFactory injectionTargetFactory = manager.getInjectionTargetFactory(AnnotatedType<T)
            Object injectionTargetFactory = _getInjectionTargetFactory.invoke(manager, annotatedType);
            // InjectionTarget injectionTarget = injectionTargetFactory.createInjectionTarget();
            _injectionTarget = _createInjectionTarget.invoke(injectionTargetFactory, NULL_ARRAY_ARG);
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
