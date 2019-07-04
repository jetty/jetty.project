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

package org.eclipse.jetty.embedded;

import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.javax.server.JavaxWebSocketServletContainerInitializer;

/**
 * Example of setting up a javax.websocket server with Jetty embedded
 */
public class WebSocketJsrServer
{
    /**
     * A server socket endpoint
     */
    @ServerEndpoint(value = "/echo")
    public static class EchoJsrSocket
    {
        @OnMessage
        public void onMessage(Session session, String message)
        {
            session.getAsyncRemote().sendText(message);
        }
    }

    public static void main(String[] args) throws Exception
    {
        final Server server = new Server(8080);

        HandlerList handlers = new HandlerList();

        ServletContextHandler contextHandler = new ServletContextHandler(
            ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        handlers.addHandler(contextHandler);
        handlers.addHandler(new DefaultHandler());
        server.setHandler(handlers);

        // Enable javax.websocket configuration for the context
        JavaxWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addEndpoint(EchoJsrSocket.class));

        server.start();
        contextHandler.dumpStdErr();
        server.join();
    }
}
