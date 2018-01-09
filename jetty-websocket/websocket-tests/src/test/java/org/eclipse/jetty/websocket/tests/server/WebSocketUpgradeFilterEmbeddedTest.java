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

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import javax.servlet.DispatcherType;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.server.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class WebSocketUpgradeFilterEmbeddedTest extends WebSocketUpgradeFilterTest
{
    private interface Case
    {
        void customize(ServletContextHandler context) throws Exception;
    }
    
    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> data()
    {
        final WebSocketCreator infoCreator = (req, resp) -> new InfoSocket();
        
        List<Object[]> cases = new ArrayList<>();
        
        // Embedded WSUF.configureContext(), directly app-ws configuration
        
        cases.add(new Object[]
                {"wsuf.configureContext/Direct configure", (Case) (context) ->
                {
                    WebSocketUpgradeFilter wsuf = WebSocketUpgradeFilter.configureContext(context);
                    // direct configuration via WSUF
                    wsuf.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
                    wsuf.addMapping("/info/*", infoCreator);
                }});
        
        // Embedded WSUF.configureContext(), apply app-ws configuration via attribute
        
        cases.add(new Object[]{
                "wsuf.configureContext/Attribute based configure", (Case) (context) ->
        {
            WebSocketUpgradeFilter.configureContext(context);
            
            // configuration via attribute
            NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration) context.getServletContext().getAttribute(NativeWebSocketConfiguration.class.getName());
            assertThat("NativeWebSocketConfiguration", configuration, notNullValue());
            configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            configuration.addMapping("/info/*", infoCreator);
        }});
        
        // Embedded WSUF, added as filter, apply app-ws configuration via attribute
        
        cases.add(new Object[]{
                "wsuf/addFilter/Attribute based configure", (Case) (context) ->
        {
            context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
            
            NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(context.getServletContext());
            configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            configuration.addMapping("/info/*", infoCreator);
            context.setAttribute(NativeWebSocketConfiguration.class.getName(), configuration);
        }});
        
        // Embedded WSUF, added as filter, apply app-ws configuration via wsuf constructor
        
        cases.add(new Object[]{
                "wsuf/addFilter/WSUF Constructor configure", (Case) (context) ->
        {
            NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(context.getServletContext());
            configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
            configuration.addMapping("/info/*", infoCreator);
            context.addBean(configuration, true);
            
            FilterHolder wsufHolder = new FilterHolder(new WebSocketUpgradeFilter(configuration));
            context.addFilter(wsufHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
        }});
        
        // Embedded WSUF, added as filter, apply app-ws configuration via ServletContextListener
        
        cases.add(new Object[]{
                "wsuf.configureContext/ServletContextListener configure", (Case) (context) ->
        {
            context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
            context.addEventListener(new InfoContextListener());
        }});
        
        return cases;
    }
    
    public WebSocketUpgradeFilterEmbeddedTest(String testid, Case testcase) throws Exception
    {
        super(newServer(testcase));
    }
    
    private static LocalServer newServer(Case testcase)
    {
        return new LocalServer()
        {
            @Override
            protected void configureServletContextHandler(ServletContextHandler context) throws Exception
            {
                testcase.customize(context);
            }
        };
    }
}
