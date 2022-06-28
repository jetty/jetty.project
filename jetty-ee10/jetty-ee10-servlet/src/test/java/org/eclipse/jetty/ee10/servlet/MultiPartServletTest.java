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
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
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
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiParts;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MultiPartServletTest
{
    private static final int MAX_FILE_SIZE = 512 * 1024;

    private Server server;
    private ServerConnector connector;
    private HttpClient client;
    private Path tmpDir;

    private void start(HttpServlet servlet) throws Exception
    {
        tmpDir = Files.createTempDirectory(MultiPartServletTest.class.getSimpleName());

        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        MultipartConfigElement config = new MultipartConfigElement(tmpDir.toAbsolutePath().toString(),
            MAX_FILE_SIZE, -1, 0);

        ServletContextHandler contextHandler = new ServletContextHandler(server, "/");
        ServletHolder servletHolder = new ServletHolder(servlet);
        servletHolder.getRegistration().setMultipartConfig(config);
        contextHandler.addServlet(servletHolder, "/");

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("multipart/form-data");
        gzipHandler.setMinGzipSize(32);
        gzipHandler.setHandler(contextHandler);
        server.setHandler(gzipHandler);

        server.start();

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
        IO.delete(tmpDir.toFile());
    }

    @Test
    public void testSimpleMultiPart() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                assertNotNull(parts);
                assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                assertEquals("part1", part.getName());
                Collection<String> headerNames = part.getHeaderNames();
                assertNotNull(headerNames);
                assertEquals(2, headerNames.size());
                String content = IO.toString(part.getInputStream(), UTF_8);
                assertEquals("content1", content);
            }
        });

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = socket.getOutputStream();

            String content = """
                --A1B2C3
                Content-Disposition: form-data; name="part1"
                Content-Type: text/plain; charset="UTF-8"
                                
                content1
                --A1B2C3--
                """;
            String header = """
                POST / HTTP/1.1
                Host: localhost
                Content-Type: multipart/form-data; boundary="A1B2C3"
                Content-Length: $L
                                
                """.replace("$L", String.valueOf(content.length()));

            output.write(header.getBytes(UTF_8));
            output.write(content.getBytes(UTF_8));
            output.flush();

            HttpTester.Response response = HttpTester.parseResponse(socket.getInputStream());
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testTempFilesDeletedOnError() throws Exception
    {
        byte[] bytes = new byte[2 * MAX_FILE_SIZE];
        Arrays.fill(bytes, (byte)1);

        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // Should throw as the max file size is exceeded.
                request.getParts();
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("largePart", "largeFile.bin", HttpFields.EMPTY, new BytesRequestContent(bytes)));
        multiPart.close();

        try (StacklessLogging ignored = new StacklessLogging(ServletChannel.class))
        {
            ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .body(multiPart)
                .send();

            assertEquals(500, response.getStatus());
            assertThat(response.getContentAsString(), containsString("max file size exceeded"));
        }

        String[] fileList = tmpDir.toFile().list();
        assertNotNull(fileList);
        assertThat(fileList.length, is(0));
    }

    @Test
    public void testMultiPartGzip() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setContentType(request.getContentType());
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });
        // Do not automatically handle gzip.
        client.getContentDecoderFactories().clear();

        String contentString = "the quick brown fox jumps over the lazy dog, " +
            "the quick brown fox jumps over the lazy dog";
        StringRequestContent content = new StringRequestContent(contentString);

        MultiPartRequestContent multiPartContent = new MultiPartRequestContent();
        multiPartContent.addPart(new MultiPart.ContentSourcePart("stringPart", null, HttpFields.EMPTY, content));
        multiPartContent.close();

        InputStreamResponseListener responseStream = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
            .path("/echo")
            .scheme(HttpScheme.HTTP.asString())
            .method(HttpMethod.POST)
            .headers(h -> h.add(HttpHeader.ACCEPT_ENCODING, "gzip"))
            .body(multiPartContent)
            .send(responseStream);

        Response response = responseStream.get(5, TimeUnit.SECONDS);
        HttpFields headers = response.getHeaders();
        assertThat(headers.get(HttpHeader.CONTENT_TYPE), startsWith("multipart/form-data"));
        assertThat(headers.get(HttpHeader.CONTENT_ENCODING), is("gzip"));

        String contentType = headers.get(HttpHeader.CONTENT_TYPE);
        String boundary = MultiParts.extractBoundary(contentType);
        MultiParts multiParts = new MultiParts(boundary);

        InputStream inputStream = new GZIPInputStream(responseStream.getInputStream());
        multiParts.parse(Content.Chunk.from(IO.readBytes(inputStream), true));
        MultiParts.Parts parts = multiParts.join();

        assertThat(parts.size(), is(1));
        assertThat(parts.get(0).getContentAsString(UTF_8), is(contentString));
    }
}
