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

package org.eclipse.jetty.ee.servlet;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class ServletChannelErrorRequestTest
{
    private Server _server;

    @AfterEach
    public void after() throws Exception
    {
        if (_server != null)
            _server.stop();
    }

    @Test
    public void testTheErrorPathDoesNotReadTheApplicationRequestWrapper() throws Exception
    {
        AtomicInteger wrapperCalls = new AtomicInteger();

        _server = new Server();
        LocalConnector connector = new LocalConnector(_server);
        _server.addConnector(connector);
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        _server.setHandler(contextHandler);
        contextHandler.addServlet(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                throw new RuntimeException("test");
            }
        }, "/");
        contextHandler.insertHandler(new Handler.Wrapper()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Store a wrapper of the request for the dispatch, as a ServerAuthModule's is stored.
                ServletContextRequest servletContextRequest = ServletContextRequest.getServletContextRequest(request);
                servletContextRequest.setServletRequest(new HttpServletRequestWrapper(servletContextRequest.getServletApiRequest())
                {
                    @Override
                    public String getRequestURI()
                    {
                        wrapperCalls.incrementAndGet();
                        return super.getRequestURI();
                    }
                });
                return super.handle(request, response, callback);
            }
        });
        // Answer the error with a core handler, so only the container reads the request.
        contextHandler.setErrorHandler((request, response, callback) ->
        {
            callback.succeeded();
            return true;
        });
        _server.start();

        try (StacklessLogging ignored = new StacklessLogging(ServletChannel.class))
        {
            String response = connector.getResponse("GET /test HTTP/1.0\r\n\r\n");
            assertThat(response, containsString("HTTP/1.1 " + HttpStatus.INTERNAL_SERVER_ERROR_500));
        }

        assertThat(wrapperCalls.get(), is(0));
    }
}
