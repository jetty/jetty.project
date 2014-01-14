//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------ */
/**
 * A {@link ContextHandlerCollection} handler may be used to direct a request to
 * a specific Context. The URI path prefix and optional virtual host is used to
 * select the context.
 * 
 */
public class SplitFileServer
{
        
    public static void main(String[] args) throws Exception
    {
        // Create the Server object and a corresponding ServerConnector and then set the port for the connector. In
        // this example the server will listen on port 8090. If you set this to port 0 then when the server has been
        // started you can called connector.getLocalPort() to programmatically get the port the server started on.
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8090);
        server.setConnectors(new Connector[]
        { connector });

        // Create a Context Handler and ResourceHandler. The ContextHandler is getting set to "/" path but this could
        // be anything you like for builing out your url. Note how we are setting the ResourceBase using our jetty
        // maven testing utilities to get the proper resource directory, you needn't use these,
        // you simply need to supply the paths you are looking to serve content from.
        ContextHandler context0 = new ContextHandler();
        context0.setContextPath("/");
        ResourceHandler rh0 = new ResourceHandler();
        rh0.setBaseResource( Resource.newResource(MavenTestingUtils.getTestResourceDir("dir0")));
        context0.setHandler(rh0);

        // Rinse and repeat the previous item, only specifying a different resource base.
        ContextHandler context1 = new ContextHandler();
        context1.setContextPath("/");   
        ResourceHandler rh1 = new ResourceHandler();
        rh1.setBaseResource( Resource.newResource(MavenTestingUtils.getTestResourceDir("dir1")));
        context1.setHandler(rh1);

        // Create a ContextHandlerCollection and set the context handlers to it. This will let jetty process urls
        // against the declared contexts in order to match up content.
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(new Handler[]
        { context0, context1 });

        server.setHandler(contexts);

        // Start things up! By using the server.join() the server thread will join with the current thread.
        // See "http://docs.oracle.com/javase/1.5.0/docs/api/java/lang/Thread.html#join()" for more details.
        server.start();
        System.err.println(server.dump());
        server.join();
    }
}
