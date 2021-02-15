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

package org.eclipse.jetty.embedded;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.session.DefaultSessionCache;
import org.eclipse.jetty.server.session.NullSessionDataStore;
import org.eclipse.jetty.server.session.SessionCache;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;

public class OneServletContextWithSession
{
    public static Server createServer(int port, Resource baseResource)
    {
        Server server = new Server(port);

        // Create a ServletContext, with a session handler enabled.
        ServletContextHandler context = new ServletContextHandler(
            ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.setBaseResource(baseResource);
        server.setHandler(context);

        // Access the SessionHandler from the context.
        SessionHandler sessions = context.getSessionHandler();

        // Explicitly set Session Cache and null Datastore.
        // This is normally done by default,
        // but is done explicitly here for demonstration.
        // If more than one context is to be deployed, it is
        // simpler to use SessionCacheFactory and/or
        // SessionDataStoreFactory instances set as beans on 
        // the server.
        SessionCache cache = new DefaultSessionCache(sessions);
        cache.setSessionDataStore(new NullSessionDataStore());
        sessions.setSessionCache(cache);

        // Servlet to read/set the greeting stored in the session.
        // Can be accessed using http://localhost:8080/hello
        context.addServlet(HelloSessionServlet.class, "/");
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Path dir = Paths.get(System.getProperty("user.dir"));
        PathResource baseResource = new PathResource(dir);
        Server server = createServer(port, baseResource);

        server.start();
        server.dumpStdErr();
        server.join();
    }
}
