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

package org.eclipse.jetty.websocket.common;

import java.net.URI;

import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.JettyAnnotatedEventDriver;
import org.eclipse.jetty.websocket.common.events.JettyListenerEventDriver;

/**
 * Default Session factory, creating WebSocketSession objects.
 */
public class WebSocketSessionFactory implements SessionFactory
{
    private final SessionListener[] listeners;

    public WebSocketSessionFactory(SessionListener... sessionListeners)
    {
        listeners = sessionListeners;
    }

    @Override
    public boolean supports(EventDriver websocket)
    {
        return (websocket instanceof JettyAnnotatedEventDriver) || (websocket instanceof JettyListenerEventDriver);
    }

    @Override
    public WebSocketSession createSession(URI requestURI, EventDriver websocket, LogicalConnection connection)
    {
        return new WebSocketSession(requestURI,websocket,connection,listeners);
    }
}
