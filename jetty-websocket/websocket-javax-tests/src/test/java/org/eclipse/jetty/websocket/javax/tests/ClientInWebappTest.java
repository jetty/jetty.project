//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.javax.client.internal.JavaxWebSocketClientContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ClientInWebappTest
{
    private Server server;
    private ServletContextHandler contextHandler;
    private URI serverUri;
    private HttpClient httpClient;
    private volatile WebSocketContainer container;

    public class WebSocketClientInServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            container = ContainerProvider.getWebSocketContainer();
        }
    }

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);

        contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        contextHandler.addServlet(new ServletHolder(new WebSocketClientInServlet()), "/");
        server.setHandler(contextHandler);
        server.start();
        serverUri = WSURI.toWebsocket(server.getURI());

        httpClient = new HttpClient();
        httpClient.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        httpClient.stop();
        server.stop();
    }

    @Test
    public void testWebSocketClientContainerInWebapp() throws Exception
    {
        ContentResponse response = httpClient.GET(serverUri);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        assertNotNull(container);
        assertThat(container, instanceOf(JavaxWebSocketClientContainer.class));
        JavaxWebSocketClientContainer clientContainer = (JavaxWebSocketClientContainer)container;
        assertThat(clientContainer.isRunning(), is(true));

        // The container should be a bean on the ContextHandler.
        Collection<WebSocketContainer> containedBeans = contextHandler.getBeans(WebSocketContainer.class);
        assertThat(containedBeans.size(), is(1));
        assertThat(containedBeans.toArray()[0], is(container));

        // The client should be attached to the servers LifeCycle and should stop with it.
        server.stop();
        assertThat(clientContainer.isRunning(), is(false));
        assertThat(server.getContainedBeans(WebSocketContainer.class), empty());
    }
}
