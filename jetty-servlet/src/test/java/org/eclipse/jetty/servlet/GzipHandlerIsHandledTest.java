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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Tests of behavior of GzipHandler when Request.isHandled() or Response.isCommitted() is true
 */
public class GzipHandlerIsHandledTest
{
    public WorkDir workDir;

    private Server server;
    private HttpClient client;
    public LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(new PathResource(workDir.getPath()));
        resourceHandler.setDirectoriesListed(true);
        resourceHandler.setHandler(new EventHandler(events, "ResourceHandler"));

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setMinGzipSize(32);
        gzipHandler.setHandler(new EventHandler(events, "GzipHandler-wrapped-handler"));

        handlers.setHandlers(new Handler[]{resourceHandler, gzipHandler, new DefaultHandler()});

        server.setHandler(handlers);
        server.start();

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testRequest() throws ExecutionException, InterruptedException, TimeoutException
    {
        ContentResponse response = client.GET(server.getURI().resolve("/"));
        assertThat("response.status", response.getStatus(), is(200));
        // we should have received a directory listing from the ResourceHandler
        assertThat("response.content", response.getContentAsString(), containsString("Directory: /"));
        // resource handler should have handled the request
        // the gzip handler and default handlers should have been executed, seeing as this is a HandlerCollection
        // but the gzip handler should not have acted on the request, as the response is committed
        assertThat("One event should have been recorded", events.size(), is(1));
        // the event handler should see the request.isHandled = true
        // and response.isCommitted = true as the gzip handler didn't really do anything due to these
        // states and let the wrapped handler (the EventHandler in this case) make the call on what it should do.
        assertThat("Event indicating that GzipHandler-wrapped-handler ran", events.remove(), is("GzipHandler-wrapped-handler [request.isHandled=true, response.isCommitted=true]"));
    }

    private static class EventHandler extends AbstractHandler
    {
        private final LinkedBlockingQueue<String> events;
        private final String action;

        public EventHandler(LinkedBlockingQueue<String> events, String action)
        {
            this.events = events;
            this.action = action;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            events.offer(String.format("%s [request.isHandled=%b, response.isCommitted=%b]", action, baseRequest.isHandled(), response.isCommitted()));
        }
    }
}
