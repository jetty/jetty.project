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

package org.eclipse.jetty.webapp;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A ServletContextAttributeListener that listens for a specific context
 * attribute (default "org.eclipse.jetty.webapp.decorator") to obtain a
 * decorator instance from the webapp.  The instance is then either coerced
 * to a Decorator or reflected for decorator compatible methods so it can
 * be added to the {@link WebAppContext#getObjectFactory()} as a
 * {@link Decorator}.
 * The context attribute "org.eclipse.jetty.webapp.DecoratingListener" if
 * not set, is set to the name of the attribute this listener listens for.
 */
public class DecoratingListener implements ServletContextAttributeListener
{
    public static final String DECORATOR_ATTRIBUTE = "org.eclipse.jetty.webapp.decorator";
    private static final Logger LOG = Log.getLogger(DecoratingListener.class);
    private static final MethodType DECORATE_TYPE;
    private static final MethodType DESTROY_TYPE;

    static
    {
        try
        {
            DECORATE_TYPE = MethodType.methodType(Object.class, Object.class);
            DESTROY_TYPE = MethodType.methodType(Void.TYPE, Object.class);

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

    public DecoratingListener()
    {
        this((String)null);
    }

    public DecoratingListener(String attributeName)
    {
        this(WebAppContext.getCurrentWebAppContext(), attributeName);
    }

    public DecoratingListener(ServletContextHandler context)
    {
        this(context, null);
    }

    public DecoratingListener(ServletContextHandler context, String attributeName)
    {
        _context = context;
        Objects.requireNonNull(_context);
        _attributeName = attributeName == null ? DECORATOR_ATTRIBUTE : attributeName;
        checkAndSetAttributeName();
        Object decorator = _context.getAttribute(_attributeName);
        if (decorator != null)
            _context.getObjectFactory().addDecorator(asDecorator(decorator));
    }

    protected void checkAndSetAttributeName()
    {
        // If not set (by another DecoratingListener), flag the attribute that are
        // listening for.  If more than one DecoratingListener is used then this
        // attribute reflects only the first.
        if (_context.getAttribute(getClass().getName()) != null)
            throw new IllegalStateException("Multiple DecoratingListeners detected");
        _context.setAttribute(getClass().getName(), _attributeName);
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
            return new DynamicDecorator(decorate, destroy, object);
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
        private final MethodHandle _decorate;
        private final MethodHandle _destroy;
        private final Object _object;

        private DynamicDecorator(MethodHandle decorate, MethodHandle destroy, Object object)
        {
            _decorate = decorate;
            _destroy = destroy;
            _object = object;
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
