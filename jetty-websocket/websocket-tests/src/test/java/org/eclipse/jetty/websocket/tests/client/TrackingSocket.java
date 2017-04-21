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

package org.eclipse.jetty.websocket.tests.client;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.hamcrest.Matcher;

@WebSocket
public class TrackingSocket
{
    private Session session;
    
    public void assertClose(int expectedStatusCode, Matcher<String> reasonMatcher)
    {
    }
    
    public Session getSession()
    {
        return session;
    }
    
    @OnWebSocketConnect
    public void onOpen(Session session)
    {
        this.session = session;
    }
    
    public void waitForClose(int timeout, TimeUnit unit)
    {
    }
    
    public void waitForConnected(int timeout, TimeUnit unit)
    {
    }
}
