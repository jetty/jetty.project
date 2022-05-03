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

package org.eclipse.jetty.ee9.plus.webapp;

import org.eclipse.jetty.ee9.plus.annotation.InjectionCollection;
import org.eclipse.jetty.ee9.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.util.Decorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PlusDecorator
 */
public class PlusDecorator implements Decorator
{
    private static final Logger LOG = LoggerFactory.getLogger(PlusDecorator.class);

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
                LOG.warn("Destroying instance of {}", o.getClass(), e);
            }
        }
    }
}
