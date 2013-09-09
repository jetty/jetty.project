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

package org.eclipse.jetty.websocket.jsr356.demo;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

public class ExampleClient
{
    public static void main(String[] args)
    {
        try
        {
            new ExampleClient().run();
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }

    private void run() throws DeploymentException, IOException, URISyntaxException, InterruptedException
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        ExampleSocket socket = new ExampleSocket();
        URI uri = new URI("ws://echo.websocket.org/");
        Session session = container.connectToServer(socket,uri);
        socket.writeMessage("Hello");
        socket.messageLatch.await(1,TimeUnit.SECONDS); // give remote 1 second to respond
        session.close();
        socket.closeLatch.await(1,TimeUnit.SECONDS); // give remote 1 second to acknowledge response
        System.exit(0);
    }
}
