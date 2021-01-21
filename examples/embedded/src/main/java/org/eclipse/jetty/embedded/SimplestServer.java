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

/**
 * The simplest possible Jetty server.
 */
public class SimplestServer
{
    public static Server createServer(int port)
    {
        Server server = new Server(port);
        // This has a connector listening on port specified
        // and no handlers, meaning all requests will result
        // in a 404 response
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = createServer(port);
        server.start();
        server.join();
    }
}
