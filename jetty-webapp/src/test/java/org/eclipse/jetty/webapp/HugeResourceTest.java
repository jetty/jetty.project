//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.MultiPartContentProvider;
import org.eclipse.jetty.client.util.PathContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.MultiPartFormDataCompliance;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@Tag("large-disk-resource")
public class HugeResourceTest
{
    private static final long KB = 1024;
    private static final long MB = 1024 * KB;
    private static final long GB = 1024 * MB;
    public static Path staticBase;
    public static Path outputDir;
    public static Path multipartTempDir;

    public Server server;
    public HttpClient client;

    @BeforeAll
    public static void prepareStaticFiles() throws IOException
    {
        staticBase = MavenTestingUtils.getTargetTestingPath(HugeResourceTest.class.getSimpleName() + "-static-base");
        FS.ensureDirExists(staticBase);

        FileStore baseFileStore = Files.getFileStore(staticBase);

        // Calculation is (1GB + 4GB + 10GB) == 15GB
        // once for static source files
        // once again for multipart/form temp files
        // for a total of (at least) 30GB needed.

        Assumptions.assumeTrue(baseFileStore.getUnallocatedSpace() > 30 * GB,
            String.format("FileStore %s of %s needs at least 30GB of free space for this test (only had %,.2fGB)",
                baseFileStore, staticBase, (double)(baseFileStore.getUnallocatedSpace() / GB)));

        makeStaticFile(staticBase.resolve("test-1g.dat"), 1 * GB);
        makeStaticFile(staticBase.resolve("test-4g.dat"), 4 * GB);
        makeStaticFile(staticBase.resolve("test-10g.dat"), 10 * GB);

        outputDir = MavenTestingUtils.getTargetTestingPath(HugeResourceTest.class.getSimpleName() + "-outputdir");
        FS.ensureEmpty(outputDir);

        multipartTempDir = MavenTestingUtils.getTargetTestingPath(HugeResourceTest.class.getSimpleName() + "-multipart-tmp");
        FS.ensureEmpty(multipartTempDir);
    }

    public static Stream<Arguments> staticFiles()
    {
        ArrayList<Arguments> ret = new ArrayList<>();

        ret.add(Arguments.of("test-1g.dat", 1 * GB));
        ret.add(Arguments.of("test-4g.dat", 4 * GB));
        ret.add(Arguments.of("test-10g.dat", 10 * GB));

        return ret.stream();
    }

    @AfterAll
    public static void cleanupTestFiles()
    {
        if (staticBase != null)
            FS.ensureDeleted(staticBase);
        if (outputDir != null)
            FS.ensureDeleted(outputDir);
        if (multipartTempDir != null)
            FS.ensureDeleted(multipartTempDir);
    }

    private static void makeStaticFile(Path staticFile, long size) throws IOException
    {
        byte[] buf = new byte[(int)(1 * MB)];
        Arrays.fill(buf, (byte)'x');
        ByteBuffer src = ByteBuffer.wrap(buf);

        if (Files.exists(staticFile) && Files.size(staticFile) == size)
        {
            // all done, nothing left to do.
            return;
        }

        System.err.printf("Creating %,d byte file: %s ...%n", size, staticFile.getFileName());
        try (SeekableByteChannel channel = Files.newByteChannel(staticFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
        {
            long remaining = size;
            while (remaining > 0)
            {
                ByteBuffer slice = src.slice();
                int len = buf.length;
                if (remaining < Integer.MAX_VALUE)
                {
                    len = Math.min(buf.length, (int)remaining);
                    slice.limit(len);
                }

                channel.write(slice);
                remaining -= len;
            }
        }
        System.err.println(" Done");
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setMultiPartFormDataCompliance(MultiPartFormDataCompliance.RFC7578);
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(0);
        server.addConnector(connector);

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        context.setBaseResource(new PathResource(staticBase));

        context.addServlet(PostServlet.class, "/post");
        context.addServlet(ChunkedServlet.class, "/chunked/*");

        String location = multipartTempDir.toString();
        long maxFileSize = Long.MAX_VALUE;
        long maxRequestSize = Long.MAX_VALUE;
        int fileSizeThreshold = (int)(2 * MB);

        MultipartConfigElement multipartConfig = new MultipartConfigElement(location, maxFileSize, maxRequestSize, fileSizeThreshold);
        ServletHolder holder = context.addServlet(MultipartServlet.class, "/multipart");
        holder.getRegistration().setMultipartConfig(multipartConfig);

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @ParameterizedTest
    @MethodSource("staticFiles")
    public void testDownloadStatic(String filename, long expectedSize) throws Exception
    {
        URI destUri = server.getURI().resolve("/" + filename);
        InputStreamResponseListener responseListener = new InputStreamResponseListener();

        Request request = client.newRequest(destUri)
            .method(HttpMethod.GET);
        request.send(responseListener);
        Response response = responseListener.get(5, TimeUnit.SECONDS);

        assertThat("HTTP Response Code", response.getStatus(), is(200));
        // dumpResponse(response);

        String contentLength = response.getHeaders().get(HttpHeader.CONTENT_LENGTH);
        long contentLengthLong = Long.parseLong(contentLength);
        assertThat("Http Response Header: \"Content-Length: " + contentLength + "\"", contentLengthLong, is(expectedSize));

        try (ByteCountingOutputStream out = new ByteCountingOutputStream();
             InputStream in = responseListener.getInputStream())
        {
            IO.copy(in, out);
            assertThat("Downloaded Files Size: " + filename, out.getCount(), is(expectedSize));
        }
    }

    @ParameterizedTest
    @MethodSource("staticFiles")
    public void testDownloadChunked(String filename, long expectedSize) throws Exception
    {
        URI destUri = server.getURI().resolve("/chunked/" + filename);
        InputStreamResponseListener responseListener = new InputStreamResponseListener();

        Request request = client.newRequest(destUri)
            .method(HttpMethod.GET);
        request.send(responseListener);
        Response response = responseListener.get(5, TimeUnit.SECONDS);

        assertThat("HTTP Response Code", response.getStatus(), is(200));
        // dumpResponse(response);

        String transferEncoding = response.getHeaders().get(HttpHeader.TRANSFER_ENCODING);
        assertThat("Http Response Header: \"Transfer-Encoding\"", transferEncoding, is("chunked"));

        try (ByteCountingOutputStream out = new ByteCountingOutputStream();
             InputStream in = responseListener.getInputStream())
        {
            IO.copy(in, out);
            assertThat("Downloaded Files Size: " + filename, out.getCount(), is(expectedSize));
        }
    }

    @ParameterizedTest
    @MethodSource("staticFiles")
    public void testHeadStatic(String filename, long expectedSize) throws Exception
    {
        URI destUri = server.getURI().resolve("/" + filename);
        InputStreamResponseListener responseListener = new InputStreamResponseListener();

        Request request = client.newRequest(destUri)
            .method(HttpMethod.HEAD);
        request.send(responseListener);
        Response response = responseListener.get(5, TimeUnit.SECONDS);

        try (InputStream in = responseListener.getInputStream())
        {
            assertThat(in.read(), is(-1));
        }

        assertThat("HTTP Response Code", response.getStatus(), is(200));
        // dumpResponse(response);

        String contentLength = response.getHeaders().get(HttpHeader.CONTENT_LENGTH);
        long contentLengthLong = Long.parseLong(contentLength);
        assertThat("Http Response Header: \"Content-Length: " + contentLength + "\"", contentLengthLong, is(expectedSize));
    }

    @ParameterizedTest
    @MethodSource("staticFiles")
    public void testHeadChunked(String filename, long expectedSize) throws Exception
    {
        URI destUri = server.getURI().resolve("/chunked/" + filename);
        InputStreamResponseListener responseListener = new InputStreamResponseListener();

        Request request = client.newRequest(destUri)
            .method(HttpMethod.HEAD);
        request.send(responseListener);
        Response response = responseListener.get(5, TimeUnit.SECONDS);

        try (InputStream in = responseListener.getInputStream())
        {
            assertThat(in.read(), is(-1));
        }

        assertThat("HTTP Response Code", response.getStatus(), is(200));
        // dumpResponse(response);

        String transferEncoding = response.getHeaders().get(HttpHeader.TRANSFER_ENCODING);
        assertThat("Http Response Header: \"Transfer-Encoding\"", transferEncoding, is("chunked"));
    }

    @ParameterizedTest
    @MethodSource("staticFiles")
    public void testUpload(String filename, long expectedSize) throws Exception
    {
        Path inputFile = staticBase.resolve(filename);

        PathContentProvider pathContentProvider = new PathContentProvider(inputFile);
        URI destUri = server.getURI().resolve("/post");
        Request request = client.newRequest(destUri).method(HttpMethod.POST).content(pathContentProvider);
        ContentResponse response = request.send();
        assertThat("HTTP Response Code", response.getStatus(), is(200));
        // dumpResponse(response);

        String responseBody = response.getContentAsString();
        assertThat("Response", responseBody, containsString("bytes-received=" + expectedSize));
    }

    @ParameterizedTest
    @MethodSource("staticFiles")
    public void testUploadMultipart(String filename, long expectedSize) throws Exception
    {
        MultiPartContentProvider multipart = new MultiPartContentProvider();
        Path inputFile = staticBase.resolve(filename);
        String name = String.format("file-%d", expectedSize);
        multipart.addFilePart(name, filename, new PathContentProvider(inputFile), null);

        URI destUri = server.getURI().resolve("/multipart");
        client.setIdleTimeout(90_000);
        Request request = client.newRequest(destUri).method(HttpMethod.POST).content(multipart);
        ContentResponse response = request.send();
        assertThat("HTTP Response Code", response.getStatus(), is(200));
        // dumpResponse(response);

        String responseBody = response.getContentAsString();
        String expectedResponse = String.format("part[%s].size=%d", name, expectedSize);
        assertThat("Response", responseBody, containsString(expectedResponse));
    }

    private void dumpResponse(Response response)
    {
        System.out.printf("  %s %d %s%n", response.getVersion(), response.getStatus(), response.getReason());
        response.getHeaders().forEach((field) -> System.out.printf("  %s%n", field));
    }

    public static class ByteCountingOutputStream extends OutputStream
    {
        private long count = 0;

        public long getCount()
        {
            return count;
        }

        @Override
        public void write(int b)
        {
            count++;
        }

        @Override
        public void write(byte[] b)
        {
            count += b.length;
        }

        @Override
        public void write(byte[] b, int off, int len)
        {
            count += len;
        }
    }

    public static class PostServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            ByteCountingOutputStream byteCounting = new ByteCountingOutputStream();
            IO.copy(req.getInputStream(), byteCounting);
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            resp.getWriter().printf("bytes-received=%d%n", byteCounting.getCount());
        }
    }

    public static class ChunkedServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            URL resource = req.getServletContext().getResource(req.getPathInfo());
            OutputStream output = resp.getOutputStream();
            try (InputStream input = resource.openStream())
            {
                resp.setContentType("application/octet-stream");
                resp.flushBuffer();
                IO.copy(input, output);
            }
        }
    }

    public static class MultipartServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            PrintWriter out = resp.getWriter();

            req.getParts().forEach((part) ->
            {
                out.printf("part[%s].filename=%s%n", part.getName(), part.getSubmittedFileName());
                out.printf("part[%s].size=%d%n", part.getName(), part.getSize());
                try (InputStream inputStream = part.getInputStream();
                     ByteCountingOutputStream byteCounting = new ByteCountingOutputStream())
                {
                    IO.copy(inputStream, byteCounting);
                    out.printf("part[%s].inputStream.length=%d%n", part.getName(), byteCounting.getCount());
                }
                catch (IOException e)
                {
                    e.printStackTrace(out);
                }
            });
        }
    }
}
