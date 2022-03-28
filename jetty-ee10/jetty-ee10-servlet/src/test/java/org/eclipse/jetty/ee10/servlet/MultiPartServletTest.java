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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.MultiPartRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// TODO
@Disabled
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

    public static class MultiPartEchoServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            if (!req.getContentType().contains(MimeTypes.Type.MULTIPART_FORM_DATA.asString()))
            {
                resp.sendError(400);
                return;
            }

            resp.setContentType(req.getContentType());
            IO.copy(req.getInputStream(), resp.getOutputStream());
        }
    }

    @BeforeEach
    public void start() throws Exception
    {
        tmpDir = Files.createTempDirectory(MultiPartServletTest.class.getSimpleName());
        assertNotNull(tmpDir);

        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        MultipartConfigElement config = new MultipartConfigElement(tmpDir.toAbsolutePath().toString(),
            MAX_FILE_SIZE, -1, 1);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        ServletHolder servletHolder = contextHandler.addServlet(MultiPartServlet.class, "/");
        servletHolder.getRegistration().setMultipartConfig(config);
        servletHolder = contextHandler.addServlet(MultiPartEchoServlet.class, "/echo");
        servletHolder.getRegistration().setMultipartConfig(config);

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("multipart/form-data");
        gzipHandler.setMinGzipSize(32);
        gzipHandler.setHandler(contextHandler);
        server.setHandler(gzipHandler);

        server.start();

        client = new HttpClient();
        client.start();
        client.getContentDecoderFactories().clear();
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

        try (StacklessLogging ignored = new StacklessLogging(HttpChannelState.class, MultiPartFormInputStream.class))
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

        String[] fileList = tmpDir.toFile().list();
        assertNotNull(fileList);
        assertThat(fileList.length, is(0));
    }

    @Test
    public void testMultiPartGzip() throws Exception
    {
        String contentString = "the quick brown fox jumps over the lazy dog, " +
            "the quick brown fox jumps over the lazy dog";
        StringRequestContent content = new StringRequestContent(contentString);

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addFieldPart("largePart", content, null);
        multiPart.close();

        try (StacklessLogging ignored = new StacklessLogging(HttpChannelState.class, MultiPartFormInputStream.class))
        {
            InputStreamResponseListener responseStream = new InputStreamResponseListener();
            client.newRequest("localhost", connector.getLocalPort())
                .path("/echo")
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .headers(h -> h.add(HttpHeader.ACCEPT_ENCODING, "gzip"))
                .body(multiPart)
                .send(responseStream);

            Response response = responseStream.get(5, TimeUnit.SECONDS);
            HttpFields headers = response.getHeaders();
            assertThat(headers.get(HttpHeader.CONTENT_TYPE), startsWith("multipart/form-data"));
            assertThat(headers.get(HttpHeader.CONTENT_ENCODING), is("gzip"));

            InputStream inputStream = new GZIPInputStream(responseStream.getInputStream());
            String contentType = headers.get(HttpHeader.CONTENT_TYPE);
            MultiPartFormInputStream mpis = new MultiPartFormInputStream(inputStream, contentType, null, null);
            List<Part> parts = new ArrayList<>(mpis.getParts());
            assertThat(parts.size(), is(1));
            assertThat(IO.toString(parts.get(0).getInputStream()), is(contentString));
        }
    }
}
