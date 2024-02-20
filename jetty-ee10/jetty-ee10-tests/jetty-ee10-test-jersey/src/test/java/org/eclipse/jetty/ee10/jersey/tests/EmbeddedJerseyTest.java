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

package org.eclipse.jetty.ee10.jersey.tests;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class EmbeddedJerseyTest
{
    private static Server server;
    private static HttpClient httpClient;
    private static int serverPort;

    @BeforeAll
    public static void beforeAll()
    {
        // Wire up java.util.logging to slf4j.
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
    }

    @AfterAll
    public static void afterAll()
    {
        org.slf4j.bridge.SLF4JBridgeHandler.uninstall();
    }

    private static void startClient() throws Exception
    {
        httpClient = new HttpClient();
        httpClient.start();
    }

    private static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        connector.setIdleTimeout(3000);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        ServletHolder servletHolder = new ServletHolder(org.glassfish.jersey.servlet.ServletContainer.class);
        servletHolder.setInitOrder(1);
        servletHolder.setInitParameter("jersey.config.server.provider.packages", "org.eclipse.jetty.ee10.jersey.tests.endpoints");
        context.addServlet(servletHolder, "/webapi/*");

        server.start();
        serverPort = connector.getLocalPort();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(server);
        LifeCycle.stop(httpClient);
    }

    @Test
    public void testPutJson() throws Exception
    {
        startServer();
        startClient();

        Request.Content content = new StringRequestContent("""
            {
                "principal" : "foo",
                "roles" : ["admin", "user"]
            }"""
        );
        ContentResponse response = httpClient.newRequest("localhost", serverPort)
            .path("/webapi/myresource/security/")
            .method(HttpMethod.PUT)
            .headers(httpFields -> httpFields.put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString()))
            .body(content)
            .send();

        assertThat(response.getStatus(), is(200));
        assertThat(response.getHeaders().get(HttpHeader.CONTENT_TYPE), is(MimeTypes.Type.APPLICATION_JSON.asString()));
        assertThat(response.getContentAsString(), is("""
            {
                "response" : "ok"
            }"""));
    }

    @Test
    public void testPutJsonTimeout() throws Exception
    {
        startServer();
        startClient();

        AsyncRequestContent content = new AsyncRequestContent();

        ContentResponse response = httpClient.newRequest("localhost", serverPort)
            .path("/webapi/myresource/security/")
            .idleTimeout(5, TimeUnit.SECONDS)
            .method(HttpMethod.PUT)
            .headers(httpFields -> httpFields.put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString()))
            .body(content)
            .send();
    }
}
