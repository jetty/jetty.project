//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.plus.webapp;

import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * PlusDecorator
 */
public class PlusDecorator implements Decorator
{
    private static final Logger LOG = Log.getLogger(PlusDecorator.class);

    protected WebAppContext _context;

    public PlusDecorator(WebAppContext context)
    {
        _context = context;
    }

    @Override
    public Object decorate(Object o)
    {
        InjectionCollection injections = (InjectionCollection)_context.getAttribute(InjectionCollection.INJECTION_COLLECTION);
        if (injections != null)
            injections.inject(o);

        LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)_context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
        if (callbacks != null)
        {
            try
            {
                callbacks.callPostConstructCallback(o);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
        return o;
    }

    @Override
    public void destroy(Object o)
    {
        LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)_context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
        if (callbacks != null)
        {
            try
            {
                callbacks.callPreDestroyCallback(o);
            }
            catch (Exception e)
            {
                LOG.warn("Destroying instance of " + o.getClass(), e);
            }
        }
    }
}
