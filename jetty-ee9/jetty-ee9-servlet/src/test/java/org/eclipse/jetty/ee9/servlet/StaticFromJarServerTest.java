//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.servlet;

import java.io.FileNotFoundException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class StaticFromJarServerTest
{
    private Server server;

    public HttpClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setTrustAll(true);

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        clientConnector.setSslContextFactory(sslContextFactory);

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");

        clientConnector.setExecutor(clientThreads);

        client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        Path jarFile = Paths.get("src/test/jars/content.jar");
        if (!Files.exists(jarFile))
            throw new FileNotFoundException(jarFile.toString());

        URI baseUri = URIUtil.toJarFileUri(jarFile.toUri());

        server = new Server(0);
        Resource baseResource = ResourceFactory.of(server).newResource(baseUri);

        ServletContextHandler context = new ServletContextHandler();
        context.setBaseResource(baseResource);
        ServletHolder defaultHolder = new ServletHolder("default", new DefaultServlet());
        context.addServlet(defaultHolder, "/");

        server.setHandler(new Handler.Collection(context.getCoreContextHandler(), new DefaultHandler()));
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testGetDir0Test0() throws Exception
    {
        URI uri = server.getURI().resolve("/dir0/test0.txt");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody, containsString("test0"));
    }

    @Test
    public void testGetDir0Listing() throws Exception
    {
        URI uri = server.getURI().resolve("/dir0/");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content has Listing", responseBody, containsString("<title>Directory: /dir0/</title>"));
        assertThat("Response Content has Listing", responseBody, containsString("<a href=\"/dir0/../\">"));
        assertThat("Response Content has Listing", responseBody, containsString("<a href=\"/dir0/test0.txt\">"));
    }

    @Test
    public void testGetDir1Test1() throws Exception
    {
        URI uri = server.getURI().resolve("/dir1/test1.txt");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody, containsString("test1"));
    }
}
