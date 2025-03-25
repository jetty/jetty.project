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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.MultiPartRequestContent;
import org.eclipse.jetty.client.OutputStreamRequestContent;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.content.InputStreamContentSource;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

public class MultiPartServletTest
{
    private static final int MAX_FILE_SIZE = 512 * 1024;

    private Server server;
    private ServerConnector connector;
    private HttpClient client;
    private Path tmpDir;
    private String tmpDirString;

    @BeforeEach
    public void before() throws Exception
    {
        tmpDir = Files.createTempDirectory(MultiPartServletTest.class.getSimpleName());
        tmpDirString = tmpDir.toAbsolutePath().toString();
    }

    private void start(HttpServlet servlet, MultipartConfigElement config, boolean eager) throws Exception
    {
        config = config == null ? new MultipartConfigElement(tmpDirString, MAX_FILE_SIZE, -1, 0) : config;
        server = new Server(null, null, null);
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler servletContextHandler = new ServletContextHandler("/");
        ServletHolder servletHolder = new ServletHolder(servlet);
        servletHolder.getRegistration().setMultipartConfig(config);
        servletContextHandler.addServlet(servletHolder, "/");
        server.setHandler(servletContextHandler);

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("multipart/form-data");
        gzipHandler.setMinGzipSize(32);

        if (eager)
            gzipHandler.setHandler(new EagerFormHandler());

        servletContextHandler.insertHandler(gzipHandler);

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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testLargePart(boolean eager) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp)
            {
                req.getParameterMap();
            }
        }, new MultipartConfigElement(tmpDirString), eager);

        OutputStreamRequestContent content = new OutputStreamRequestContent();
        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("param", null, null, content));
        multiPart.close();

        try (InputStreamResponseListener listener = new InputStreamResponseListener())
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/defaultConfig")
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .body(multiPart)
                .send(listener);

            // Write large amount of content to the part.
            byte[] byteArray = new byte[1024 * 1024];
            Arrays.fill(byteArray, (byte)1);
            for (int i = 0; i < 1024 * 2; i++)
            {
                content.getOutputStream().write(byteArray);
            }
            content.close();

            assert400orEof(listener, responseContent -> assertThat(responseContent, containsString("400")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testManyParts(boolean eager) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp)
            {
                req.getParameterMap();
            }
        }, new MultipartConfigElement(tmpDirString), eager);

        byte[] byteArray = new byte[1024];
        Arrays.fill(byteArray, (byte)1);

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        for (int i = 0; i < 1024 * 1024; i++)
        {
            BytesRequestContent content = new BytesRequestContent(byteArray);
            multiPart.addPart(new MultiPart.ContentSourcePart("part" + i, null, null, content));
        }
        multiPart.close();

        try (InputStreamResponseListener listener = new InputStreamResponseListener())
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/defaultConfig")
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .body(multiPart)
                .send(listener);

            assert400orEof(listener, responseContent -> assertThat(responseContent, containsString("400")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMaxRequestSize(boolean eager) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp)
            {
                req.getParameterMap();
            }
        }, new MultipartConfigElement(tmpDirString, -1, 1024, 1024 * 1024 * 8), eager);

        OutputStreamRequestContent content = new OutputStreamRequestContent();
        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("param", null, null, content));
        multiPart.close();

        try (InputStreamResponseListener listener = new InputStreamResponseListener())
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/requestSizeLimit")
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .body(multiPart)
                .send(listener);

            Throwable writeError = null;
            try
            {
                // Write large amount of content to the part.
                byte[] byteArray = new byte[1024 * 1024];
                Arrays.fill(byteArray, (byte)1);
                for (int i = 0; i < 1024 * 1024; i++)
                {
                    content.getOutputStream().write(byteArray);
                }
                fail("We should never be able to write all the content.");
            }
            catch (Exception e)
            {
                writeError = e;
            }

            assertThat(writeError, instanceOf(EofException.class));

            assert400orEof(listener, null);
        }
    }

    private static void assert400orEof(InputStreamResponseListener listener, Consumer<String> checkbody) throws InterruptedException, TimeoutException
    {
        // There is a race here, either we fail trying to write some more content OR
        // we get 400 response, for some reason reading the content throws EofException.
        try
        {
            Response response = listener.get(60, TimeUnit.SECONDS);
            assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
            String responseContent = IO.toString(listener.getInputStream());
            if (checkbody != null)
                checkbody.accept(responseContent);
        }
        catch (EofException ignored)
        {
        }
        catch (ExecutionException | IOException e)
        {
            Throwable cause = e.getCause();
            assertThat(cause, instanceOf(EofException.class));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSimpleMultiPart(boolean eager) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response1) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                assertNotNull(parts);
                assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                assertEquals("part1", part.getName());
                Collection<String> headerNames = part.getHeaderNames();
                assertNotNull(headerNames);
                assertEquals(2, headerNames.size());
                String content1 = IO.toString(part.getInputStream(), UTF_8);
                assertEquals("content1", content1);
            }
        }, null, eager);

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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testTempFilesDeletedOnError(boolean eager) throws Exception
    {
        byte[] bytes = new byte[2 * MAX_FILE_SIZE];
        Arrays.fill(bytes, (byte)1);

        // Should throw as the max file size is exceeded.
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response1) throws ServletException, IOException
            {
                // Should throw as the max file size is exceeded.
                request.getParts();
            }
        }, null, eager);

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

            assertEquals(400, response.getStatus());
        }

        String[] fileList = tmpDir.toFile().list();
        assertNotNull(fileList);
        assertThat(fileList.length, is(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testDefaultTempDirectory(boolean eager) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response1) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                assertNotNull(parts);
                assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                assertEquals("part1", part.getName());
                Collection<String> headerNames = part.getHeaderNames();
                assertNotNull(headerNames);
                assertEquals(2, headerNames.size());
                String content1 = IO.toString(part.getInputStream(), UTF_8);
                assertEquals("content1", content1);
            }
        }, new MultipartConfigElement(null, MAX_FILE_SIZE, -1, 0), eager);

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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testMultiPartGzip(boolean eager) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response1) throws IOException, ServletException
            {
                String contentType1 = request.getContentType();
                response1.setContentType(contentType1);
                response1.flushBuffer();

                MultiPartRequestContent echoParts = new MultiPartRequestContent(MultiPart.extractBoundary(contentType1));
                Collection<Part> servletParts = request.getParts();
                for (Part part : servletParts)
                {
                    HttpFields.Mutable partHeaders = HttpFields.build();
                    for (String h1 : part.getHeaderNames())
                        partHeaders.add(h1, part.getHeader(h1));

                    echoParts.addPart(new MultiPart.ContentSourcePart(part.getName(), part.getSubmittedFileName(), partHeaders, new InputStreamContentSource(part.getInputStream())));
                }
                echoParts.close();
                IO.copy(Content.Source.asInputStream(echoParts), response1.getOutputStream());
            }
        }, null, eager);

        // Do not automatically handle gzip.
        client.getContentDecoderFactories().clear();

        String contentString = "the quick brown fox jumps over the lazy dog, " +
            "the quick brown fox jumps over the lazy dog";
        StringRequestContent content = new StringRequestContent(contentString);

        MultiPartRequestContent multiPartContent = new MultiPartRequestContent();
        multiPartContent.addPart(new MultiPart.ContentSourcePart("stringPart", null, HttpFields.EMPTY, content));
        multiPartContent.close();

        try (InputStreamResponseListener responseStream = new InputStreamResponseListener())
        {
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
            String boundary = MultiPart.extractBoundary(contentType);
            InputStream inputStream = new GZIPInputStream(responseStream.getInputStream());
            MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary);
            formData.setMaxParts(1);
            formData.setMaxMemoryFileSize(-1);

            try (Blocker.Promise<MultiPartFormData.Parts> promise = Blocker.promise())
            {
                formData.parse(new InputStreamContentSource(inputStream), promise);
                MultiPartFormData.Parts parts = promise.block();

                assertThat(parts.size(), is(1));
                assertThat(parts.get(0).getContentAsString(UTF_8), is(contentString));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testDoubleReadFromPart(boolean eager) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.setContentType("text/plain");
                for (Part part : req.getParts())
                {
                    resp.getWriter().println("Part: name=" + part.getName() + ", size=" + part.getSize() + ", content=" + IO.toString(part.getInputStream()));
                    resp.getWriter().println("Part: name=" + part.getName() + ", size=" + part.getSize() + ", content=" + IO.toString(part.getInputStream()));
                }
            }
        }, null, eager);

        String contentString = "the quick brown fox jumps over the lazy dog, " +
            "the quick brown fox jumps over the lazy dog";
        StringRequestContent content = new StringRequestContent(contentString);
        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("myPart", null, HttpFields.EMPTY, content));
        multiPart.close();

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTP.asString())
            .method(HttpMethod.POST)
            .body(multiPart)
            .send();

        assertEquals(200, response.getStatus());
        assertThat(response.getContentAsString(), containsString("Part: name=myPart, size=88, content=the quick brown fox jumps over the lazy dog, the quick brown fox jumps over the lazy dog\n" +
            "Part: name=myPart, size=88, content=the quick brown fox jumps over the lazy dog, the quick brown fox jumps over the lazy dog"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPartAsParameter(boolean eager) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.setContentType("text/plain");
                Map<String, String[]> parameterMap = req.getParameterMap();
                for (Map.Entry<String, String[]> entry : parameterMap.entrySet())
                {
                    assertThat(entry.getValue().length, equalTo(1));
                    resp.getWriter().println("Parameter: " + entry.getKey() + "=" + entry.getValue()[0]);
                }
            }
        }, null, eager);

        String contentString = "the quick brown fox jumps over the lazy dog, " +
            "the quick brown fox jumps over the lazy dog";
        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("part1", null, HttpFields.EMPTY, new StringRequestContent(contentString)));
        multiPart.addPart(new MultiPart.ContentSourcePart("part2", null, HttpFields.EMPTY, new StringRequestContent(contentString)));
        multiPart.addPart(new MultiPart.ContentSourcePart("part3", null, HttpFields.EMPTY, new StringRequestContent(contentString)));
        multiPart.addPart(new MultiPart.ContentSourcePart("partFileName", "myFile", HttpFields.EMPTY, new StringRequestContent(contentString)));
        multiPart.close();

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTP.asString())
            .method(HttpMethod.POST)
            .body(multiPart)
            .send();

        assertEquals(200, response.getStatus());
        String responseContent = response.getContentAsString();
        assertThat(responseContent, containsString("Parameter: part1=" + contentString));
        assertThat(responseContent, containsString("Parameter: part2=" + contentString));
        assertThat(responseContent, containsString("Parameter: part3=" + contentString));
        assertThat(responseContent, not(containsString("Parameter: partFileName=" + contentString)));
    }
}
