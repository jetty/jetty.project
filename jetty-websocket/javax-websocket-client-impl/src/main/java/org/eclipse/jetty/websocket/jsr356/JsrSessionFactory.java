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

package org.eclipse.jetty.websocket.jsr356;

import java.net.URI;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.SessionFactory;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.jsr356.endpoints.AbstractJsrEventDriver;

public class JsrSessionFactory implements SessionFactory
{
    private static final Logger LOG = Log.getLogger(JsrSessionFactory.class);
    private final ClientContainer container;

    public JsrSessionFactory(ClientContainer container)
    {
        if(LOG.isDebugEnabled()) {
            LOG.debug("Container: {}", container);
        }
        this.container = container;
    }

    @Override
    public WebSocketSession createSession(URI requestURI, EventDriver websocket, LogicalConnection connection)
    {
        return new JsrSession(container,connection.getId(),requestURI,websocket,connection);
    }

    @Override
    public boolean supports(EventDriver websocket)
    {
        return (websocket instanceof AbstractJsrEventDriver);
    }
}
