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

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;

import org.eclipse.jetty.jndi.NamingContext;
import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.jndi.Transaction;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadClassLoaderScope;
import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Configuration
 */
public class PlusConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = Log.getLogger(PlusConfiguration.class);

    private Integer _key;

    @Override
    public void preConfigure(WebAppContext context)
        throws Exception
    {
        context.getObjectFactory().addDecorator(new PlusDecorator(context));
    }

    @Override
    public void cloneConfigure(WebAppContext template, WebAppContext context) throws Exception
    {
        context.getObjectFactory().addDecorator(new PlusDecorator(context));
    }

    @Override
    public void configure(WebAppContext context)
        throws Exception
    {
        bindUserTransaction(context);

        context.getMetaData().addDescriptorProcessor(new PlusDescriptorProcessor());
    }

    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        //lock this webapp's java:comp namespace as per J2EE spec
        lockCompEnv(context);
    }

    @Override
    public void deconfigure(WebAppContext context)
        throws Exception
    {
        unlockCompEnv(context);
        _key = null;
        context.setAttribute(InjectionCollection.INJECTION_COLLECTION, null);
        context.setAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION, null);
    }

    public void bindUserTransaction(WebAppContext context)
        throws Exception
    {
        try
        {
            Transaction.bindToENC();
        }
        catch (NameNotFoundException e)
        {
            LOG.debug("No Transaction manager found - if your webapp requires one, please configure one.");
        }
    }

    protected void lockCompEnv(WebAppContext wac)
        throws Exception
    {
        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(wac.getClassLoader()))
        {
            _key = (int)(this.hashCode() ^ System.nanoTime());
            Context context = new InitialContext();
            Context compCtx = (Context)context.lookup("java:comp");
            compCtx.addToEnvironment(NamingContext.LOCK_PROPERTY, _key);
        }
    }

    protected void unlockCompEnv(WebAppContext wac)
        throws Exception
    {
        if (_key != null)
        {
            try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(wac.getClassLoader()))
            {
                Context context = new InitialContext();
                Context compCtx = (Context)context.lookup("java:comp");
                compCtx.addToEnvironment("org.eclipse.jetty.jndi.unlock", _key);
            }
        }
    }
}
