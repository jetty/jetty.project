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

package org.eclipse.jetty.ee10.servlet;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContextScopeListenerTest
{
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _client;
    private final List<String> _history = new CopyOnWriteArrayList<>();
    private ServletContextHandler _contextHandler;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _contextHandler = new ServletContextHandler();
        _server.setHandler(_contextHandler);
        _server.start();

        _client = new HttpClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testAsyncServlet() throws Exception
    {
        _contextHandler.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                if  (req.getDispatcherType() == DispatcherType.ASYNC)
                {
                    _history.add("asyncDispatch");
                    return;
                }

                _history.add("doGet");
                AsyncContext asyncContext = req.startAsync();
                asyncContext.start(() ->
                {
                    _history.add("asyncRunnable");
                    asyncContext.dispatch("/dispatch");
                });
            }
        }), "/");

        CountDownLatch complete = new CountDownLatch(3);

        _contextHandler.addEventListener(new ContextHandler.ContextScopeListener()
        {
            // Use a lock to prevent the async thread running the listener concurrently.
            private final ReentrantLock _lock = new ReentrantLock();

            @Override
            public void enterScope(Context context, Request request)
            {
                _lock.lock();
                String pathInContext = (request == null) ? "null" : Request.getPathInContext(request);
                _history.add("enterScope " + pathInContext);
            }

            @Override
            public void exitScope(Context context, Request request)
            {
                String pathInContext = (request == null) ? "null" : Request.getPathInContext(request);
                _history.add("exitScope " + pathInContext);
                _lock.unlock();
                complete.countDown();
            }
        });

        URI uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/initialPath");
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));

        assertTrue(complete.await(5, TimeUnit.SECONDS));
        assertHistory(
            "enterScope /initialPath",
            "doGet",
            "exitScope /initialPath",
            "enterScope /initialPath",
            "asyncRunnable",
            "exitScope /initialPath",
            "enterScope /initialPath",
            "asyncDispatch",
            "exitScope /initialPath"
        );
    }

    private void assertHistory(String... values)
    {
        assertThat(_history, equalTo(Arrays.asList(values)));
    }
}
