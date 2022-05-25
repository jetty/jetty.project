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

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class GzipHandlerBreakEvenSizeTest
{
    private Server server;
    private HttpClient client;

    @BeforeEach
    public void startServerAndClient() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        GzipHandler gzipHandler = new GzipHandler();

        ServletContextHandler context = new ServletContextHandler(gzipHandler, "/");
        context.addServlet(VeryCompressibleContentServlet.class, "/content");
        gzipHandler.setHandler(context);
        server.setHandler(gzipHandler);

        server.start();

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stopServerAndClient()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 10, 15, 20, 21, 22, 23, 24, 25, 50, 100, 300, 500})
    public void testRequestSized(int size) throws Exception
    {
        // TODO no idea what this is really testing

        URI uri = server.getURI().resolve("/content?size=" + size);
        ContentResponse response = client.newRequest(uri)
            .headers(headers -> headers.put(HttpHeader.ACCEPT_ENCODING, HttpHeaderValue.GZIP))
            .send();

        assertThat("Status Code", response.getStatus(), is(200));
        assertThat("Size Requested", response.getHeaders().getField("X-SizeRequested").getIntValue(), is(size));

        if (size < GzipHandler.DEFAULT_MIN_GZIP_SIZE)
            assertThat("Response Size", response.getHeaders().getField(HttpHeader.CONTENT_LENGTH).getIntValue(), lessThanOrEqualTo(size));
    }

    public static class VeryCompressibleContentServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            String sizeStr = req.getParameter("size");
            int size = 0;
            if (!StringUtil.isBlank(sizeStr))
            {
                size = Integer.parseInt(sizeStr);
            }
            resp.setHeader("X-SizeRequested", String.valueOf(size));
            if (size > 0)
            {
                byte[] buf = new byte[size];
                Arrays.fill(buf, (byte)'x');
                resp.getWriter().print(new String(buf, UTF_8));
            }
            resp.getWriter().close();
        }
    }
}
