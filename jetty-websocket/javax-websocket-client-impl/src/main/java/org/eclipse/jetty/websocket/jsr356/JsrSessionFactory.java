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

package org.eclipse.jetty.websocket.jsr356;

import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.SessionFactory;
import org.eclipse.jetty.websocket.common.SessionListener;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.jsr356.endpoints.AbstractJsrEventDriver;

public class JsrSessionFactory implements SessionFactory
{
    private AtomicLong idgen = new AtomicLong(0);
    private final ClientContainer container;
    private final SessionListener[] listeners;

    public JsrSessionFactory(ClientContainer container, SessionListener... sessionListeners)
    {
        this.container = container;
        this.listeners = sessionListeners;
    }

    @Override
    public WebSocketSession createSession(URI requestURI, EventDriver websocket, LogicalConnection connection)
    {
        return new JsrSession(container,getNextId(),requestURI,websocket,connection,listeners);
    }

    public String getNextId()
    {
        return String.format("websocket-%d",idgen.incrementAndGet());
    }

    @Override
    public boolean supports(EventDriver websocket)
    {
        return (websocket instanceof AbstractJsrEventDriver);
    }
}
