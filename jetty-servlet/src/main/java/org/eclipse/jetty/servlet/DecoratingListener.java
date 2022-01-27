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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import org.eclipse.jetty.util.Decorator;

/**
 * A ServletContextAttributeListener that listens for a context
 * attribute to obtain a decorator instance.  The instance is then either
 * coerced to a {@link Decorator} or reflected for decorator compatible methods
 * so it can be added to the {@link ServletContextHandler#getObjectFactory()} as a
 * {@link Decorator}.
 */
public class DecoratingListener implements ServletContextAttributeListener
{
    private static final MethodType DECORATE_TYPE;
    private static final MethodType DESTROY_TYPE;

    static
    {
        try
        {
            DECORATE_TYPE = MethodType.methodType(Object.class, Object.class);
            DESTROY_TYPE = MethodType.methodType(void.class, Object.class);

            // Check we have the right MethodTypes for the current Decorator signatures
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            lookup.findVirtual(Decorator.class, "decorate", DECORATE_TYPE);
            lookup.findVirtual(Decorator.class, "destroy", DESTROY_TYPE);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    private final ServletContextHandler _context;
    private final String _attributeName;
    private Decorator _decorator;

    public DecoratingListener(ServletContextHandler context, String attributeName)
    {
        Objects.requireNonNull(context);
        Objects.requireNonNull(attributeName);
        _context = context;
        _attributeName = attributeName;
        Object decorator = _context.getAttribute(_attributeName);
        if (decorator != null)
            _context.getObjectFactory().addDecorator(asDecorator(decorator));
    }

    public String getAttributeName()
    {
        return _attributeName;
    }

    public ServletContext getServletContext()
    {
        return _context.getServletContext();
    }

    private Decorator asDecorator(Object object)
    {
        if (object == null)
            return null;
        if (object instanceof Decorator)
            return (Decorator)object;

        try
        {
            Class<?> clazz = object.getClass();

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle decorate = lookup.findVirtual(clazz, "decorate", DECORATE_TYPE);
            MethodHandle destroy = lookup.findVirtual(clazz, "destroy", DESTROY_TYPE);
            return new DynamicDecorator(object, decorate, destroy);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void attributeAdded(ServletContextAttributeEvent event)
    {
        if (_attributeName.equals(event.getName()))
        {
            _decorator = asDecorator(event.getValue());
            _context.getObjectFactory().addDecorator(_decorator);
        }
    }

    @Override
    public void attributeRemoved(ServletContextAttributeEvent event)
    {
        if (_attributeName.equals(event.getName()) && _decorator != null)
        {
            _context.getObjectFactory().removeDecorator(_decorator);
            _decorator = null;
        }
    }

    @Override
    public void attributeReplaced(ServletContextAttributeEvent event)
    {
        attributeRemoved(event);
        attributeAdded(event);
    }

    private static class DynamicDecorator implements Decorator
    {
        private final Object _object;
        private final MethodHandle _decorate;
        private final MethodHandle _destroy;

        private DynamicDecorator(Object object, MethodHandle decorate, MethodHandle destroy)
        {
            _object = object;
            _decorate = decorate;
            _destroy = destroy;
        }

        @Override
        public <T> T decorate(T o)
        {
            try
            {
                return (T)_decorate.invoke(_object, o);
            }
            catch (Throwable t)
            {
                throw new RuntimeException(t);
            }
        }

        @Override
        public void destroy(Object o)
        {
            try
            {
                _destroy.invoke(_object, o);
            }
            catch (Throwable t)
            {
                throw new RuntimeException(t);
            }
        }
    }
}
