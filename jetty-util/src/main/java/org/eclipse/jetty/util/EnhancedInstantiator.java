//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An instantiator enhanced by {@link Decorator} instances.
 * <p>
 * Consistent single location for all Decorator behavior, with equal behavior in a ServletContext and also for a stand
 * alone client.
 * <p>
 * Used by WebAppContext, WebSocketServerFactory, or WebSocketClient.
 */
public class EnhancedInstantiator implements Iterable<Decorator>
{
    private static final ThreadLocal<EnhancedInstantiator> CURRENT_INSTANTIATOR = new ThreadLocal<>();

    /**
     * Get the current EnhancedInstantiator that this thread is dispatched to.
     * <p>
     * This exists because of various {@link java.util.ServiceLoader} use that makes passing in an EnhancedInstantiator
     * difficult.
     *
     * @return the current EnhancedInstantiator or null
     */
    public static EnhancedInstantiator getCurrentInstantiator()
    {
        return CURRENT_INSTANTIATOR.get();
    }

    public static EnhancedInstantiator setCurrentInstantiator(EnhancedInstantiator instantiator)
    {
        EnhancedInstantiator last = CURRENT_INSTANTIATOR.get();
        if (instantiator == null)
        {
            CURRENT_INSTANTIATOR.remove();
        }
        else
        {
            CURRENT_INSTANTIATOR.set(instantiator);
        }
        return last;
    }
    
    private static final Logger LOG = Log.getLogger(EnhancedInstantiator.class);

    /**
     * ServletContext attribute for the active EnhancedInstantiator
     */
    public static final String ATTR = EnhancedInstantiator.class.getName();

    private List<Decorator> decorators = new ArrayList<>();

    public void addDecorator(Decorator decorator)
    {
        this.decorators.add(decorator);
    }

    public void clear()
    {
        this.decorators.clear();
    }

    public <T> T createInstance(Class<T> clazz) throws InstantiationException, IllegalAccessException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Creating Instance: " + clazz,new Throwable("Creation Stack"));
        }
        T o = clazz.newInstance();
        return decorate(o);
    }

    public <T> T decorate(T obj)
    {
        T f = obj;
        // Decorate is always backwards
        for (int i = decorators.size() - 1; i >= 0; i--)
        {
            f = decorators.get(i).decorate(f);
        }
        return f;
    }

    public void destroy(Object obj)
    {
        for (Decorator decorator : this.decorators)
        {
            decorator.destroy(obj);
        }
    }

    public List<Decorator> getDecorators()
    {
        return Collections.unmodifiableList(decorators);
    }

    @Override
    public Iterator<Decorator> iterator()
    {
        return this.decorators.iterator();
    }

    public void setDecorators(List<? extends Decorator> decorators)
    {
        this.decorators.clear();
        if (decorators != null)
        {
            this.decorators.addAll(decorators);
        }
    }
}
