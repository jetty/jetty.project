//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.demos;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.SessionHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.NullSessionDataStore;
import org.eclipse.jetty.session.SessionCache;
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
        context.setResourceBase(baseResource.getPath());
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
