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

import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
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

    public void startServer(Handler rootHandler) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        server.setHandler(rootHandler);
        server.start();

        client = new HttpClient();
        client.start();
    }

    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testRequest() throws Exception
    {
        Handler.Collection handlers = new Handler.Collection();

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(ResourceFactory.root().newResource(workDir.getPath()));
        resourceHandler.setDirAllowed(true);
        resourceHandler.setHandler(new EventHandler(events, "ResourceHandler"));

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setMinGzipSize(32);
        gzipHandler.setHandler(new EventHandler(events, "GzipHandler-wrapped-handler"));

        handlers.setHandlers(resourceHandler, gzipHandler, new DefaultHandler());

        startServer(handlers);

        ContentResponse response = client.GET(server.getURI().resolve("/"));
        assertThat("response.status", response.getStatus(), is(200));
        // we should have received a directory listing from the ResourceHandler
        assertThat("response.content", response.getContentAsString(), containsString("Directory: /"));
        // resource handler should have handled the request;
        // hence the gzip handler and default handlers should not have been executed
        assertThat("Zero event should have been recorded", events.size(), is(0));
    }

    private static class EventHandler extends Handler.Abstract
    {
        private final LinkedBlockingQueue<String> events;
        private final String action;

        public EventHandler(LinkedBlockingQueue<String> events, String action)
        {
            this.events = events;
            this.action = action;
        }

        @Override
        public boolean process(Request request, Response response, Callback callback)
        {
            events.offer(action);
            callback.succeeded();
            return true;
        }
    }
}
