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
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
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
        final WebSocketCreator infoCreator = (req, resp) -> new InfoSocket();
        
        List<Object[]> cases = new ArrayList<>();
        
        // Embedded WSUF.configureContext(), directly app-ws configuration
        
        cases.add(new Object[]{"wsuf.configureContext/Direct configure", (ServerProvider) () ->
        {
            Server server1 = new Server();
            ServerConnector connector = new ServerConnector(server1);
            connector.setPort(0);
            server1.addConnector(connector);
            
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server1.setHandler(context);
            
            WebSocketUpgradeFilter wsuf = WebSocketUpgradeFilter.configureContext(context);
            
            // direct configuration via WSUF
            wsuf.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            wsuf.addMapping(new ServletPathSpec("/info/*"), infoCreator);
            
            server1.start();
            return server1;
        }});
        
        // Embedded WSUF.configureContext(), apply app-ws configuration via attribute
        
        cases.add(new Object[]{"wsuf.configureContext/Attribute based configure", (ServerProvider) () ->
        {
            Server server12 = new Server();
            ServerConnector connector = new ServerConnector(server12);
            connector.setPort(0);
            server12.addConnector(connector);
            
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server12.setHandler(context);
            
            WebSocketUpgradeFilter.configureContext(context);
            
            // configuration via attribute
            NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration) context.getServletContext().getAttribute(NativeWebSocketConfiguration.class.getName());
            assertThat("NativeWebSocketConfiguration", configuration, notNullValue());
            configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            configuration.addMapping(new ServletPathSpec("/info/*"), infoCreator);
            
            server12.start();
            
            return server12;
        }});
        
        // Embedded WSUF, added as filter, apply app-ws configuration via attribute
        
        cases.add(new Object[]{"wsuf/addFilter/Attribute based configure", (ServerProvider) () ->
        {
            Server server13 = new Server();
            ServerConnector connector = new ServerConnector(server13);
            connector.setPort(0);
            server13.addConnector(connector);
            
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server13.setHandler(context);
            context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
            
            NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(context.getServletContext());
            configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            configuration.addMapping(new ServletPathSpec("/info/*"), infoCreator);
            context.setAttribute(NativeWebSocketConfiguration.class.getName(), configuration);
            
            server13.start();
            
            return server13;
        }});
    
        // Embedded WSUF, added as filter, apply app-ws configuration via wsuf constructor
    
        cases.add(new Object[]{"wsuf/addFilter/WSUF Constructor configure", new ServerProvider()
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
            
                NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(context.getServletContext());
                configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
                configuration.addMapping(new ServletPathSpec("/info/*"), infoCreator);
                context.addBean(configuration, true);
            
                FilterHolder wsufHolder = new FilterHolder(new WebSocketUpgradeFilter(configuration));
                context.addFilter(wsufHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
            
                server.start();
            
                return server;
            }
        }});

        // Embedded WSUF, added as filter, apply app-ws configuration via ServletContextListener
        
        cases.add(new Object[]{"wsuf.configureContext/ServletContextListener configure", (ServerProvider) () ->
        {
            Server server14 = new Server();
            ServerConnector connector = new ServerConnector(server14);
            connector.setPort(0);
            server14.addConnector(connector);
            
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server14.setHandler(context);
            context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
            context.addEventListener(new InfoContextListener());
            
            server14.start();
            
            return server14;
        }});
        
        // WSUF from web.xml, SCI active, apply app-ws configuration via ServletContextListener
        
        cases.add(new Object[]{"wsuf/WebAppContext/web.xml/ServletContextListener", (ServerProvider) () ->
        {
            File testDir = MavenTestingUtils.getTargetTestingDir("WSUF-webxml");
            
            WSServer server15 = new WSServer(testDir, "/");

            server15.copyWebInf("wsuf-config-via-listener.xml");
            server15.copyClass(InfoSocket.class);
            server15.copyClass(InfoContextAttributeListener.class);
            server15.start();
            
            WebAppContext webapp = server15.createWebAppContext();
            server15.deployWebapp(webapp);
            
            return server15.getServer();
        }});
    
        // WSUF from web.xml, SCI active, apply app-ws configuration via Servlet.init
    
        cases.add(new Object[]{"wsuf/WebAppContext/web.xml/Servlet.init", (ServerProvider) () ->
        {
            File testDir = MavenTestingUtils.getTargetTestingDir("WSUF-webxml");
        
            WSServer server16 = new WSServer(testDir, "/");

            server16.copyWebInf("wsuf-config-via-servlet-init.xml");
            server16.copyClass(InfoSocket.class);
            server16.copyClass(InfoServlet.class);
            server16.start();
        
            WebAppContext webapp = server16.createWebAppContext();
            server16.deployWebapp(webapp);
        
            return server16.getServer();
        }});
        
        // xml based, wsuf, on alternate url-pattern and config attribute location

        cases.add(new Object[]{"wsuf/WebAppContext/web.xml/ServletContextListener/alt-config", (ServerProvider) () ->
        {
            File testDir = MavenTestingUtils.getTargetTestingDir("WSUF-webxml");
        
            WSServer server17 = new WSServer(testDir, "/");

            server17.copyWebInf("wsuf-alt-config-via-listener.xml");
            server17.copyClass(InfoSocket.class);
            server17.copyClass(InfoContextAltAttributeListener.class);
            server17.start();
        
            WebAppContext webapp = server17.createWebAppContext();
            server17.deployWebapp(webapp);
        
            return server17.getServer();
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
    public void testNormalConfiguration() throws Exception
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
    
    @Test
    public void testStopStartOfHandler() throws Exception
    {
        URI destUri = serverUri.resolve("/info/");
        
        try (BlockheadClient client = new BlockheadClient(destUri))
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();
            
            client.write(new TextFrame().setPayload("hello 1"));
            
            EventQueue<WebSocketFrame> frames = client.readFrames(1, 1000, TimeUnit.MILLISECONDS);
            String payload = frames.poll().getPayloadAsUTF8();
            
            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("payload", payload, containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }
        
        server.getHandler().stop();
        server.getHandler().start();
        
        try (BlockheadClient client = new BlockheadClient(destUri))
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();
        
            client.write(new TextFrame().setPayload("hello 2"));
        
            EventQueue<WebSocketFrame> frames = client.readFrames(1, 1000, TimeUnit.MILLISECONDS);
            String payload = frames.poll().getPayloadAsUTF8();
        
            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("payload", payload, containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }
    }
}
