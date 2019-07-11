//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * A Decorator invokes a CDI provider within
 * a webapplication to decorate objects created by the contexts {@link org.eclipse.jetty.util.DecoratedObjectFactory}
 * (typically Listeners, Filters and Servlets).
 * The CDI provide is invoked using {@link MethodHandle}s to avoid any CDI instance or dependencies within the server scope.
 */
public class CdiDecorator implements Decorator
{
    private final static Logger LOG = Log.getLogger(CdiServletContainerInitializer.class);

    private final WebAppContext _context;
    private final Class<?> _cdiClass;
    private final Class<?> _beanManagerClass;
    private final Class<?> _annotatedTypeClass;
    private final Class<?> _injectionTargetClass;
    private final Class<?> _creationalContextClass;
    private final Class<?> _contextualClass;

    private MethodHandle _createAnnotatedType;
    private MethodHandle _createInjectionTarget;
    private MethodHandle _createCreationalContext;
    private MethodHandle _inject;
    private MethodHandle _destroy;

    public CdiDecorator(WebAppContext context) throws ClassNotFoundException, UnsupportedOperationException
    {
        _context = context;
        ClassLoader classLoader = _context.getClassLoader();

        try
        {
            _cdiClass = classLoader.loadClass("javax.enterprise.inject.spi.CDI");
        }
        catch (Exception e)
        {
            throw new UnsupportedOperationException(e);
        }
        _beanManagerClass = classLoader.loadClass("javax.enterprise.inject.spi.BeanManager");
        _annotatedTypeClass = classLoader.loadClass("javax.enterprise.inject.spi.AnnotatedType");
        _injectionTargetClass = classLoader.loadClass("javax.enterprise.inject.spi.InjectionTarget");
        _creationalContextClass = classLoader.loadClass("javax.enterprise.context.spi.CreationalContext");
        _contextualClass = classLoader.loadClass("javax.enterprise.context.spi.Contextual");
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
            if (_inject == null)
            {
                MethodHandles.Lookup lookup = MethodHandles.lookup();

                MethodHandle current = lookup.findStatic(_cdiClass, "current", MethodType.methodType(_cdiClass));
                MethodHandle getBeanManager = lookup.findVirtual(_cdiClass, "getBeanManager", MethodType.methodType(_beanManagerClass));
                Object manager = getBeanManager.invoke(current.invoke());

                _createAnnotatedType = lookup.findVirtual(_beanManagerClass, "createAnnotatedType", MethodType.methodType(_annotatedTypeClass, Class.class))
                    .bindTo(manager);
                _createInjectionTarget = lookup.findVirtual(_beanManagerClass, "createInjectionTarget", MethodType.methodType(_injectionTargetClass, _annotatedTypeClass))
                    .bindTo(manager);
                _createCreationalContext = lookup.findVirtual(_beanManagerClass, "createCreationalContext", MethodType.methodType(_creationalContextClass, _contextualClass))
                    .bindTo(manager).bindTo(null);
                _inject = lookup.findVirtual(_injectionTargetClass, "inject", MethodType.methodType(Void.TYPE, Object.class, _creationalContextClass));
            }

            Object annotatedType = _createAnnotatedType.invoke(o.getClass());
            Object injectionTarget = _createInjectionTarget.invoke(annotatedType);
            Object creationalContext = _createCreationalContext.invoke();
            _inject.invoke(injectionTarget, o, creationalContext);
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
            if (_destroy == null)
            {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodHandle current = lookup.findStatic(_cdiClass, "current", MethodType.methodType(_cdiClass));
                Object cdi = current.invoke();
                _destroy = lookup.findVirtual(_cdiClass, "destroy", MethodType.methodType(Void.TYPE, Object.class))
                    .bindTo(cdi);
            }
            _destroy.invoke(o);
        }
        catch (Throwable th)
        {
            LOG.warn("Unable to destroy " + o, th);
        }
    }
}
