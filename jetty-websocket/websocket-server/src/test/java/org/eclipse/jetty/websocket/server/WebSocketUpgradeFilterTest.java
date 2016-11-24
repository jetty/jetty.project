//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.servlet.DispatcherType;

import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class WebSocketUpgradeFilterTest
{
    interface ServerProvider
    {
        Server newServer() throws Exception;
    }
    
    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> data()
    {
        /**
         * Case A:
         *   1. embedded-jetty WSUF.configureContext() / app-ws configured at ...
         *      a. during server construction / before server.start (might not be possible with current impl, native SCI not run (yet))
         *          might require NativeSCI.getDefaultFrom() first
         *      b. during server construction / after server.start
         *      c. during server start / via CustomServlet.init()
         *   2. embedded-jetty WSUF addFilter / app-ws configured at server construction (before server.start)
         * Case B:
         *   1. web.xml WSUF / app-ws configured in CustomServlet.init() load-on-start
         * Case C:
         *   1. embedded-jetty WSUF.configureContext() / app-ws configured via ServletContextListener.contextInitialized
         *   2. embedded-jetty WSUF addFilter / app-ws configured via ServletContextListener.contextInitialized
         * Case D:
         *   1. web.xml WSUF / app-ws configured via ServletContextListener.contextInitialized
         *
         * Every "app-ws configured" means it should access/set ws policy and add ws mappings
         */
        
        final WebSocketCreator infoCreator = new WebSocketCreator()
        {
            @Override
            public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
            {
                return new InfoSocket();
            }
        };
        
        List<Object[]> cases = new ArrayList<>();
        
        // Embedded WSUF.configureContext(), directly app-ws configuration
        
        cases.add(new Object[]{"wsuf.configureContext/Direct configure", new ServerProvider()
        {
            @Override
            public Server newServer() throws Exception
            {
                Server server = new Server();
                ServerConnector connector = new ServerConnector(server);
                connector.setPort(0);
                server.addConnector(connector);
                
                ServletContextHandler context = new ServletContextHandler();
                context.setContextPath("/");
                server.setHandler(context);
                
                WebSocketUpgradeFilter wsuf = WebSocketUpgradeFilter.configureContext(context);
                
                // direct configuration via WSUF
                wsuf.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
                wsuf.addMapping(new ServletPathSpec("/info/*"), infoCreator);
                
                server.start();
                return server;
            }
        }});
        
        // Embedded WSUF.configureContext(), apply app-ws configuration via attribute
        
        cases.add(new Object[]{"wsuf.configureContext/Attribute based configure", new ServerProvider()
        {
            @Override
            public Server newServer() throws Exception
            {
                Server server = new Server();
                ServerConnector connector = new ServerConnector(server);
                connector.setPort(0);
                server.addConnector(connector);
                
                ServletContextHandler context = new ServletContextHandler();
                context.setContextPath("/");
                server.setHandler(context);
                
                WebSocketUpgradeFilter.configureContext(context);
                
                // configuration via attribute
                NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration) context.getServletContext().getAttribute(NativeWebSocketConfiguration.class.getName());
                assertThat("NativeWebSocketConfiguration", configuration, notNullValue());
                configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
                configuration.addMapping(new ServletPathSpec("/info/*"), infoCreator);
                
                server.start();
                
                return server;
            }
        }});
        
        // Embedded WSUF, added as filter, apply app-ws configuration via attribute
        
        cases.add(new Object[]{"wsuf/addFilter/Attribute based configure", new ServerProvider()
        {
            @Override
            public Server newServer() throws Exception
            {
                Server server = new Server();
                ServerConnector connector = new ServerConnector(server);
                connector.setPort(0);
                server.addConnector(connector);
                
                ServletContextHandler context = new ServletContextHandler();
                context.setContextPath("/");
                server.setHandler(context);
                context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
                
                NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration();
                configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
                configuration.addMapping(new ServletPathSpec("/info/*"), infoCreator);
                context.getServletContext().setAttribute(NativeWebSocketConfiguration.class.getName(), configuration);
                
                server.start();
                
                return server;
            }
        }});
        
        // Embedded WSUF, added as filter, apply app-ws configuration via ServletContextListener
        
        cases.add(new Object[]{"wsuf.configureContext/ServletContextListener configure", new ServerProvider()
        {
            @Override
            public Server newServer() throws Exception
            {
                Server server = new Server();
                ServerConnector connector = new ServerConnector(server);
                connector.setPort(0);
                server.addConnector(connector);
                
                ServletContextHandler context = new ServletContextHandler();
                context.setContextPath("/");
                server.setHandler(context);
                context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
                context.addEventListener(new InfoContextListener());
                
                server.start();
                
                return server;
            }
        }});
        
        // WSUF from web.xml, SCI active, apply app-ws configuration via ServletContextListener
        
        cases.add(new Object[]{"wsuf/WebAppContext/web.xml/ServletContextListener", new ServerProvider()
        {
            @Override
            public Server newServer() throws Exception
            {
                File testDir = MavenTestingUtils.getTargetTestingDir("WSUF-webxml");
                
                WSServer server = new WSServer(testDir, "/");
                
                server.copyClass(InfoSocket.class);
                server.copyClass(InfoContextListener.class);
                server.copyWebInf("wsuf-config-via-listener.xml");
                server.start();
                
                WebAppContext webapp = server.createWebAppContext();
                server.deployWebapp(webapp);
                
                return server.getServer();
            }
        }});
        
        return cases;
    }
    
    private final Server server;
    private final URI serverUri;
    
    public WebSocketUpgradeFilterTest(String testId, ServerProvider serverProvider) throws Exception
    {
        this.server = serverProvider.newServer();
        
        ServerConnector connector = (ServerConnector) server.getConnectors()[0];
        
        // Establish the Server URI
        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        
        serverUri = new URI(String.format("ws://%s:%d/", host, port));
    }
    
    @Test
    public void testConfiguration() throws Exception
    {
        URI destUri = serverUri.resolve("/info/");
        
        try (BlockheadClient client = new BlockheadClient(destUri))
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();
            
            client.write(new TextFrame().setPayload("hello"));
            
            EventQueue<WebSocketFrame> frames = client.readFrames(1, 1000, TimeUnit.MILLISECONDS);
            String payload = frames.poll().getPayloadAsUTF8();
            
            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("payload", payload, containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }
    }
}
