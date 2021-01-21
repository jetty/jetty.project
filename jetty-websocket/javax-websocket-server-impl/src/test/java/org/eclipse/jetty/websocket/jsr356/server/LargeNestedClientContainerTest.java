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

package org.eclipse.jetty.websocket.jsr356.server;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.jsr356.JettyClientContainerProvider;
import org.eclipse.jetty.websocket.jsr356.server.samples.echo.LargeEchoAnnotatedSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Test send of large messages from within a Server using a ClientContainer
 */
public class LargeNestedClientContainerTest
{
    public abstract static class WSServerConfig
    {
        private final String description;

        public WSServerConfig(String description)
        {
            this.description = description;
        }

        public abstract void configure(WSServer server) throws Exception;

        @Override
        public String toString()
        {
            return this.description;
        }
    }

    public static List<WSServerConfig[]> usecases()
    {
        List<WSServerConfig[]> scenarios = new ArrayList<>();

        scenarios.add(new WSServerConfig[]{
            new WSServerConfig("Servlet using ContainerProvider.getWebSocketContainer() (default)")
            {
                @Override
                public void configure(WSServer server) throws Exception
                {
                    server.copyWebInf("large-client-container-servlet-web.xml");
                    server.copyClass(LargeClientContainerServlet.class);
                    server.copyEndpoint(LargeEchoAnnotatedSocket.class);
                }
            }
        });

        scenarios.add(new WSServerConfig[]{
            new WSServerConfig("Servlet using ContainerProvider.getWebSocketContainer() (init / server-container)")
            {
                @Override
                public void configure(WSServer server) throws Exception
                {
                    server.copyWebInf("large-client-container-servlet-init-use-server-web.xml");
                    server.copyClass(LargeClientContainerInitAsServerListener.class);
                    server.copyClass(LargeClientContainerServlet.class);
                    server.copyEndpoint(LargeEchoAnnotatedSocket.class);
                }
            }
        });

        scenarios.add(new WSServerConfig[]{
            new WSServerConfig("Servlet using ServerContainer as ClientContainer")
            {
                @Override
                public void configure(WSServer server) throws Exception
                {
                    server.copyWebInf("large-client-container-servlet-web.xml");
                    server.copyClass(LargeServerContainerAsClientContainerServlet.class);
                    server.copyEndpoint(LargeEchoAnnotatedSocket.class);
                }
            }
        });

        return scenarios;
    }

    @AfterEach
    public void resetClientContainerProvider()
    {
        JettyClientContainerProvider.useServerContainer(false);
        JettyClientContainerProvider.useSingleton(false);
    }

    private static final AtomicInteger appDirIdx = new AtomicInteger(0);

    @ParameterizedTest
    @MethodSource("usecases")
    public void testLargeEcho(WSServerConfig serverConfig) throws Exception
    {
        Path testDir = MavenTestingUtils.getTargetTestingPath(LargeNestedClientContainerTest.class.getSimpleName() + "-" + appDirIdx.getAndIncrement());

        WSServer server = new WSServer(testDir, "app");
        server.createWebInf();

        serverConfig.configure(server);

        try
        {
            server.start();

            WebAppContext webapp = server.createWebAppContext();
            server.deployWebapp(webapp);

            // server.dump();

            HttpClient client = new HttpClient();
            try
            {
                client.start();

                URI destUri = server.getServerBaseURI().resolve("/app/echo/large");
                String destUrl = URLEncoder.encode(destUri.toASCIIString(), "utf-8");

                WebSocketPolicy defaultPolicy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
                int defaultTextSize = defaultPolicy.getMaxTextMessageSize();

                URI uri = server.getServer().getURI().resolve("/app/echo/servlet?size=" + (defaultTextSize * 2) + "&destUrl=" + destUrl);

                ContentResponse response = client.GET(uri);

                assertThat("Response.status", response.getStatus(), is(200));
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            server.stop();
        }
    }
}
