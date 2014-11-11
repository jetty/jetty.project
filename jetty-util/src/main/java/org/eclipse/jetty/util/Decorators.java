//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a collection of {@link Decorator} instances that apply to a known
 * state, such as a WebAppContext, WebSocketServerFactory, or WebSocketClient.
 * <p>
 * Consistent single location for all Decorator behavior, with equal behavior in
 * a ServletContext and also for a stand alone client.
 */
public class Decorators
{
    private List<Decorator> decorators = new ArrayList<>();

    public List<Decorator> getDecorators()
    {
        return Collections.unmodifiableList(decorators);
    }

    public void setDecorators(List<Decorator> decorators)
    {
        this.decorators.clear();
        if (decorators != null)
        {
            this.decorators.addAll(decorators);
        }
    }

    public void addDecorator(Decorator decorator)
    {
        this.decorators.add(decorator);
    }

    public void destroy(Object obj)
    {
        for (Decorator decorator : this.decorators)
        {
            decorator.destroy(obj);
        }
    }

    public <T> T decorate(T obj)
    {
        T f = obj;
        for (Decorator decorator : this.decorators)
        {
            f = decorator.decorate(f);
        }
        return f;
    }

    public <T> T createInstance(Class<T> clazz) throws Exception
    {
        T o = clazz.newInstance();
        return o;
    }
}
