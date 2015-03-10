//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.cdi.websocket;

import java.util.Set;

import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;

import org.eclipse.jetty.cdi.core.AnyLiteral;
import org.eclipse.jetty.cdi.core.ScopedInstance;
import org.eclipse.jetty.cdi.core.ScopedInstances;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;

@Dependent
public class WebSocketScopeContext
{
    private static final Logger LOG = Log.getLogger(WebSocketScopeContext.class);
    public static ThreadLocal<ScopedInstances> state = new ThreadLocal<>();

    private ScopedInstances scope;

    @Inject
    private BeanManager beanManager;
    
    @Inject
    private JettyWebSocketSessionProducer jettySessionProducer;

    @Inject
    private JavaWebSocketSessionProducer javaSessionProducer;

    public void begin()
    {
        LOG.debug("begin()");
        if (state.get() != null)
        {
            return;
        }
        state.set(scope);
    }
    
    public void create()
    {
        LOG.debug("create()");
        scope = new ScopedInstances();
    }

    public void destroy()
    {
        LOG.debug("destroy()");
        for (ScopedInstance<?> entry : scope)
        {
            entry.destroy();
        }
    }

    public void end()
    {
        LOG.debug("end()");
        if (state.get() == null)
        {
            return;
        }
        state.remove();
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> T newInstance(Class<T> clazz)
    {
        LOG.debug("newInstance()");
        Set<Bean<?>> beans = beanManager.getBeans(clazz,AnyLiteral.INSTANCE);
        if (beans.isEmpty())
        {
            return null;
        }

        Bean bean = beans.iterator().next();
        CreationalContext cc = beanManager.createCreationalContext(bean);
        return (T)beanManager.getReference(bean,clazz,cc);
    }

    public void setSession(Session sess)
    {
        LOG.debug("setSession({})",sess);
        jettySessionProducer.setSession(sess);
        if(sess instanceof javax.websocket.Session)
        {
            javaSessionProducer.setSession((javax.websocket.Session)sess);
        }
    }
}
