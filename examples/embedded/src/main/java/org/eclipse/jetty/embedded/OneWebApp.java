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

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;

public class OneWebApp
{
    public static Server createServer(int port)
    {
        // Create a basic jetty server object that will listen on port 8080.
        // Note that if you set this to port 0 then a randomly available port
        // will be assigned that you can either look in the logs for the port,
        // or programmatically obtain it for use in test cases.
        Server server = new Server(port);

        // The WebAppContext is the entity that controls the environment in
        // which a web application lives and breathes. In this example the
        // context path is being set to "/" so it is suitable for serving root
        // context requests and then we see it setting the location of the war.
        // A whole host of other configurations are available, ranging from
        // configuring to support annotation scanning in the webapp (through
        // PlusConfiguration) to choosing where the webapp will unpack itself.
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        File warFile = JettyDistribution.resolve("demo-base/webapps/async-rest.war").toFile();
        webapp.setWar(warFile.getAbsolutePath());

        // A WebAppContext is a ContextHandler as well so it needs to be set to
        // the server so it is aware of where to send the appropriate requests.
        server.setHandler(webapp);
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = createServer(port);

        // Start things up!
        server.start();

        server.dumpStdErr();

        // The use of server.join() the will make the current thread join and
        // wait until the server is done executing.
        server.join();
    }
}
