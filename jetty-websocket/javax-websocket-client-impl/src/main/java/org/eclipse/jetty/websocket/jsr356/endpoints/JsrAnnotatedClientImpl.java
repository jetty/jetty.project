//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.WebSocketClient;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverImpl;

/**
 * {@link EventDriverImpl} for {@link WebSocketClient &#064;WebSocketClient} annotated classes
 */
public class JsrAnnotatedClientImpl implements EventDriverImpl
{
    private ConcurrentHashMap<Class<?>, JsrAnnotatedMetadata> cache = new ConcurrentHashMap<>();

    @Override
    public EventDriver create(Object websocket, WebSocketPolicy policy)
    {
        Class<?> websocketClass = websocket.getClass();

        WebSocketClient wsclient = websocketClass.getAnnotation(WebSocketClient.class);
        if (wsclient == null)
        {
            throw new InvalidWebSocketException("Cannot handle @WebSocketClient annotations here");
        }

        JsrAnnotatedMetadata metadata = cache.get(websocketClass);
        if (metadata == null)
        {
            JsrAnnotatedClientScanner scanner = new JsrAnnotatedClientScanner(websocketClass);
            metadata = scanner.scan();
            cache.put(websocketClass,metadata);
        }

        return new JsrAnnotatedClientEventDriver(policy,websocket,metadata);
    }

    @Override
    public String describeRule()
    {
        return "class annotated with @" + WebSocketClient.class.getName();
    }

    @Override
    public boolean supports(Object websocket)
    {
        return (websocket.getClass().getAnnotation(WebSocketClient.class) != null);
    }
}
