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

package org.eclipse.jetty.embedded;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.session.AbstractSessionIdManager;
import org.eclipse.jetty.server.session.HashSessionIdManager;
import org.eclipse.jetty.server.session.HashSessionManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;


public class OneServletContextWithSession
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server(8080);

        SessionHandler handler = new SessionHandler();
        handler.setServer(server);

        HashSessionManager manager = new HashSessionManager();
        handler.setSessionManager(manager);

        AbstractSessionIdManager idManager = new HashSessionIdManager();
        manager.setSessionIdManager(idManager);
        server.setSessionIdManager(idManager);

        ServletContextHandler context = new ServletContextHandler(
                ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.setResourceBase(System.getProperty("java.io.tmpdir"));
        server.setHandler(context);

        //Servlet to store a different greeting in the session
        //Can be accessed using http://localhost:8080/store
        context.addServlet(StoreInSessionServlet.class, "/store/*");

        //Servlet to read the greeting stored in the session, if you have not visited the
        //StoreInSessionServlet a default greeting is shown, after visiting you will see
        //a different greeting.
        //Can be accessed using http://localhost:8080/hello
        context.addServlet(HelloSessionServlet.class, "/");

        server.start();
        server.join();
    }
}
