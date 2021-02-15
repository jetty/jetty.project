//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@SuppressWarnings("unused")
@WebSocket
public class GetAuthHeaderEndpoint
{
    @OnWebSocketConnect
    public void onConnect(Session session) throws IOException
    {
        String authHeaderName = "Authorization";
        String authHeaderValue = session.getUpgradeRequest().getHeader(authHeaderName);
        session.getRemote().sendString("Header[" + authHeaderName + "]=" + authHeaderValue);
    }
}
