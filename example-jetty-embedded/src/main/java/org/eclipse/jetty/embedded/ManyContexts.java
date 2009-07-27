// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.embedded;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;

/* ------------------------------------------------------------ */
/**
 * A {@link ContextHandlerCollection} handler may be used to direct a request to
 * a specific Context. The URI path prefix and optional virtual host is used to
 * select the context.
 * 
 */
public class ManyContexts
{
    public final static String BODY=
        "<a href='/'>root context</a><br/>"+
        "<a href='http://127.0.0.1:8080/context'>normal context</a><br/>"+
        "<a href='http://127.0.0.2:8080/context'>virtual context</a><br/>";
        
    public static void main(String[] args) throws Exception
    {
        Server server = new Server();
        Connector connector = new SelectChannelConnector();
        connector.setPort(8080);
        server.setConnectors(new Connector[]
        { connector });

        ContextHandler context0 = new ContextHandler();
        context0.setContextPath("/");
        Handler handler0 = new HelloHandler("Root Context",BODY);
        context0.setHandler(handler0);

        ContextHandler context1 = new ContextHandler();
        context1.setContextPath("/context");
        Handler handler1 = new HelloHandler("A Context",BODY);
        context1.setHandler(handler1);

        ContextHandler context2 = new ContextHandler();
        context2.setContextPath("/context");
        context2.setVirtualHosts(new String[]
        { "127.0.0.2" });
        Handler handler2 = new HelloHandler("A Virtual Context",BODY);
        context2.setHandler(handler2);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(new Handler[]
        { context0, context1, context2 });

        server.setHandler(contexts);

        server.start();
        System.err.println(server.dump());
        server.join();
    }

}
