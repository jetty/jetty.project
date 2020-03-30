//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.MultiPartRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.MultiPartFormInputStream;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MultiPartServletTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;
    private Path tmpDir;

    private static final int MAX_FILE_SIZE = 512 * 1024;
    private static final int LARGE_MESSAGE_SIZE = 1024 * 1024;

    public static class MultiPartServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            if (!req.getContentType().contains(MimeTypes.Type.MULTIPART_FORM_DATA.asString()))
            {
                resp.setContentType("text/plain");
                resp.getWriter().println("not content type " + MimeTypes.Type.MULTIPART_FORM_DATA);
                resp.getWriter().println("contentType: " + req.getContentType());
                return;
            }

            resp.setContentType("text/plain");
            for (Part part : req.getParts())
            {
                resp.getWriter().println("Part: name=" + part.getName() + ", size=" + part.getSize());
            }
        }
    }

    @BeforeEach
    public void start() throws Exception
    {
        tmpDir = Files.createTempDirectory(MultiPartServletTest.class.getSimpleName());

        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        ServletHolder servletHolder = contextHandler.addServlet(MultiPartServlet.class, "/");

        MultipartConfigElement config = new MultipartConfigElement(tmpDir.toAbsolutePath().toString(),
            MAX_FILE_SIZE, -1, 1);
        servletHolder.getRegistration().setMultipartConfig(config);

        server.setHandler(contextHandler);

        server.start();

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();

        IO.delete(tmpDir.toFile());
    }

    @Test
    public void testTempFilesDeletedOnError() throws Exception
    {
        byte[] byteArray = new byte[LARGE_MESSAGE_SIZE];
        Arrays.fill(byteArray, (byte)1);
        BytesRequestContent content = new BytesRequestContent(byteArray);

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addFieldPart("largePart", content, null);
        multiPart.close();

        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class, MultiPartFormInputStream.class))
        {
            ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .body(multiPart)
                .send();

            assertEquals(500, response.getStatus());
            assertThat(response.getContentAsString(),
                containsString("Multipart Mime part largePart exceeds max filesize"));
        }

        assertThat(tmpDir.toFile().list().length, is(0));
    }
}
