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

import javax.websocket.ClientEndpoint;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverImpl;

/**
 * {@link EventDriverImpl} for {@link ClientEndpoint &#064;ClientEndpoint} annotated classes
 */
public class JsrAnnotatedClientImpl implements EventDriverImpl
{
    private ConcurrentHashMap<Class<?>, JsrAnnotatedMetadata> cache = new ConcurrentHashMap<>();

    @Override
    public EventDriver create(Object websocket, WebSocketPolicy policy)
    {
        Class<?> websocketClass = websocket.getClass();

        ClientEndpoint wsclient = websocketClass.getAnnotation(ClientEndpoint.class);
        if (wsclient == null)
        {
            throw new InvalidWebSocketException("Cannot handle @ClientEndpoint annotations here");
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
        return "class annotated with @" + ClientEndpoint.class.getName();
    }

    @Override
    public boolean supports(Object websocket)
    {
        return (websocket.getClass().getAnnotation(ClientEndpoint.class) != null);
    }
}
