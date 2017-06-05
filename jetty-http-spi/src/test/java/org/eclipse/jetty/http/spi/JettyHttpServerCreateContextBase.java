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

package org.eclipse.jetty.http.spi;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;

public class JettyHttpServerCreateContextBase
{

    protected JettyHttpServer jettyHttpServer;

    protected Server getContextHandlerServer()
    {
        // context handler default path is "/"
        Handler handler = new ContextHandler();
        Server server = new Server();
        server.setHandler(handler);
        return server;
    }

    protected Server getContextHandlerCollectionServer()
    {
        Handler handler = new ContextHandlerCollection();
        Server server = new Server();
        server.setHandler(handler);
        return server;
    }

    protected Server getContextHandlerCollectionsServer()
    {
        ContextHandlerCollection handler = new ContextHandlerCollection();
        Handler[] handles =
        { handler };
        HandlerCollection contextHandler = new HandlerCollection();
        contextHandler.setHandlers(handles);
        Server server = new Server();
        server.setHandler(contextHandler);
        return server;
    }

    protected void initializeJettyHttpServer(Server server)
    {
        jettyHttpServer = new JettyHttpServer(server,false);
    }
}
