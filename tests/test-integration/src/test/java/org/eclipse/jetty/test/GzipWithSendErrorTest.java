//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.zip.GZIPOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GzipWithSendErrorTest
{
    private Server server;
    private HttpClient client;

    @BeforeEach
    public void setup() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setInflateBufferSize(4096);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");

        contextHandler.addServlet(PostServlet.class, "/submit");
        contextHandler.addServlet(FailServlet.class, "/fail");

        gzipHandler.setHandler(contextHandler);
        server.setHandler(gzipHandler);
        server.start();

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void teardown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    /**
     * Make 3 requests on the same connection.
     * <p>
     * Normal POST with 200 response, POST which results in 400, POST with 200 response.
     * </p>
     */
    @Test
    public void testGzipNormalErrorNormal() throws Exception
    {
        URI serverURI = server.getURI();

        ContentResponse response;

        response = client.newRequest(serverURI.resolve("/submit"))
            .method(HttpMethod.POST)
            .header(HttpHeader.CONTENT_ENCODING, "gzip")
            .header(HttpHeader.ACCEPT_ENCODING, "gzip")
            .content(new BytesContentProvider("text/plain", compressed("normal-A")))
            .send();

        assertEquals(200, response.getStatus(), "Response status on /submit (normal-A)");
        assertEquals("normal-A", response.getContentAsString(), "Response content on /submit (normal-A)");

        response = client.newRequest(serverURI.resolve("/fail"))
            .method(HttpMethod.POST)
            .header(HttpHeader.CONTENT_ENCODING, "gzip")
            .header(HttpHeader.ACCEPT_ENCODING, "gzip")
            .content(new BytesContentProvider("text/plain", compressed("normal-B")))
            .send();

        assertEquals(400, response.getStatus(), "Response status on /fail (normal-B)");
        assertThat("Response content on /fail (normal-B)", response.getContentAsString(), containsString("<title>Error 400 Bad Request</title>"));

        response = client.newRequest(serverURI.resolve("/submit"))
            .method(HttpMethod.POST)
            .header(HttpHeader.CONTENT_ENCODING, "gzip")
            .header(HttpHeader.ACCEPT_ENCODING, "gzip")
            .content(new BytesContentProvider("text/plain", compressed("normal-C")))
            .send();

        assertEquals(200, response.getStatus(), "Response status on /submit (normal-C)");
        assertEquals("normal-C", response.getContentAsString(), "Response content on /submit (normal-C)");
    }

    private byte[] compressed(String content) throws IOException
    {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(baos))
        {
            gzipOut.write(content.getBytes(UTF_8));
            gzipOut.finish();
            return baos.toByteArray();
        }
    }

    public static class PostServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setCharacterEncoding("utf-8");
            resp.setContentType("text/plain");
            resp.setHeader("X-Servlet", req.getServletPath());

            String reqBody = IO.toString(req.getInputStream(), UTF_8);
            resp.getWriter().append(reqBody);
        }
    }

    public static class FailServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setHeader("X-Servlet", req.getServletPath());
            // intentionally do not read request body here.
            resp.sendError(400);
        }
    }
}
