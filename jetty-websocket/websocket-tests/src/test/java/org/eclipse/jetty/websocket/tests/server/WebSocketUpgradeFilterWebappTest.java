//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.server;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.tests.WSServer;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class WebSocketUpgradeFilterWebappTest extends WebSocketUpgradeFilterTest
{
    private interface Case
    {
        void customize(WSServer server) throws Exception;
    }
    
    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> data()
    {
        List<Object[]> cases = new ArrayList<>();
        
        // WSUF from web.xml, SCI active, apply app-ws configuration via ServletContextListener
        
        cases.add(new Object[]{
                "From ServletContextListener",
                (Case) (server) ->
                {
                    server.copyWebInf("wsuf-config-via-listener.xml");
                    server.copyClass(InfoSocket.class);
                    server.copyClass(InfoContextAttributeListener.class);
                }
        });
        
        // WSUF from web.xml, SCI active, apply app-ws configuration via ServletContextListener with WEB-INF/lib/jetty-http.jar
        
        cases.add(new Object[]{
                "From ServletContextListener with jar scanning",
                (Case) (server) ->
                {
                    server.copyWebInf("wsuf-config-via-listener.xml");
                    server.copyClass(InfoSocket.class);
                    server.copyClass(InfoContextAttributeListener.class);
                    // Add a jetty-http.jar to ensure that the classloader constraints
                    // and the WebAppClassloader setup is sane and correct
                    // The odd version string is present to capture bad regex behavior in Jetty
                    server.copyLib(org.eclipse.jetty.http.pathmap.PathSpec.class, "jetty-http-9.99.999.jar");
                }
        });
        
        return cases;
    }
    
    public WebSocketUpgradeFilterWebappTest(String testid, Case testcase) throws Exception
    {
        super(newServer(testcase));
    }
    
    private static WSServer newServer(Case testcase)
    {
        return new WSServer(getNewTestDir(), "")
        {
            private WebAppContext webapp;
            
            @Override
            protected Handler createRootHandler(Server server) throws Exception
            {
                Handler handler = super.createRootHandler(server);
                testcase.customize(this);
                return handler;
            }
            
            @Override
            public ServletContextHandler getServletContextHandler()
            {
                return this.webapp;
            }
            
            @Override
            protected void doStart() throws Exception
            {
                super.doStart();
                
                this.webapp = createWebAppContext();
                deployWebapp(webapp);
            }
        };
    }
}
