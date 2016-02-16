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

package org.eclipse.jetty.websocket.server.misbehaving;

import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

@SuppressWarnings("serial")
public class BadSocketsServlet extends WebSocketServlet implements WebSocketCreator
{
    public ListenerRuntimeOnConnectSocket listenerRuntimeConnect;
    public AnnotatedRuntimeOnConnectSocket annotatedRuntimeConnect;

    @Override
    public void configure(WebSocketServletFactory factory)
    {
        factory.setCreator(this);

        this.listenerRuntimeConnect = new ListenerRuntimeOnConnectSocket();
        this.annotatedRuntimeConnect = new AnnotatedRuntimeOnConnectSocket();
    }

    @Override
    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
    {
        if (req.hasSubProtocol("listener-runtime-connect"))
        {
            return this.listenerRuntimeConnect;
        }
        else if (req.hasSubProtocol("annotated-runtime-connect"))
        {
            return this.annotatedRuntimeConnect;
        }

        return null;
    }
}
