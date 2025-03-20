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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;
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
import org.eclipse.jetty.ee9.nested.HttpChannel;
import org.eclipse.jetty.ee9.nested.MultiPartFormInputStream;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartCompliance;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

public class MultiPartServletTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;
    private Path tmpDir;

    private static final int MAX_FILE_SIZE = 512 * 1024;
    private static final int LARGE_MESSAGE_SIZE = 1024 * 1024;
    private static final int MAX_REQUEST_SIZE = 1024 * 1024 * 8;

    public static class RequestParameterServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            req.getParameterMap();
            req.getParts();
            resp.setStatus(200);
            resp.getWriter().print("success");
            resp.getWriter().close();
        }
    }

    public static class MultiPartServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setCharacterEncoding("utf-8");
            resp.setContentType("text/plain");

            PrintWriter out = resp.getWriter();

            if (!req.getContentType().contains(MimeTypes.Type.MULTIPART_FORM_DATA.asString()))
            {
                out.println("not content type " + MimeTypes.Type.MULTIPART_FORM_DATA);
                out.println("contentType: " + req.getContentType());
                return;
            }

            for (Part part : req.getParts())
            {
                out.printf("Part: name=%s, size=%s", part.getName(), part.getSize());
                if (part.getSize() <= 100)
                {
                    String content = IO.toString(part.getInputStream());
                    out.printf(", content=%s", content);
                }
                out.println();
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

    private void startServer(MultiPartCompliance multiPartCompliance) throws Exception
    {
        tmpDir = Files.createTempDirectory(MultiPartServletTest.class.getSimpleName());
        assertNotNull(tmpDir);

        server = new Server();
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.setMultiPartCompliance(multiPartCompliance);
        connector = new ServerConnector(server, new HttpConnectionFactory(httpConfiguration));
        server.addConnector(connector);

        MultipartConfigElement config = new MultipartConfigElement(tmpDir.toAbsolutePath().toString(),
            MAX_FILE_SIZE, -1, 1);
        MultipartConfigElement requestSizedConfig = new MultipartConfigElement(tmpDir.toAbsolutePath().toString(),
            -1, MAX_REQUEST_SIZE, 1);
        MultipartConfigElement defaultConfig = new MultipartConfigElement(tmpDir.toAbsolutePath().toString(),
            -1, -1, 1);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        ServletHolder servletHolder = contextHandler.addServlet(MultiPartServlet.class, "/");
        servletHolder.getRegistration().setMultipartConfig(config);
        servletHolder = contextHandler.addServlet(RequestParameterServlet.class, "/defaultConfig");
        servletHolder.getRegistration().setMultipartConfig(defaultConfig);
        servletHolder = contextHandler.addServlet(RequestParameterServlet.class, "/requestSizeLimit");
        servletHolder.getRegistration().setMultipartConfig(requestSizedConfig);
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

    public static Stream<Arguments> multipartModes()
    {
        return Stream.of(
            Arguments.of(MultiPartCompliance.RFC7578),
            Arguments.of(MultiPartCompliance.LEGACY)
        );
    }

    /**
     * The request indicates that it is a multipart/form-data, but no body is sent.
     */
    @ParameterizedTest
    @MethodSource("multipartModes")
    public void testEmptyBodyMultipartForm(MultiPartCompliance multiPartCompliance) throws Exception
    {
        startServer(multiPartCompliance);

        String contentType = "multipart/form-data; boundary=---------------boundaryXYZ123";
        StringRequestContent emptyContent = new StringRequestContent(contentType, "");

        try (InputStreamResponseListener listener = new InputStreamResponseListener())
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/defaultConfig")
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .body(emptyContent)
                .send(listener);

            Response response = listener.get(60, TimeUnit.SECONDS);
            assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));

            assert400orEof(listener, responseContent ->
            {
                assertThat(responseContent, containsString("Unable to parse form content"));
                assertThat(responseContent, containsString("Missing content for multipart request"));
            });
        }
    }

    @ParameterizedTest
    @MethodSource("multipartModes")
    public void testLargePart(MultiPartCompliance multiPartCompliance) throws Exception
    {
        startServer(multiPartCompliance);

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

            assert400orEof(listener, responseContent ->
            {
                assertThat(responseContent, containsString("Unable to parse form content"));
                assertThat(responseContent, containsString("Form is larger than max length"));
            });
        }
    }

    @ParameterizedTest
    @MethodSource("multipartModes")
    public void testIncompleteMultipart(MultiPartCompliance multiPartCompliance) throws Exception
    {
        startServer(multiPartCompliance);

        String contentType = "multipart/form-data; boundary=-------------------------7e21c038151054";
        String incompleteForm = """
            ---------------------------7e21c038151054
            Content-Disposition: form-data; name="description"
            
            Some data, but incomplete
            ---------------------------7e21c038151054
            Content-Disposition: form-d"""; // intentionally incomplete

        StringRequestContent incomplete = new StringRequestContent(
            contentType,
            incompleteForm
        );

        try (InputStreamResponseListener listener = new InputStreamResponseListener())
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/defaultConfig")
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .body(incomplete)
                .send(listener);

            assert400orEof(listener, responseContent ->
            {
                assertThat(responseContent, containsString("Unable to parse form content"));
                assertThat(responseContent, containsString("Incomplete Multipart"));
            });
        }
    }

    @ParameterizedTest
    @MethodSource("multipartModes")
    public void testLineFeedCarriageReturnEOL(MultiPartCompliance multiPartCompliance) throws Exception
    {
        startServer(multiPartCompliance);

        String contentType = "multipart/form-data; boundary=---------------------------7e25e1e151054";
        // NOTE: The extra `\r` here are intentional, do not remove.
        String rawForm = """
            -----------------------------7e25e1e151054\r
            Content-Disposition: form-data; name="user"\r
                        \r
            anotheruser\r
            -----------------------------7e25e1e151054\r
            Content-Disposition: form-data; name="comment"\r
                        \r
            with something to say\r
            -----------------------------7e25e1e151054--\r
            """;

        StringRequestContent form = new StringRequestContent(
            contentType,
            rawForm
        );

        try (InputStreamResponseListener listener = new InputStreamResponseListener())
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/defaultConfig")
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .body(form)
                .send(listener);

            assert400orEof(listener, responseContent ->
            {
                assertThat(responseContent, containsString("Unable to parse form content"));
                if (multiPartCompliance == MultiPartCompliance.RFC7578)
                {
                    assertThat(responseContent, containsString("Illegal character ALPHA=&apos;s&apos"));
                }
                else if (multiPartCompliance == MultiPartCompliance.LEGACY)
                {
                    assertThat(responseContent, containsString("Incomplete Multipart"));
                }
            });
        }
    }

    @ParameterizedTest
    @MethodSource("multipartModes")
    public void testAllWhitespaceForm(MultiPartCompliance multiPartCompliance) throws Exception
    {
        startServer(multiPartCompliance);

        String contentType = "multipart/form-data; boundary=----WebKitFormBoundaryjwqONTsAFgubfMZc";
        String rawForm = " \n \n \n \n \n \n \n \n \n ";

        StringRequestContent form = new StringRequestContent(
            contentType,
            rawForm
        );

        try (InputStreamResponseListener listener = new InputStreamResponseListener())
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/defaultConfig")
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .body(form)
                .send(listener);

            assert400orEof(listener, responseContent ->
            {
                assertThat(responseContent, containsString("Unable to parse form content"));
                assertThat(responseContent, containsString("Missing content for multipart request"));
            });
        }
    }

    /**
     * A part with Content-Transfer-Encoding: base64, and the content is valid Base64 encoded.
     *
     * MultiPartCompliance mode set to allow MultiPartCompliance.Violation.BASE64_TRANSFER_ENCODING
     */
    @Test
    public void testLegacyContentTransferEncodingBase64Allowed() throws Exception
    {
        MultiPartCompliance legacyBase64 = MultiPartCompliance.from("LEGACY,BASE64_TRANSFER_ENCODING");

        startServer(legacyBase64);

        String contentType = "multipart/form-data; boundary=8GbcZNTauFWYMt7GeM9BxFMdlNBJ6aLJhGdXp";
        String rawForm = """
            --8GbcZNTauFWYMt7GeM9BxFMdlNBJ6aLJhGdXp
            Content-ID: <foo@example.org>
            Content-Disposition: form-data; name="quote"
            Content-Transfer-Encoding: base64
            
            IkJvb2tzIGFyZSB0aGUgbGliZXJhdGVkIHNwaXJpdHMgb2YgbWVuLiIgLS0gTWFyayBUd2Fpbg==
            --8GbcZNTauFWYMt7GeM9BxFMdlNBJ6aLJhGdXp--
            
            """;

        StringRequestContent form = new StringRequestContent(
            contentType,
            rawForm
        );

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .path("/")
            .scheme(HttpScheme.HTTP.asString())
            .method(HttpMethod.POST)
            .body(form)
            .send();

        assertEquals(200, response.getStatus());
        assertThat(response.getContentAsString(), containsString("Part: name=quote, size=55, content=\"Books are the liberated spirits of men.\" -- Mark Twain"));
    }

    /**
     * A part with Content-Transfer-Encoding: base64, but the content is not actually encoded in Base 64.
     *
     * MultiPartCompliance mode set to allow MultiPartCompliance.Violation.BASE64_TRANSFER_ENCODING
     */
    @Test
    public void testLegacyContentTransferEncodingBadBase64Allowed() throws Exception
    {
        MultiPartCompliance legacyBase64 = MultiPartCompliance.from("LEGACY,BASE64_TRANSFER_ENCODING");

        startServer(legacyBase64);

        String contentType = "multipart/form-data; boundary=8GbcZNTauFWYMt7GeM9BxFMdlNBJ6aLJhGdXp";
        String rawForm = """
            --8GbcZNTauFWYMt7GeM9BxFMdlNBJ6aLJhGdXp
            Content-ID: <foo@example.org>
            Content-Disposition: form-data; name="quote"
            Content-Transfer-Encoding: base64
            
            "Travel is fatal to prejudice." -- Mark Twain
            --8GbcZNTauFWYMt7GeM9BxFMdlNBJ6aLJhGdXp--
            
            """;

        StringRequestContent form = new StringRequestContent(
            contentType,
            rawForm
        );

        try (InputStreamResponseListener listener = new InputStreamResponseListener())
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/")
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .body(form)
                .send(listener);

            assert400orEof(listener, responseContent ->
            {
                assertThat(responseContent, containsString("Unable to parse form content"));
                assertThat(responseContent, containsString("java.lang.IllegalArgumentException: Last unit does not have enough valid bits"));
            });
        }
    }

    /**
     * A part with Content-Transfer-Encoding: base64, and the content is valid Base64 encoded.
     *
     * MultiPartCompliance mode set to allow MultiPartCompliance.LEGACY, which does not perform
     * base64 decoding.
     */
    @Test
    public void testLegacyContentTransferEncodingBase64() throws Exception
    {
        MultiPartCompliance legacyBase64 = MultiPartCompliance.LEGACY;

        startServer(legacyBase64);

        String contentType = "multipart/form-data; boundary=8GbcZNTauFWYMt7GeM9BxFMdlNBJ6aLJhGdXp";
        String rawForm = """
            --8GbcZNTauFWYMt7GeM9BxFMdlNBJ6aLJhGdXp
            Content-ID: <foo@example.org>
            Content-Disposition: form-data; name="quote"
            Content-Transfer-Encoding: base64
            
            IkJvb2tzIGFyZSB0aGUgbGliZXJhdGVkIHNwaXJpdHMgb2YgbWVuLiIgLS0gTWFyayBUd2Fpbg==
            --8GbcZNTauFWYMt7GeM9BxFMdlNBJ6aLJhGdXp--
            
            """;

        StringRequestContent form = new StringRequestContent(
            contentType,
            rawForm
        );

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .path("/")
            .scheme(HttpScheme.HTTP.asString())
            .method(HttpMethod.POST)
            .body(form)
            .send();

        assertEquals(200, response.getStatus());
        assertThat(response.getContentAsString(), containsString("Part: name=quote, size=76, content=IkJvb2tzIGFyZSB0aGUgbGliZXJhdGVkIHNwaXJpdHMgb2YgbWVuLiIgLS0gTWFyayBUd2Fpbg=="));
    }

    @ParameterizedTest
    @MethodSource("multipartModes")
    public void testManyParts(MultiPartCompliance multiPartCompliance) throws Exception
    {
        startServer(multiPartCompliance);

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

            assert400orEof(listener, responseContent ->
            {
                assertThat(responseContent, containsString("Unable to parse form content"));
                assertThat(responseContent, containsString("Form with too many keys"));
            });
        }
    }

    @ParameterizedTest
    @MethodSource("multipartModes")
    public void testMaxRequestSize(MultiPartCompliance multiPartCompliance) throws Exception
    {
        startServer(multiPartCompliance);

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
        catch (ExecutionException | IOException e)
        {
            Throwable cause = e.getCause();
            assertThat(cause, instanceOf(EofException.class));
        }
    }

    @ParameterizedTest
    @MethodSource("multipartModes")
    public void testTempFilesDeletedOnError(MultiPartCompliance multiPartCompliance) throws Exception
    {
        startServer(multiPartCompliance);

        byte[] byteArray = new byte[LARGE_MESSAGE_SIZE];
        Arrays.fill(byteArray, (byte)1);
        BytesRequestContent content = new BytesRequestContent(byteArray);

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("largePart", null, HttpFields.EMPTY, content));
        multiPart.close();

        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class, MultiPartFormInputStream.class))
        {
            ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .body(multiPart)
                .send();

            assertEquals(400, response.getStatus());
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
        startServer(MultiPartCompliance.RFC7578);

        String contentString = "the quick brown fox jumps over the lazy dog, " +
            "the quick brown fox jumps over the lazy dog";
        StringRequestContent content = new StringRequestContent(contentString);

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("largePart", null, HttpFields.EMPTY, content));
        multiPart.close();

        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class, MultiPartFormInputStream.class))
        {
            try (InputStreamResponseListener responseStream = new InputStreamResponseListener())
            {
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

                try (InputStream inputStream = new GZIPInputStream(responseStream.getInputStream()))
                {
                    String contentType = headers.get(HttpHeader.CONTENT_TYPE);
                    MultiPartFormInputStream mpis = new MultiPartFormInputStream(inputStream, contentType, null, null);
                    List<Part> parts = new ArrayList<>(mpis.getParts());
                    assertThat(parts.size(), is(1));
                    assertThat(IO.toString(parts.get(0).getInputStream()), is(contentString));
                }
            }
        }
    }
}
