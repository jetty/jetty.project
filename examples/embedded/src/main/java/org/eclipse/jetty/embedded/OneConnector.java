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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * A Jetty server with one connectors.
 */
public class OneConnector
{
    public static Server createServer(int port) throws Exception
    {
        // The Server
        Server server = new Server();

        // HTTP connector
        ServerConnector http = new ServerConnector(server);
        http.setHost("localhost");
        http.setPort(port);
        http.setIdleTimeout(30000);

        // Set the connector
        server.addConnector(http);

        // Set a handler
        server.setHandler(new HelloHandler());
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = createServer(port);

        // Start the server
        server.start();
        server.join();
    }
}
