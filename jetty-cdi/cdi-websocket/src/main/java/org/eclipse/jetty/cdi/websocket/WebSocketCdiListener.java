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

import java.util.Set;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.CDI;

import org.eclipse.jetty.cdi.core.AnyLiteral;
import org.eclipse.jetty.cdi.core.ScopedInstance;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketSessionScope;

public class WebSocketCdiListener extends AbstractContainerListener
{
    static final Logger LOG = Log.getLogger(WebSocketCdiListener.class);

    @SuppressWarnings(
    { "rawtypes", "unchecked" })
    public static <T> ScopedInstance<T> newInstance(Class<T> clazz)
    {
        BeanManager bm = CDI.current().getBeanManager();

        ScopedInstance sbean = new ScopedInstance();
        Set<Bean<?>> beans = bm.getBeans(clazz,AnyLiteral.INSTANCE);
        if (beans.size() > 0)
        {
            sbean.bean = beans.iterator().next();
            sbean.creationalContext = bm.createCreationalContext(sbean.bean);
            sbean.instance = bm.getReference(sbean.bean,clazz,sbean.creationalContext);
            return sbean;
        }
        else
        {
            throw new RuntimeException(String.format("Can't find class %s",clazz));
        }
    }

    public static class ContainerListener extends AbstractContainerListener
    {
        private static final Logger LOG = Log.getLogger(WebSocketCdiListener.ContainerListener.class);
        private final WebSocketContainerScope container;
        private final ScopedInstance<WebSocketScopeContext> wsScope;

        public ContainerListener(WebSocketContainerScope container)
        {
            this.container = container;
            this.wsScope = newInstance(WebSocketScopeContext.class);
            this.wsScope.instance.create();
        }

        @Override
        public void lifeCycleStarted(LifeCycle event)
        {
            if (event == container)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("starting websocket container [{}]",event);
                }
                wsScope.instance.begin();
                return;
            }
            
            if (event instanceof WebSocketSessionScope)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("starting websocket session [{}]",event);
                }
                wsScope.instance.setSession((Session)event);
                return;
            }
        }

        @Override
        public void lifeCycleStopped(LifeCycle event)
        {
            if (event == container)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("stopped websocket container [{}]",event);
                }
                this.wsScope.instance.end();
                this.wsScope.instance.destroy();
                this.wsScope.destroy();
            }
        }
    }
    
    @Override
    public void lifeCycleStarting(LifeCycle event)
    {
        if (event instanceof WebSocketContainerScope)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("started websocket container [{}]",event);
            }
            ContainerListener listener = new ContainerListener((WebSocketContainerScope)event);
            if (event instanceof ContainerLifeCycle)
            {
                ContainerLifeCycle container = (ContainerLifeCycle)event;
                container.addLifeCycleListener(listener);
                container.addEventListener(listener);
            }
            else
            {
                throw new RuntimeException("Unable to setup CDI against non-container: " + event.getClass().getName());
            }
        }
    }
}
