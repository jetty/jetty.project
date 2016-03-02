//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import javax.enterprise.context.spi.Context;
import javax.enterprise.context.spi.Contextual;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;

import org.eclipse.jetty.cdi.core.AnyLiteral;
import org.eclipse.jetty.cdi.core.ScopedInstance;
import org.eclipse.jetty.cdi.core.SimpleBeanStore;
import org.eclipse.jetty.cdi.websocket.annotation.WebSocketScope;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;

/**
 * WebSocket Scope Context.
 * <p>
 * A CDI Context definition for how CDI will use objects defined to belong to the WebSocketScope
 */
public class WebSocketScopeContext implements Context
{
    private static final Logger LOG = Log.getLogger(WebSocketScopeContext.class);

    private static ThreadLocal<WebSocketScopeContext> current = new ThreadLocal<>();

    public static WebSocketScopeContext current()
    {
        return current.get();
    }

    private SimpleBeanStore beanStore;

    @Inject
    private BeanManager beanManager;

    private ThreadLocal<org.eclipse.jetty.websocket.api.Session> session = new ThreadLocal<>();

    public void begin()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{} begin()",this);
        }
        current.set(this);
    }

    public void create()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{} create()",this);
        }
        current.set(this);
        beanStore = new SimpleBeanStore();
    }

    public void destroy()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{} destroy()",this);
        }

        beanStore.destroy();
    }

    public void end()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{} end()",this);
        }
        beanStore.clear();
    }

    @SuppressWarnings({ "unchecked" })
    @Override
    public <T> T get(Contextual<T> contextual)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{} get({})",this,contextual);
        }

        Bean<T> bean = (Bean<T>)contextual;

        if (bean.getBeanClass().isAssignableFrom(Session.class))
        {
            return (T)this.session;
        }
        
        if (beanStore == null)
        {
            return null;
        }

        List<ScopedInstance<?>> beans = beanStore.getBeans(contextual);

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
            LOG.debug("{} get({},{})",this,contextual,creationalContext);
        }

        Bean<T> bean = (Bean<T>)contextual;

        if (bean.getBeanClass().isAssignableFrom(Session.class))
        {
            return (T)this.session;
        }

        if (beanStore == null)
        {
            beanStore = new SimpleBeanStore();
        }

        List<ScopedInstance<?>> beans = beanStore.getBeans(contextual);

        if ((beans != null) && (!beans.isEmpty()))
        {
            for (ScopedInstance<?> instance : beans)
            {
                if (instance.bean.equals(bean))
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
        beanStore.addBean(customInstance);
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

    public void setSession(org.eclipse.jetty.websocket.api.Session sess)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{} setSession({})",this,sess);
        }
        current.set(this);
        this.session.set(sess);
    }

    public org.eclipse.jetty.websocket.api.Session getSession()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{} getSession()",this);
        }
        return this.session.get();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%X[%s]",this.getClass().getSimpleName(),hashCode(),beanStore);
    }
}
