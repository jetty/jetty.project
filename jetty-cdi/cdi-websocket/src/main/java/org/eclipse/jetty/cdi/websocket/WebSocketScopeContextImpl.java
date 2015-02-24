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

import javax.enterprise.context.spi.Context;
import javax.enterprise.context.spi.Contextual;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.inject.Inject;

import org.eclipse.jetty.cdi.core.ScopedInstance;
import org.eclipse.jetty.util.log.Logger;

public class WebSocketScopeContextImpl implements Context
{
    @Inject
    private Logger LOG;
    
    private WebSocketScopeContextHolder customScopeContextHolder;

    @Override
    public <T> T get(Contextual<T> contextual)
    {
        LOG.debug(".get({})",contextual);
        
        Bean bean = (Bean) contextual;
        if (customScopeContextHolder.getBeans().containsKey(bean.getBeanClass())) {
            return (T) customScopeContextHolder.getBean(bean.getBeanClass()).instance;
        }
        
        return null;
    }

    @Override
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext)
    {
        LOG.debug(".get({},{})",contextual,creationalContext);
        // TODO Auto-generated method stub
        
        Bean bean = (Bean) contextual;
        if (customScopeContextHolder.getBeans().containsKey(bean.getBeanClass())) {
            return (T) customScopeContextHolder.getBean(bean.getBeanClass()).instance;
        } 
        
        T t = (T) bean.create(creationalContext);
        ScopedInstance customInstance = new ScopedInstance();
        customInstance.bean = bean;
        customInstance.creationalContext = creationalContext;
        customInstance.instance = t;
        customScopeContextHolder.putBean(customInstance);
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
}
