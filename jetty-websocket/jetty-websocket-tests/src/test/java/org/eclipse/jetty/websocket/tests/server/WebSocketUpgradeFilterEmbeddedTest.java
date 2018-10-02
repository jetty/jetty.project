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

import java.util.EnumSet;
import java.util.stream.Stream;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.servlet.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

public class WebSocketUpgradeFilterEmbeddedTest extends WebSocketUpgradeFilterTest
{
    private interface Case
    {
        void customize(ServletContextHandler context) throws Exception;
    }
    
    public static Stream<Arguments> data()
    {
        final WebSocketCreator infoCreator = (req, resp) -> new InfoSocket();
        
        return Stream.of(
            // Embedded WSUF.configureContext(), directly app-ws configuration

            Arguments.of("wsuf.configureContext/Direct configure", (Case) (context) ->
                    {
                        WebSocketUpgradeFilter wsuf = WebSocketUpgradeFilter.configureContext(context);
                        // direct configuration via WSUF
                        wsuf.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
                        wsuf.addMapping("/info/*", infoCreator);
                    }),

            // Embedded WSUF.configureContext(), apply app-ws configuration via attribute

            Arguments.of("wsuf.configureContext/Attribute based configure", (Case) (context) ->
            {
                WebSocketUpgradeFilter.configureContext(context);

                // configuration via attribute
                NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration) context.getServletContext().getAttribute(NativeWebSocketConfiguration.class.getName());
                assertThat("NativeWebSocketConfiguration", configuration, notNullValue());
                configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
                configuration.addMapping("/info/*", infoCreator);
            }),

            // Embedded WSUF, added as filter, apply app-ws configuration via attribute

            Arguments.of("wsuf/addFilter/Attribute based configure", (Case) (context) ->
            {
                context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

                NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(context.getServletContext());
                configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
                configuration.addMapping("/info/*", infoCreator);
                context.setAttribute(NativeWebSocketConfiguration.class.getName(), configuration);
            }),

            // Embedded WSUF, added as filter, apply app-ws configuration via wsuf constructor

            Arguments.of("wsuf/addFilter/WSUF Constructor configure", (Case) (context) ->
            {
                NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(context.getServletContext());
                configuration.getFactory().getPolicy().setMaxTextMessageSize(10 * 1024 * 1024);
                configuration.addMapping("/info/*", infoCreator);
                context.addBean(configuration, true);

                FilterHolder wsufHolder = new FilterHolder(new WebSocketUpgradeFilter(configuration));
                context.addFilter(wsufHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
            }),

            // Embedded WSUF, added as filter, apply app-ws configuration via ServletContextListener

            Arguments.of("wsuf.configureContext/ServletContextListener configure", (Case) (context) ->
            {
                context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
                context.addEventListener(new InfoContextListener());
            })
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testNormalConfiguration(String testid, Case testcase) throws Exception
    {
        super.testNormalConfiguration(newServer(testcase));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testStopStartOfHandler(String testid, Case testcase) throws Exception
    {
        super.testStopStartOfHandler(newServer(testcase));
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
