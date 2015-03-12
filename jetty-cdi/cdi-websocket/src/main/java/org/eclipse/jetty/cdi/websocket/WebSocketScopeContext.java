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

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.Context;
import javax.enterprise.context.spi.Contextual;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;

import org.eclipse.jetty.cdi.core.AnyLiteral;
import org.eclipse.jetty.cdi.core.ScopedInstance;
import org.eclipse.jetty.cdi.core.SimpleBeanStore;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;

/**
 * WebSocket Scope Context.
 * <p>
 * A CDI Context definition for how CDI will use objects defined to belong to the WebSocketScope
 */
@Dependent
public class WebSocketScopeContext implements Context
{
    private static final Logger LOG = Log.getLogger(WebSocketScopeContext.class);
    public static ThreadLocal<SimpleBeanStore> state = new ThreadLocal<>();

    private SimpleBeanStore beanStore;

    @Inject
    private BeanManager beanManager;

    @Inject
    private JettyWebSocketSessionProducer jettySessionProducer;

    @Inject
    private JavaWebSocketSessionProducer javaSessionProducer;

    public void begin()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("begin()");
        }
        if (state.get() != null)
        {
            return;
        }
        state.set(beanStore);
    }

    public void create()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("create()");
        }
        beanStore = new SimpleBeanStore();
    }

    public void destroy()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("destroy()");
        }

        beanStore.destroy();
    }

    public void end()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("end()");
        }
        if (state.get() == null)
        {
            return;
        }
        state.remove();
    }

    @SuppressWarnings({ "unchecked" })
    @Override
    public <T> T get(Contextual<T> contextual)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("get({})",contextual);
        }
        SimpleBeanStore store = state.get();
        if (store == null)
        {
            return null;
        }

        List<ScopedInstance<?>> beans = store.getBeans(contextual);

        if ((beans != null) && (!beans.isEmpty()))
        {
            return (T)beans.get(0).instance;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("get({},{})",contextual,creationalContext);
        }
        Bean<T> bean = (Bean<T>)contextual;

        SimpleBeanStore store = state.get();
        if (store == null)
        {
            store = new SimpleBeanStore();
            state.set(store);
        }

        List<ScopedInstance<?>> beans = store.getBeans(contextual);

        if ((beans != null) && (!beans.isEmpty()))
        {
            for (ScopedInstance<?> instance : beans)
            {
                // TODO: need to work out the creational context comparison logic better
                if (instance.creationalContext.equals(creationalContext))
                {
                    return (T)instance.instance;
                }
            }
        }

        // no bean found, create it
        T t = bean.create(creationalContext);
        ScopedInstance<T> customInstance = new ScopedInstance<>();
        customInstance.bean = bean;
        customInstance.creationalContext = creationalContext;
        customInstance.instance = t;
        store.addBean(customInstance);
        return t;
    }

    @Override
    public Class<? extends Annotation> getScope()
    {
        return WebSocketScope.class;
    }

    @Override
    public boolean isActive()
    {
        return true;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> T newInstance(Class<T> clazz)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("newInstance({})",clazz);
        }
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
        if (sess instanceof javax.websocket.Session)
        {
            javaSessionProducer.setSession((javax.websocket.Session)sess);
        }
    }
}
