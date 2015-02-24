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

import java.util.Map;

import org.eclipse.jetty.cdi.core.ScopedInstance;

public class WebSocketScopeContextHolder
{
    public <T> Map<Class<T>,ScopedInstance<T>> getBeans()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public <T> ScopedInstance<T> getBean(Class<T> beanClass)
    {
        // TODO Auto-generated method stub
        return null;
    }

    public void putBean(ScopedInstance customInstance)
    {
        // TODO Auto-generated method stub
        
    }

}
