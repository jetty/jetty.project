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

package org.eclipse.jetty.cdi.core;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;

public class ScopedInstance<T>
{
    public Bean<T> bean;
    public CreationalContext<T> creationalContext;
    public T instance;

    public void destroy()
    {
        bean.destroy(instance,creationalContext);
    }
    
    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();
        s.append("ScopedInstance[");
        s.append(bean);
        s.append(',').append(creationalContext);
        s.append(']');
        return s.toString();
    }
}
