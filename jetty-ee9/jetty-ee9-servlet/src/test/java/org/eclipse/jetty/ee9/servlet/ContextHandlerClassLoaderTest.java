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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class ContextHandlerClassLoaderTest
{
    private ServletContextHandler _context;
    private ServerConnector _connector;
    private Server _server;
    private HttpClient _client;

    public static class MyCustomClassLoader extends ClassLoader
    {
    }

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler();
        _context.setClassLoader(new MyCustomClassLoader());
        _context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getDispatcherType() == DispatcherType.REQUEST)
                {
                    AsyncContext asyncContext = req.startAsync();
                    asyncContext.start(asyncContext::dispatch);
                    return;
                }

                resp.getWriter().print(req.getDispatcherType() + " " + Thread.currentThread().getContextClassLoader());
                if (req.isAsyncStarted())
                    req.getAsyncContext().complete();
            }
        }), "/");

        _client = new HttpClient();
        _client.start();
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testRootContextHandler() throws Exception
    {
        _server.setHandler(_context);
        _server.start();

        ContentResponse response = _client.GET("http://localhost:" + _connector.getLocalPort());
        assertThat(response.getStatus(), equalTo(200));
        String responseContent = response.getContentAsString();
        assertThat(responseContent, containsString("ASYNC"));
        assertThat(responseContent, containsString("MyCustomClassLoader"));
    }

    @Test
    public void testNestedContextHandler() throws Exception
    {
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setHandler(_context);
        contextHandler.setContextPath("/");
        _server.setHandler(contextHandler);
        _server.start();

        ContentResponse response = _client.GET("http://localhost:" + _connector.getLocalPort());
        assertThat(response.getStatus(), equalTo(200));
        String responseContent = response.getContentAsString();
        assertThat(responseContent, containsString("ASYNC"));
        assertThat(responseContent, containsString("MyCustomClassLoader"));
    }
}
