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

import org.eclipse.jetty.server.Server;

/**
 * The simplest possible Jetty server.
 */
// TODO: remove this class, only used in documentation.
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
