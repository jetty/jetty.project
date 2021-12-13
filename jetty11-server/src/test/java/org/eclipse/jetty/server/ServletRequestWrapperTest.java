//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class ServletRequestWrapperTest
{
    private Server server;
    private LocalConnector connector;
    private RequestHandler handler;

    @BeforeEach
    public void init() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server, new HttpConnectionFactory());
        server.addConnector(connector);

        handler = new RequestHandler();
        server.setHandler(handler);
        server.start();
    }

    @Test
    public void testServletRequestWrapper() throws Exception
    {
        String request = "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "\n";

        String response = connector.getResponse(request);
        assertThat("Response", response, containsString("200"));
    }

    private class RequestWrapper extends ServletRequestWrapper
    {
        public RequestWrapper(ServletRequest request)
        {
            super(request);
        }
    }

    private class RequestHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                            HttpServletResponse response)
            throws IOException, ServletException
        {
            RequestWrapper requestWrapper = new RequestWrapper(request);
            AsyncContext context = request.startAsync(requestWrapper, response);
            context.complete();
            baseRequest.setHandled(true);
        }
    }
}
