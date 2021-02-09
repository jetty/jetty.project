//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
