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

package org.eclipse.jetty.demos;

import java.nio.file.Paths;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * A {@link ContextHandlerCollection} handler may be used to direct a request to
 * a specific Context. The URI path prefix and optional virtual host is used to
 * select the context.
 */
public class SplitFileServer
{
    public static Server createServer(int port, Resource baseResource0, Resource baseResource1)
    {
        // Create the Server object and a corresponding ServerConnector and then
        // set the port for the connector. In this example the server will
        // listen on port 8080. If you set this to port 0 then when the server
        // has been started you can called connector.getLocalPort() to
        // programmatically get the port the server started on.
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        // Create a Context Handler and ResourceHandler. The ContextHandler is
        // getting set to "/" path but this could be anything you like for
        // building out your url. Note how we are setting the ResourceBase using
        // our jetty maven testing utilities to get the proper resource
        // directory, you needn't use these, you simply need to supply the paths
        // you are looking to serve content from.
        ResourceHandler rh0 = new ResourceHandler();
        rh0.setDirectoriesListed(false);

        ContextHandler context0 = new ContextHandler();
        context0.setContextPath("/");
        context0.setBaseResource(baseResource0);
        context0.setHandler(rh0);

        // Rinse and repeat the previous item, only specifying a different
        // resource base.
        ResourceHandler rh1 = new ResourceHandler();
        rh1.setDirectoriesListed(false);

        ContextHandler context1 = new ContextHandler();
        context1.setContextPath("/");
        context1.setBaseResource(baseResource1);
        context1.setHandler(rh1);

        // Create a ContextHandlerCollection and set the context handlers to it.
        // This will let jetty process urls against the declared contexts in
        // order to match up content.
        ContextHandlerCollection contexts = new ContextHandlerCollection(
            context0, context1
        );
        server.setHandler(contexts);
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Resource resource0 = new PathResource(Paths.get("src/test/resources/dir0"));
        Resource resource1 = new PathResource(Paths.get("src/test/resources/dir1"));

        Server server = createServer(port, resource0, resource1);

        // Dump the server state
        server.setDumpAfterStart(true);

        // Start things up!
        server.start();

        // The use of server.join() the will make the current thread join and
        // wait until the server is done executing.
        server.join();
    }
}
