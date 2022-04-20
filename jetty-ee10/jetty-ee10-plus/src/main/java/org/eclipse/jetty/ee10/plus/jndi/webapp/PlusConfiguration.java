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

package org.eclipse.jetty.ee10.plus.jndi.webapp;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;

import org.eclipse.jetty.ee10.plus.annotation.InjectionCollection;
import org.eclipse.jetty.ee10.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.ee10.plus.jndi.Transaction;
import org.eclipse.jetty.ee10.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee10.webapp.FragmentConfiguration;
import org.eclipse.jetty.ee10.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebXmlConfiguration;
import org.eclipse.jetty.jndi.NamingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration
 */
public class PlusConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(PlusConfiguration.class);

    private Integer _key;

    public PlusConfiguration()
    {
        addDependencies(EnvConfiguration.class, WebXmlConfiguration.class, MetaInfConfiguration.class, FragmentConfiguration.class);
        addDependents(JettyWebXmlConfiguration.class);
    }

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
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(wac.getClassLoader());
        try
        {
            _key = (int)(this.hashCode() ^ System.nanoTime());
            Context context = new InitialContext();
            Context compCtx = (Context)context.lookup("java:comp");
            compCtx.addToEnvironment(NamingContext.LOCK_PROPERTY, _key);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
    }

    protected void unlockCompEnv(WebAppContext wac)
        throws Exception
    {
        if (_key != null)
        {
            ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(wac.getClassLoader());

            try
            {
                Context context = new InitialContext();
                Context compCtx = (Context)context.lookup("java:comp");
                compCtx.addToEnvironment("org.eclipse.jetty.jndi.unlock", _key);
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(oldLoader);
            }
        }
    }
}
