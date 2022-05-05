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

package org.eclipse.jetty.server.handler.gzip;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.FutureFormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GzipHandlerTest
{
    private static final String __content =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. " +
            "Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque " +
            "habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. " +
            "Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam " +
            "at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate " +
            "velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum. " +
            "Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum " +
            "eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa " +
            "sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam " +
            "consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque. " +
            "Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse " +
            "et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.";

    private static final byte[] __bytes = __content.getBytes(StandardCharsets.UTF_8);

    private static final String __micro = __content.substring(0, 10);

    private static final String __contentETag = String.format("W/\"%x\"", __content.hashCode());
    private static final String __contentETagGzip = String.format("W/\"%x" + CompressedContentFormat.GZIP.getEtagSuffix() + "\"", __content.hashCode());
    private static final String __icontent = "BEFORE" + __content + "AFTER";

    private static final MimeTypes __mimeTypes = new MimeTypes();

    private Server _server;
    private LocalConnector _connector;
    private GzipHandler _gziphandler;
    private ContextHandler _contextHandler;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        CheckHandler checkHandler = new CheckHandler();
        _server.setHandler(checkHandler);

        _gziphandler = new GzipHandler();
        _gziphandler.setMinGzipSize(16);
        _gziphandler.setInflateBufferSize(4096);
        checkHandler.setHandler(_gziphandler);

        _contextHandler = new ContextHandler("/ctx");
        _gziphandler.setHandler(_contextHandler);
    }

    public static class MicroHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            response.setHeader("ETag", __contentETag);
            String ifnm = request.getHeaders().get("If-None-Match");
            if (ifnm != null && ifnm.equals(__contentETag))
                Response.writeError(request, response, callback, 304);
            else
            {
                response.write(true, callback, __micro);
            }
        }
    }

    public static class MicroChunkedHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            response.write(false, callback, __micro);
        }
    }

    public static class MimeTypeContentHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            String pathInfo = request.getPathInContext();
            response.setContentType(getContentTypeFromRequest(pathInfo, request));
            response.write(true, callback, "This is content for " + pathInfo + "\n");
        }

        private String getContentTypeFromRequest(String filename, Request request)
        {
            String defaultContentType = "application/octet-stream";
            Fields parameters = Request.extractQueryParameters(request);
            if (parameters.get("type") != null)
                defaultContentType = parameters.get("type").getValue();

            // TODO get mime type from context.
            Context context = request.getContext();
            String contentType = __mimeTypes.getMimeByExtension(filename);
            if (contentType != null)
                return contentType;
            return defaultContentType;
        }
    }

    public static class TestHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            if (HttpMethod.DELETE.is(request.getMethod()))
            {
                doDelete(request, response, callback);
                return;
            }

            Fields parameters = Request.extractQueryParameters(request);
            if (parameters.get("vary") != null)
                response.addHeader("Vary", parameters.get("vary").getValue());
            response.setHeader("ETag", __contentETag);
            String ifnm = request.getHeaders().get("If-None-Match");
            if (ifnm != null && ifnm.equals(__contentETag))
                Response.writeError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
            else
                response.write(true, callback, __content);
        }

        void doDelete(Request request, Response response, Callback callback) throws IOException
        {
            String ifm = request.getHeaders().get("If-Match");
            if (ifm != null && ifm.equals(__contentETag))
                Response.writeError(request, response, callback, HttpStatus.NO_CONTENT_204);
            else
                Response.writeError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
        }
    }

    public static class AsyncHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            Fields parameters = Request.extractQueryParameters(request);
            String writes = parameters.get("writes").getValue();
            AtomicInteger count = new AtomicInteger(writes == null ? 1 : Integer.parseInt(writes));

            response.setContentLength((long)count.get() * __bytes.length);

            Runnable writer = new Runnable()
            {
                @Override
                public void run()
                {
                    if (count.getAndDecrement() == 0)
                        response.write(true, callback);
                    else
                        response.write(false, Callback.from(this), ByteBuffer.wrap(__bytes));
                }
            };

            Context context = request.getContext();
            context.run(writer);
        }
    }

    public static class BufferHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            ByteBuffer buffer = BufferUtil.toBuffer(__bytes).asReadOnlyBuffer();
            response.setContentLength(buffer.remaining());
            response.setContentType("text/plain");
            response.write(true, callback, buffer);
        }
    }

    public static class EchoHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            HttpField contentType = request.getHeaders().getField(HttpHeader.CONTENT_TYPE);
            if (contentType != null)
                response.getHeaders().add(contentType);

            Content.copy(request, response, callback);
        }
    }

    public static class DumpHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            response.setContentType("text/plain");

            Fields parameters = Request.extractQueryParameters(request);
            FutureFormFields futureFormFields = new FutureFormFields(request, StandardCharsets.UTF_8, -1, -1, parameters);
            futureFormFields.run();
            parameters = futureFormFields.get();

            String dump = parameters.stream().map(f -> "%s: %s\n".formatted(f.getName(), f.getValue())).collect(Collectors.joining());
            response.write(true, callback, dump);
        }
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testNotGzipHandler() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/content?vary=Other");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), not(equalToIgnoringCase("gzip")));
        assertThat(response.get("ETag"), is(__contentETag));
        assertThat(response.getCSV("Vary", false), Matchers.contains("Other", "Accept-Encoding"));

        InputStream testIn = new ByteArrayInputStream(response.getContentBytes());
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(__content, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testBlockingResponse() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/content?vary=Accept-Encoding,Other");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.get("ETag"), is(__contentETagGzip));
        assertThat(response.getCSV("Vary", false), Matchers.contains("Accept-Encoding", "Other"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(__content, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testAsyncResponse() throws Exception
    {
        _contextHandler.setHandler(new AsyncHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/async/info?writes=1");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.getCSV("Vary", false), Matchers.contains("Accept-Encoding"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(__content, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testBufferResponse() throws Exception
    {
        _contextHandler.setHandler(new BufferHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/buffer/info");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.getCSV("Vary", false), Matchers.contains("Accept-Encoding"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(__content, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testAsyncLargeResponse() throws Exception
    {
        _contextHandler.setHandler(new AsyncHandler());
        _server.start();

        int writes = 100;
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/async/info?writes=" + writes);
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.getCSV("Vary", false), Matchers.contains("Accept-Encoding"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        byte[] bytes = testOut.toByteArray();

        for (int i = 0; i < writes; i++)
        {
            assertEquals(__content, new String(Arrays.copyOfRange(bytes, i * __bytes.length, (i + 1) * __bytes.length), StandardCharsets.UTF_8), "chunk " + i);
        }
    }

    @Test
    public void testAsyncEmptyResponse() throws Exception
    {
        _contextHandler.setHandler(new AsyncHandler());
        _server.start();

        int writes = 0;
        _gziphandler.setMinGzipSize(0);

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/async/info?writes=" + writes);
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.getCSV("Vary", false), Matchers.contains("Accept-Encoding"));
    }

    @Test
    public void testGzipHandlerWithMultipleAcceptEncodingHeaders() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/content?vary=Accept-Encoding,Other");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "deflate");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.get("ETag"), is(__contentETagGzip));
        assertThat(response.getCSV("Vary", false), Matchers.contains("Accept-Encoding", "Other"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(__content, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testGzipNotMicro() throws Exception
    {
        _contextHandler.setHandler(new MicroHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/micro");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("Accept-Encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat(response.get("ETag"), is(__contentETag));
        assertThat(response.get("Vary"), is("Accept-Encoding"));

        InputStream testIn = new ByteArrayInputStream(response.getContentBytes());
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(__micro, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testGzipNotMicroChunked() throws Exception
    {
        _contextHandler.setHandler(new MicroChunkedHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/microchunked");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "tester");
        request.setHeader("Accept-Encoding", "gzip");

        ByteBuffer rawresponse = _connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(rawresponse));
        response = HttpTester.parseResponse(rawresponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Transfer-Encoding"), containsString("chunked"));
        assertThat(response.get("Content-Encoding"), containsString("gzip"));
        assertThat(response.get("Vary"), is("Accept-Encoding"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(__micro, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testETagNotGzipHandler() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/content");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("If-None-Match", __contentETag);
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(304));
        assertThat(response.get("Content-Encoding"), not(Matchers.equalToIgnoringCase("gzip")));
        assertThat(response.get("ETag"), is(__contentETag));
    }

    @Test
    public void testETagGzipHandler() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/content");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("If-None-Match", __contentETagGzip);
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(304));
        assertThat(response.get("Content-Encoding"), not(Matchers.equalToIgnoringCase("gzip")));
        assertThat(response.get("ETag"), is(__contentETagGzip));
    }

    @Test
    public void testDeleteETagGzipHandler() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("DELETE");
        request.setURI("/ctx/content");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("If-Match", "WrongEtag" + CompressedContentFormat.GZIP.getEtagSuffix());
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), Matchers.is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response.get("Content-Encoding"), not(Matchers.equalToIgnoringCase("gzip")));

        request = HttpTester.newRequest();
        request.setMethod("DELETE");
        request.setURI("/ctx/content");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("If-Match", __contentETagGzip);
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), Matchers.is(HttpStatus.NO_CONTENT_204));
        assertThat(response.get("Content-Encoding"), not(Matchers.equalToIgnoringCase("gzip")));
    }

    @Test
    public void testIncludeExcludeGzipHandlerInflate() throws Exception
    {
        _contextHandler.setHandler(new EchoHandler());
        _server.start();

        _gziphandler.addExcludedInflationPaths("/ctx/echo/exclude");
        _gziphandler.addIncludedInflationPaths("/ctx/echo/include");

        String message = "hello world";
        byte[] gzippedMessage = gzipContent(message);

        // The included path does deflate the content.
        HttpTester.Response response = sendGzipRequest("/ctx/echo/include", message);
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContent(), equalTo(message));

        // The excluded path does not deflate the content.
        response = sendGzipRequest("/ctx/echo/exclude", message);
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentBytes(), equalTo(gzippedMessage));
    }

    private byte[] gzipContent(String content) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.close();
        return baos.toByteArray();
    }

    private HttpTester.Response sendGzipRequest(String uri, String data) throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setURI(uri);
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("Content-Type", "text/plain");
        request.setHeader("Content-Encoding", "gzip");
        request.setContent(gzipContent(data));

        return HttpTester.parseResponse(_connector.getResponse(request.generate()));
    }

    @Test
    public void testAddGetPaths()
    {
        GzipHandler gzip = new GzipHandler();
        gzip.addIncludedPaths("/foo");
        gzip.addIncludedPaths("^/bar.*$");

        String[] includedPaths = gzip.getIncludedPaths();
        assertThat("Included Paths.size", includedPaths.length, is(2));
        assertThat("Included Paths", Arrays.asList(includedPaths), contains("/foo", "^/bar.*$"));
    }

    @Test
    public void testGzipRequest() throws Exception
    {
        _contextHandler.setHandler(new EchoHandler());
        _server.start();

        String data = "Hello Nice World! ";
        for (int i = 0; i < 10; ++i)
        {
            data += data;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("POST");
        request.setURI("/ctx/echo");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("Content-Type", "text/plain");
        request.setHeader("Content-Encoding", "gzip");
        request.setContent(bytes);

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is(data));
    }

    @Test
    public void testGzipRequestChunked() throws Exception
    {
        _contextHandler.setHandler(new EchoHandler());
        _server.start();

        String data = "Hello Nice World! ";
        for (int i = 0; i < 10; ++i)
        {
            data += data;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("POST");
        request.setURI("/ctx/echo");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "tester");
        request.setHeader("Content-Type", "text/plain");
        request.setHeader("Content-Encoding", "gzip");
        request.add("Transfer-Encoding", "chunked");
        request.setContent(bytes);
        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is(data));
    }

    @Test
    public void testGzipFormRequest() throws Exception
    {
        _contextHandler.setHandler(new DumpHandler());
        _server.start();

        String data = "name=value";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("POST");
        request.setURI("/ctx/dump");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        request.setHeader("Content-Encoding", "gzip");
        request.setContent(bytes);

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("name: value\n"));
    }

    @Test
    public void testGzipBomb() throws Exception
    {
        _contextHandler.setHandler(new EchoHandler());
        _server.start();

        byte[] data = new byte[512 * 1024];
        Arrays.fill(data, (byte)'X');

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data);
        output.close();
        byte[] bytes = baos.toByteArray();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("POST");
        request.setURI("/ctx/echo");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("Content-Type", "text/plain");
        request.setHeader("Content-Encoding", "gzip");
        request.setContent(bytes);

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        // TODO need to test back pressure works

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentBytes().length, is(512 * 1024));
    }

    @Test
    public void testGzipExcludeNewMimeType() throws Exception
    {
        _contextHandler.setHandler(new MimeTypeContentHandler());
        _server.start();

        // setting all excluded mime-types to a mimetype new mime-type
        // Note: this mime-type does not exist in MimeTypes object.
        _gziphandler.setExcludedMimeTypes("image/webfoo");

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        // Request something that is not present on MimeTypes and is also
        // excluded by GzipHandler configuration
        request.setMethod("GET");
        request.setURI("/ctx/mimetypes/foo.webfoo?type=image/webfoo");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "tester");
        request.setHeader("Accept", "*/*");
        request.setHeader("Accept-Encoding", "gzip"); // allow compressed responses
        request.setHeader("Connection", "close");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat("Should not be compressed with gzip", response.get("Content-Encoding"), nullValue());
        assertThat(response.get("ETag"), nullValue());
        assertThat(response.get("Vary"), nullValue());

        // Request something that is present on MimeTypes and is also compressible
        // by the GzipHandler configuration
        request.setMethod("GET");
        request.setURI("/ctx/mimetypes/zed.txt");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "tester");
        request.setHeader("Accept", "*/*");
        request.setHeader("Accept-Encoding", "gzip"); // allow compressed responses
        request.setHeader("Connection", "close");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), containsString("gzip"));
        assertThat(response.get("ETag"), nullValue());
        assertThat(response.get("Vary"), is("Accept-Encoding"));
    }

    public static class CheckHandler extends Handler.Wrapper
    {
        @Override
        public Request.Processor handle(Request request) throws Exception
        {
            Request.Processor processor = super.handle(request);
            if (processor == null)
                return null;

            return (req, res, cb) ->
            {
                if (req.getHeaders().get("X-Content-Encoding") != null)
                    assertEquals(-1, req.getContentLength());
                else if (req.getContentLength() >= 0)
                    MatcherAssert.assertThat(req.getHeaders().get("X-Content-Encoding"), nullValue());
                processor.process(req, res, cb);
            };
        }
    }
}
