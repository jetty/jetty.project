//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.CDI;

import org.eclipse.jetty.cdi.core.AnyLiteral;
import org.eclipse.jetty.cdi.core.ScopedInstance;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.WebSocketSession;
import org.eclipse.jetty.websocket.core.scopes.WebSocketContainerScope;

public class WebSocketCdiListener extends AbstractContainerListener implements WebSocketSession.Listener
{
    static final Logger LOG = Log.getLogger(WebSocketCdiListener.class);

    private Map<String, ScopedInstance<WebSocketScopeContext>> instances = new ConcurrentHashMap<>();

    @SuppressWarnings(
            {"rawtypes", "unchecked"})
    public static <T> ScopedInstance<T> newInstance(Class<T> clazz)
    {
        BeanManager bm = CDI.current().getBeanManager();

        ScopedInstance sbean = new ScopedInstance();
        Set<Bean<?>> beans = bm.getBeans(clazz, AnyLiteral.INSTANCE);
        if (beans.size() > 0)
        {
            sbean.bean = beans.iterator().next();
            sbean.creationalContext = bm.createCreationalContext(sbean.bean);
            sbean.instance = bm.getReference(sbean.bean, clazz, sbean.creationalContext);
            return sbean;
        }
        else
        {
            throw new RuntimeException(String.format("Can't find class %s", clazz));
        }
    }

    @Override
    public void onCreated(WebSocketSession session)
    {
        String id = toId(session);

        ScopedInstance<WebSocketScopeContext> wsScope = newInstance(WebSocketScopeContext.class);
        wsScope.instance.create();
        wsScope.instance.begin();
        wsScope.instance.setSession(session);

        instances.put(id, wsScope);
    }

    @Override
    public void onOpened(WebSocketSession session)
    {
        // do nothing
    }

    @Override
    public void onClosed(WebSocketSession session)
    {
        String id = toId(session);
        ScopedInstance<WebSocketScopeContext> wsScope = instances.remove(id);
        if (wsScope != null)
        {
            wsScope.instance.end();
            wsScope.instance.destroy();
            wsScope.destroy();
        }
    }

    private String toId(WebSocketSession session)
    {
        return session.getRemoteAddress().toString() + ">" + session.getLocalAddress().toString();
    }

    @Override
    public void lifeCycleStarting(LifeCycle event)
    {
        if (event instanceof WebSocketContainerScope)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("started websocket container [{}]", event);
            }

            WebSocketContainerScope webSocketContainerScope = (WebSocketContainerScope) event;
            webSocketContainerScope.addSessionListener(this);
        }
    }
}
