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

package org.eclipse.jetty.ee10.plus.webapp;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;

import org.eclipse.jetty.ee10.plus.jndi.Transaction;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee10.webapp.FragmentConfiguration;
import org.eclipse.jetty.ee10.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebXmlConfiguration;
import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.util.NanoTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration
 */
public class PlusConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(PlusConfiguration.class);
    private static final String LOCK_JNDI_KEY = "org.eclipse.jetty.ee10.plus.webapp.PlusConfiguration.jndiKey";

    public PlusConfiguration()
    {
        super(new Builder()
            .addDependencies(EnvConfiguration.class, WebXmlConfiguration.class, MetaInfConfiguration.class, FragmentConfiguration.class)
            .addDependents(JettyWebXmlConfiguration.class));
    }

    @Override
    public void preConfigure(WebAppContext context)
        throws Exception
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
        context.setAttribute(InjectionCollection.INJECTION_COLLECTION, null);
        context.setAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION, null);
    }

    public void bindUserTransaction(WebAppContext context)
        throws Exception
    {
        try
        {
            Transaction.bindTransactionToENC(ServletContextHandler.__environment.getName());
        }
        catch (NameNotFoundException e)
        {
            try
            {
                org.eclipse.jetty.plus.jndi.Transaction.bindTransactionToENC(ServletContextHandler.__environment.getName());
            }
            catch (NameNotFoundException x)
            {
                LOG.debug("No Transaction manager found - if your webapp requires one, please configure one.");
            }
        }
    }

    protected void lockCompEnv(WebAppContext wac)
        throws Exception
    {
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(wac.getClassLoader());
        try
        {
            Integer key = (int)(this.hashCode() ^ NanoTime.now());
            Context context = new InitialContext();
            Context compCtx = (Context)context.lookup("java:comp");
            wac.setAttribute(LOCK_JNDI_KEY, key);
            compCtx.addToEnvironment("org.eclipse.jetty.jndi.lock", key);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
    }

    protected void unlockCompEnv(WebAppContext wac)
        throws Exception
    {
        Object o = wac.removeAttribute(LOCK_JNDI_KEY);
        if (o instanceof Integer key)
        {
            ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(wac.getClassLoader());

            try
            {
                Context context = new InitialContext();
                Context compCtx = (Context)context.lookup("java:comp");
                compCtx.addToEnvironment("org.eclipse.jetty.jndi.unlock", key);
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(oldLoader);
            }
        }
    }
}
