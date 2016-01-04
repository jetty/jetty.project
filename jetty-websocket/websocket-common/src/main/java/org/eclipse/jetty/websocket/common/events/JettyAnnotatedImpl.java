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

package org.eclipse.jetty.websocket.common.events;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

public class JettyAnnotatedImpl implements EventDriverImpl
{
    private ConcurrentHashMap<Class<?>, JettyAnnotatedMetadata> cache = new ConcurrentHashMap<>();

    @Override
    public EventDriver create(Object websocket, WebSocketPolicy policy)
    {
        Class<?> websocketClass = websocket.getClass();
        synchronized (this)
        {
            JettyAnnotatedMetadata metadata = cache.get(websocketClass);
            if (metadata == null)
            {
                JettyAnnotatedScanner scanner = new JettyAnnotatedScanner();
                metadata = scanner.scan(websocketClass);
                cache.put(websocketClass,metadata);
            }
            return new JettyAnnotatedEventDriver(policy,websocket,metadata);
        }
    }

    @Override
    public String describeRule()
    {
        return "class is annotated with @" + WebSocket.class.getName();
    }

    @Override
    public boolean supports(Object websocket)
    {
        WebSocket anno = websocket.getClass().getAnnotation(WebSocket.class);
        return (anno != null);
    }

    @Override
    public String toString()
    {
        return String.format("%s [cache.count=%d]",this.getClass().getSimpleName(),cache.size());
    }
}
