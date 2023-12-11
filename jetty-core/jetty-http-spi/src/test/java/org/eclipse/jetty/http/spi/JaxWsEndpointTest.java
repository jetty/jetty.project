//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http.spi;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

import jakarta.jws.WebMethod;
import jakarta.jws.WebService;
import jakarta.xml.ws.Endpoint;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class JaxWsEndpointTest
{
    private StacklessLogging stacklessLogging;
    private String urlPrefix;
    private Server server;
    private HttpClient client;

    @Test
    public void testPostToURLWithoutTrailingSlash() throws Exception
    {
        Endpoint.publish(urlPrefix + "/add", new AddService());

        ContentResponse response = client.newRequest(urlPrefix)
            .method(HttpMethod.POST)
            .path("/add")
            .send();

        assertThat(response.getStatus(), is(500));
        assertThat(response.getContentAsString(), containsString("Couldn't create SOAP message due to exception"));
    }

    @WebService
    public static class AddService
    {
        @WebMethod
        public int add(int a, int b)
        {
            return a + b;
        }
    }

    @BeforeEach
    public void setUp() throws Exception
    {
        LoggingUtil.init();
        stacklessLogging = new StacklessLogging(com.sun.xml.ws.transport.http.HttpAdapter.class);

        int port;
        try (ServerSocket serverSocket = new ServerSocket())
        {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("localhost", 0));
            port = serverSocket.getLocalPort();
            urlPrefix = "http://localhost:" + port;
        }

        server = new Server(new DelegatingThreadPool(new QueuedThreadPool()));
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);
        server.setHandler(new ContextHandlerCollection());
        server.start();

        JettyHttpServerProvider.setServer(server);
        System.setProperty("com.sun.net.httpserver.HttpServerProvider", JettyHttpServerProvider.class.getName());

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
        JettyHttpServerProvider.setServer(null);
        System.clearProperty("com.sun.net.httpserver.HttpServerProvider");
        stacklessLogging.close();
        LoggingUtil.end();
    }
}
