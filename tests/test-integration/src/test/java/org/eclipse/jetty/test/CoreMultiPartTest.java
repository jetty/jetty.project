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

package org.eclipse.jetty.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.MultiPartRequestContent;
import org.eclipse.jetty.client.OutputStreamRequestContent;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.content.InputStreamContentSource;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class CoreMultiPartTest
{
    private static final int MAX_FILE_SIZE = 512 * 1024;

    private Server server;
    private ServerConnector connector;
    private HttpClient client;
    private Path tmpDir;
    private MultiPartConfig config;

    @BeforeEach
    public void before() throws Exception
    {
        tmpDir = Files.createTempDirectory(CoreMultiPartTest.class.getSimpleName());
    }

    private MultiPartConfig multiPartConfig(Path location, int maxFormKeys, long maxRequestSize, long maxFileSize, long fileSizeThreshold)
    {
        return new MultiPartConfig.Builder()
            .location(location)
            .maxParts(maxFormKeys)
            .maxSize(maxRequestSize)
            .maxPartSize(maxFileSize)
            .maxMemoryPartSize(fileSizeThreshold)
            .useFilesForPartsWithoutFileName(true)
            .build();
    }

    private void start(Handler handler, MultiPartConfig config) throws Exception
    {
        this.config = config == null ? multiPartConfig(tmpDir, -1, -1, MAX_FILE_SIZE, 0) : config;
        server = new Server(null, null, null);
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("multipart/form-data");
        gzipHandler.setMinGzipSize(32);
        server.insertHandler(gzipHandler);

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

    private MultiPartFormData.Parts getParts(Request request, MultiPartConfig config)
    {
        try
        {
            String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
            return MultiPartFormData.from(request, request, contentType, config).get();
        }
        catch (Throwable t)
        {
            throw new BadMessageException("bad multipart", t.getCause());
        }
    }

    @Test
    public void testLargePart() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                getParts(request, config);
                callback.succeeded();
                return true;
            }
        }, multiPartConfig(null, -1, -1, 1024 * 1024, -1));

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

            // The write side will eventually throw because connection is closed.
            assertThrows(Throwable.class, () ->
            {
                // Write large amount of content to the part.
                byte[] byteArray = new byte[1024 * 1024];
                Arrays.fill(byteArray, (byte)1);
                for (int i = 0; i < 1024 * 2; i++)
                {
                    content.getOutputStream().write(byteArray);
                }
                content.close();
            });

            assert400orEof(listener, responseContent -> assertThat(responseContent, containsString("400")));
        }
    }

    @Test
    public void testManyParts() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                getParts(request, config);
                callback.succeeded();
                return true;
            }
        }, multiPartConfig(null, 1024, -1, -1, -1));

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

    @Test
    public void testMaxRequestSize() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                getParts(request, config);
                callback.succeeded();
                return true;
            }
        }, multiPartConfig(tmpDir, -1, 1024, -1, 1024 * 1024 * 8));

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
            org.eclipse.jetty.client.Response response = listener.get(60, TimeUnit.SECONDS);
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

    @Test
    public void testSimpleMultiPart() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                MultiPartFormData.Parts parts = getParts(request, config);
                assertNotNull(parts);
                assertEquals(1, parts.size());
                MultiPart.Part part = parts.iterator().next();
                assertEquals("part1", part.getName());
                HttpFields fields = part.getHeaders();
                assertNotNull(fields);
                assertEquals(2, fields.size());
                InputStream inputStream = Content.Source.asInputStream(part.getContentSource());
                String content1 = IO.toString(inputStream, UTF_8);
                assertEquals("content1", content1);

                callback.succeeded();
                return true;
            }
        }, null);

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

        // Should throw as the max file size is exceeded.
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                getParts(request, config);
                callback.succeeded();
                return true;
            }
        }, null);

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("largePart", "largeFile.bin", HttpFields.EMPTY, new BytesRequestContent(bytes)));
        multiPart.close();

        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
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

    @Test
    public void testDefaultTempDirectory() throws Exception
    {
        start(new Handler.Abstract()
        {
          @Override
          public boolean handle(Request request, Response response, Callback callback) throws Exception
          {
              MultiPartConfig conf = Request.getMultiPartConfig(request, config.getLocation())
                  .maxParts(config.getMaxParts())
                  .maxSize(config.getMaxSize())
                  .maxPartSize(config.getMaxPartSize())
                  .maxMemoryPartSize(config.getMaxMemoryPartSize())
                  .useFilesForPartsWithoutFileName(config.isUseFilesForPartsWithoutFileName())
                  .build();
              MultiPartFormData.Parts parts = getParts(request, conf);
              assertNotNull(parts);
              assertEquals(1, parts.size());
              MultiPart.Part part = parts.iterator().next();
              assertEquals("part1", part.getName());
              HttpFields headers = part.getHeaders();
              assertNotNull(headers);
              assertEquals(2, headers.size());
              InputStream inputStream = Content.Source.asInputStream(part.getContentSource());
              String content1 = IO.toString(inputStream, UTF_8);
              assertEquals("content1", content1);

              callback.succeeded();
              return true;
          }
        }, multiPartConfig(null, -1, MAX_FILE_SIZE, -1, 0));

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
    public void testMultiPartGzip() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);

                MultiPartRequestContent echoParts = new MultiPartRequestContent(MultiPart.extractBoundary(contentType));
                MultiPartFormData.Parts servletParts = getParts(request, config);
                for (MultiPart.Part part : servletParts)
                {
                    HttpFields.Mutable partHeaders = HttpFields.build();
                    for (HttpField field : part.getHeaders())
                        partHeaders.add(field);

                    echoParts.addPart(new MultiPart.ContentSourcePart(part.getName(), part.getFileName(), partHeaders, part.getContentSource()));
                }
                echoParts.close();
                IO.copy(Content.Source.asInputStream(echoParts), Content.Sink.asOutputStream(response));

                callback.succeeded();
                return true;
            }
        }, null);

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

            org.eclipse.jetty.client.Response response = responseStream.get(5, TimeUnit.SECONDS);
            HttpFields headers = response.getHeaders();
            assertThat(headers.get(HttpHeader.CONTENT_TYPE), startsWith("multipart/form-data"));
            assertThat(headers.get(HttpHeader.CONTENT_ENCODING), is("gzip"));

            String contentType = headers.get(HttpHeader.CONTENT_TYPE);
            String boundary = MultiPart.extractBoundary(contentType);
            InputStream inputStream = new GZIPInputStream(responseStream.getInputStream());
            MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary);
            formData.setMaxParts(1);
            formData.setMaxMemoryFileSize(-1);
            MultiPartFormData.Parts parts = formData.parse(new InputStreamContentSource(inputStream)).join();

            assertThat(parts.size(), is(1));
            assertThat(parts.get(0).getContentAsString(UTF_8), is(contentString));
        }
    }

    @Test
    public void testDoubleReadFromPart() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                PrintWriter writer = new PrintWriter(Content.Sink.asOutputStream(response));
                for (MultiPart.Part part : getParts(request, config))
                {
                    String partContent = IO.toString(Content.Source.asInputStream(part.getContentSource()));
                    writer.println("Part: name=" + part.getName() + ", size=" + part.getLength() + ", content=" + partContent);

                    // We can only consume the getContentSource() once so we must use newContentSource().
                    partContent = IO.toString(Content.Source.asInputStream(part.newContentSource()));
                    writer.println("Part: name=" + part.getName() + ", size=" + part.getLength() + ", content=" + partContent);
                }

                writer.close();
                callback.succeeded();
                return true;
            }
        }, null);

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
}
