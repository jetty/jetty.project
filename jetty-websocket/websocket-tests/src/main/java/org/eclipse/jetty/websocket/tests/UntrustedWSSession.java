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

import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.core.WebSocketSession;
import org.eclipse.jetty.websocket.core.io.WSConnection;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

public class UntrustedWSSession extends WebSocketSession implements AutoCloseable
{
    private final UntrustedWSConnection untrustedConnection;
    private final UntrustedWSEndpoint untrustedEndpoint;
    
    public UntrustedWSSession(WebSocketContainerScope containerScope, URI requestURI, Object endpoint, LogicalConnection connection)
    {
        super(containerScope, requestURI, endpoint, connection);
        WSConnection abstractWebSocketConnection = (WSConnection) connection;
        abstractWebSocketConnection.getGenerator().setValidating(false);
        this.untrustedConnection = new UntrustedWSConnection(abstractWebSocketConnection);
        this.untrustedEndpoint = (UntrustedWSEndpoint) endpoint;
    }
    
    public UntrustedWSConnection getUntrustedConnection()
    {
        return untrustedConnection;
    }
    
    public UntrustedWSEndpoint getUntrustedEndpoint()
    {
        return untrustedEndpoint;
    }
}
