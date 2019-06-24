//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.server;

import javax.websocket.DeploymentException;
import javax.websocket.OnMessage;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PathParamTest
{
    private JavaxWebSocketServerContainer container;

    @BeforeEach
    public void startContainer() throws Exception
    {
        container = new DummyServerContainer();
        container.start();
    }

    @AfterEach
    public void stopContainer() throws Exception
    {
        container.stop();
    }

    @ServerEndpoint("/pathparam/basic/{name}")
    public static class BasicPathParamSocket
    {
        @OnMessage
        public void onMessage(String message, @PathParam("name") String name)
        {
        }
    }

    @Test
    public void testBasicPathParamSocket() throws DeploymentException
    {
        container.addEndpoint(BasicPathParamSocket.class);
    }
}
