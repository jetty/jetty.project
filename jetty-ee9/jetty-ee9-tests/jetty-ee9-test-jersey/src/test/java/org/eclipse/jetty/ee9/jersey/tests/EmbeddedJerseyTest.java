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

package org.eclipse.jetty.ee9.jersey.tests;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EmbeddedJerseyTest
{
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

    private Server server;
    private ServerConnector connector;
    private HttpClient httpClient;

    private void start() throws Exception
    {
        startServer();
        startClient();
    }

    private void startServer() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        ServletHolder servletHolder = new ServletHolder(org.glassfish.jersey.servlet.ServletContainer.class);
        servletHolder.setInitOrder(1);
        servletHolder.setInitParameter("jersey.config.server.provider.packages", "org.eclipse.jetty.ee9.jersey.tests.endpoints");
        context.addServlet(servletHolder, "/webapi/*");

        server.start();
    }

    private void startClient() throws Exception
    {
        httpClient = new HttpClient();
        httpClient.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(httpClient);
        LifeCycle.stop(server);
    }

    @Test
    public void testPutJSON() throws Exception
    {
        start();

        Request.Content content = new StringRequestContent("""
            {
                "principal" : "foo",
                "roles" : ["admin", "user"]
            }
            """
        );
        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.PUT)
            .path("/webapi/resource/security/")
            .headers(httpFields -> httpFields.put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString()))
            .body(content)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(200));
        assertThat(response.getHeaders().get(HttpHeader.CONTENT_TYPE), is(MimeTypes.Type.APPLICATION_JSON.asString()));
        assertThat(response.getContentAsString(), is("""
            {
                "response" : "ok"
            }
            """)
        );
    }

    @Test
    public void testPutNoJSONThenTimeout() throws Exception
    {
        start();
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        AsyncRequestContent content = new AsyncRequestContent();

        CountDownLatch responseLatch = new CountDownLatch(1);
        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(
            httpClient.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.PUT)
                .path("/webapi/resource/security/")
                .timeout(3 * idleTimeout, TimeUnit.SECONDS)
                .headers(httpFields -> httpFields.put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString()))
                .body(content)
                .onResponseSuccess(r -> responseLatch.countDown())
        ).send();

        // Do not add content to the request, the server should time out and send the response.
        assertTrue(responseLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Terminate the request too.
        content.close();

        ContentResponse response = completable.get(idleTimeout, TimeUnit.MILLISECONDS);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
    }
}
