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

package org.eclipse.jetty.cdi.websocket.scope;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.eclipse.jetty.cdi.websocket.WebSocketScope;

@WebSocketScope
public class Food
{
    private boolean constructed = false;
    private boolean destroyed = false;
    private String name;

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public boolean isConstructed()
    {
        return constructed;
    }

    public boolean isDestroyed()
    {
        return destroyed;
    }

    @PostConstruct
    void init()
    {
        constructed = true;
    }

    @PreDestroy
    void destroy()
    {
        destroyed = true;
    }
}
