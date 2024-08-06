//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.server.http;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.Callback;

@SuppressWarnings("unused")
public class SessionHandlerDocs
{
    public void setupSession() throws Exception
    {
        // tag::session[]
        class MyAppHandler extends Handler.Abstract.NonBlocking
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Your web application implemented here.

                // You can access the HTTP session.
                Session session = request.getSession(false);

                return true;
            }
        }

        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create a ContextHandler with contextPath.
        ContextHandler contextHandler = new ContextHandler("/myApp");
        server.setHandler(contextHandler);

        // Create and link the SessionHandler.
        SessionHandler sessionHandler = new SessionHandler();
        contextHandler.setHandler(sessionHandler);

        // Link your web application Handler.
        sessionHandler.setHandler(new MyAppHandler());

        server.start();
        // end::session[]
    }
}
