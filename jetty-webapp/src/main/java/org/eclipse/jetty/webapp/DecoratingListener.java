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
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;

import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A ServletContextAttributeListener that listens for a specific attribute
 * name (default "org.eclipse.jetty.webapp.Decorator") to obtain a
 * decorator instance from the webapp.  The instance is then either coerced
 * to a Decorator or reflected for decorator compatible methods so it can
 * be added to the {@link WebAppContext#getObjectFactory()} as a
 * {@link Decorator}.
 */
public class DecoratingListener implements ServletContextAttributeListener
{
    private static final Logger LOG = Log.getLogger(DecoratingListener.class);
    private static final MethodType decorateType;
    private static final MethodType destroyType;

    static
    {
        try
        {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            decorateType = MethodType.methodType(Object.class, Object.class);
            destroyType = MethodType.methodType(Void.TYPE, Object.class);
            // Ensure we have a match
            lookup.findVirtual(Decorator.class, "decorate", decorateType);
            lookup.findVirtual(Decorator.class, "destroy", destroyType);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    private final WebAppContext _context;
    private final String _attributeName;
    private Decorator _decorator;

    public DecoratingListener()
    {
        this(null, null);
    }

    public DecoratingListener(String attributeName)
    {
        this(null, attributeName);
    }

    public DecoratingListener(WebAppContext context)
    {
        this(context, null);
    }

    public DecoratingListener(WebAppContext context, String attributeName)
    {
        _context = context == null ? WebAppContext.getCurrentWebAppContext() : context;
        _attributeName = attributeName == null ? DecoratingListener.class.getPackageName() + ".Decorator" : attributeName;
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
            final MethodHandle decorate = lookup.findVirtual(clazz, "decorate", decorateType);
            final MethodHandle destroy = lookup.findVirtual(clazz, "destroy", destroyType);
            return new Decorator()
            {
                @Override
                public <T> T decorate(T o)
                {
                    try
                    {
                        return (T)decorate.invoke(object, o);
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
                        destroy.invoke(object, o);
                    }
                    catch (Throwable t)
                    {
                        throw new RuntimeException(t);
                    }
                }
            };
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
        return null;
    }

    @Override
    public void attributeAdded(ServletContextAttributeEvent event)
    {
        if (_attributeName.equals(event.getName()))
        {
            _decorator = asDecorator(event.getValue());
            if (_decorator == null)
                LOG.warn("Could not create decorator from {}={}", event.getName(), event.getValue());
            else
                _context.getObjectFactory().addDecorator(_decorator);
        }
    }

    @Override
    public void attributeRemoved(ServletContextAttributeEvent event)
    {
        if (_attributeName.equals(event.getName()) && _decorator != null)
        {
            _context.getObjectFactory().removeDecorator(_decorator);
        }
    }

    @Override
    public void attributeReplaced(ServletContextAttributeEvent event)
    {
        if (_attributeName.equals(event.getName()))
        {
            if (_decorator != null)
                _context.getObjectFactory().removeDecorator(_decorator);
            attributeAdded(event);
        }
    }
}
