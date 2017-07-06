//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.SessionFactory;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

public class UntrustedWSSessionFactory implements SessionFactory
{
    interface Listener
    {
        void onSessionCreate(UntrustedWSSession session, URI requestURI);
    }
    
    private final static Logger LOG = Log.getLogger(UntrustedWSSessionFactory.class);
    
    private final WebSocketContainerScope containerScope;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    
    public UntrustedWSSessionFactory(WebSocketContainerScope containerScope)
    {
        this.containerScope = containerScope;
    }
    
    public boolean addListener(Listener listener)
    {
        return this.listeners.add(listener);
    }
    
    public boolean removeListener(Listener listener)
    {
        return this.listeners.remove(listener);
    }
    
    @Override
    public boolean supports(Object websocket)
    {
        return (websocket instanceof WebSocketConnectionListener) || (websocket.getClass().getAnnotation(WebSocket.class) != null);
    }
    
    @Override
    public WebSocketSession createSession(URI requestURI, Object websocket, LogicalConnection connection)
    {
        final UntrustedWSSession session = new UntrustedWSSession(containerScope, requestURI, websocket, connection);
        listeners.forEach((listener) -> {
            try
            {
                listener.onSessionCreate(session, requestURI);
            }
            catch (Throwable t)
            {
                LOG.warn("Unable to notify listener " + listener, t);
            }
        });
        return session;
    }
}
