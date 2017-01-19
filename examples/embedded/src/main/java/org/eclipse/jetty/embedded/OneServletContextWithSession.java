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

        // Create an ID manager for the server.  This is normally done
        // by default, but is done explicitly here for demonstration.
        AbstractSessionIdManager idManager = new HashSessionIdManager();
        server.setSessionIdManager(idManager);

        // Create a ServletContext, with a session handler enabled.
        ServletContextHandler context = new ServletContextHandler(
                ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.setResourceBase(System.getProperty("java.io.tmpdir"));
        server.setHandler(context);

        // Access the SessionHandler from the context.
        SessionHandler sessions = context.getSessionHandler();
        
        // Set a SessionManager.  This is normally done by default,
        // but is done explicitly here for demonstration.
        sessions.setSessionManager(new HashSessionManager());
        

        //Servlet to read/set the greeting stored in the session.
        //Can be accessed using http://localhost:8080/hello
        context.addServlet(HelloSessionServlet.class, "/");

        server.start();
        server.join();
    }
}
