//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.servlet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("serial")
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

    private Server _server;
    private LocalConnector _connector;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setExcludedAgentPatterns();
        gzipHandler.setMinGzipSize(16);
        gzipHandler.setInflateBufferSize(4096);

        ServletContextHandler context = new ServletContextHandler(gzipHandler, "/ctx");
        ServletHandler servlets = context.getServletHandler();

        _server.setHandler(gzipHandler);
        gzipHandler.setHandler(context);
        servlets.addServletWithMapping(MicroServlet.class, "/micro");
        servlets.addServletWithMapping(MicroChunkedServlet.class, "/microchunked");
        servlets.addServletWithMapping(TestServlet.class, "/content");
        servlets.addServletWithMapping(ForwardServlet.class, "/forward");
        servlets.addServletWithMapping(IncludeServlet.class, "/include");
        servlets.addServletWithMapping(EchoServlet.class, "/echo/*");
        servlets.addServletWithMapping(DumpServlet.class, "/dump/*");
        servlets.addServletWithMapping(AsyncServlet.class, "/async/*");
        servlets.addServletWithMapping(BufferServlet.class, "/buffer/*");
        servlets.addFilterWithMapping(CheckFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        _server.start();
    }

    public static class MicroServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            response.setHeader("ETag", __contentETag);
            String ifnm = req.getHeader("If-None-Match");
            if (ifnm != null && ifnm.equals(__contentETag))
                response.sendError(304);
            else
            {
                PrintWriter writer = response.getWriter();
                writer.write(__micro);
            }
        }
    }

    public static class MicroChunkedServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            PrintWriter writer = response.getWriter();
            writer.write(__micro);
            response.flushBuffer();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            if (req.getParameter("vary") != null)
                response.addHeader("Vary", req.getParameter("vary"));
            response.setHeader("ETag", __contentETag);
            String ifnm = req.getHeader("If-None-Match");
            if (ifnm != null && ifnm.equals(__contentETag))
            {
                response.setStatus(304);
                response.flushBuffer();
            }
            else
            {
                PrintWriter writer = response.getWriter();
                writer.write(__content);
            }
        }

        @Override
        protected void doDelete(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            String ifm = req.getHeader("If-Match");
            if (ifm != null && ifm.equals(__contentETag))
                response.sendError(HttpServletResponse.SC_NO_CONTENT);
            else
                response.sendError(HttpServletResponse.SC_NOT_MODIFIED);
        }
    }

    public static class AsyncServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            String writes = req.getParameter("writes");
            final AsyncContext context = req.startAsync();
            final ServletOutputStream out = response.getOutputStream();

            out.setWriteListener(new WriteListener()
            {
                int count = writes == null ? 1 : Integer.valueOf(writes);

                {
                    response.setContentLength(count * __bytes.length);
                }

                @Override
                public void onWritePossible() throws IOException
                {
                    while (out.isReady())
                    {
                        if (count-- == 0)
                        {
                            out.close();
                            break;
                        }

                        out.write(__bytes);
                    }
                }

                @Override
                public void onError(Throwable t)
                {
                    t.printStackTrace();
                }
            });
        }
    }

    public static class BufferServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            HttpOutput out = (HttpOutput)response.getOutputStream();
            ByteBuffer buffer = BufferUtil.toBuffer(__bytes).asReadOnlyBuffer();
            response.setContentLength(buffer.remaining());
            response.setContentType("text/plain");
            out.write(buffer);
        }
    }

    public static class EchoServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType(req.getContentType());
            IO.copy(req.getInputStream(), response.getOutputStream());
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            doGet(req, response);
        }
    }

    public static class DumpServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType("text/plain");
            for (Enumeration<String> e = req.getParameterNames(); e.hasMoreElements(); )
            {
                String n = e.nextElement();
                response.getWriter().printf("%s: %s\n", n, req.getParameter(n));
            }
        }
    }

    public static class ForwardServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            getServletContext().getRequestDispatcher("/content").forward(request, response);
        }
    }

    public static class IncludeServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.getWriter().write("BEFORE");
            getServletContext().getRequestDispatcher("/content").include(request, response);
            response.getWriter().write("AFTER");
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
        assertThat(response.getValuesList("Vary"), Matchers.contains("Other", "Accept-Encoding"));

        InputStream testIn = new ByteArrayInputStream(response.getContentBytes());
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(__content, testOut.toString("UTF8"));
    }

    @Test
    public void testBlockingResponse() throws Exception
    {
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

        assertEquals(__content, testOut.toString("UTF8"));
    }

    @Test
    public void testAsyncResponse() throws Exception
    {
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

        assertEquals(__content, testOut.toString("UTF8"));
    }

    @Test
    public void testBufferResponse() throws Exception
    {
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

        assertEquals(__content, testOut.toString("UTF8"));
    }

    @Test
    public void testAsyncLargeResponse() throws Exception
    {
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
        int writes = 0;
        _server.getChildHandlerByClass(GzipHandler.class).setMinGzipSize(0);

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

        assertEquals(__content, testOut.toString("UTF8"));
    }

    @Test
    public void testGzipNotMicro() throws Exception
    {
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

        assertEquals(__micro, testOut.toString("UTF8"));
    }

    @Test
    public void testGzipNotMicroChunked() throws Exception
    {
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

        assertEquals(__micro, testOut.toString("UTF8"));
    }

    @Test
    public void testETagNotGzipHandler() throws Exception
    {
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
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("DELETE");
        request.setURI("/ctx/content");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("If-Match", "WrongEtag" + CompressedContentFormat.GZIP.getEtagSuffix());
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(HttpServletResponse.SC_NOT_MODIFIED));
        assertThat(response.get("Content-Encoding"), not(Matchers.equalToIgnoringCase("gzip")));

        request = HttpTester.newRequest();
        request.setMethod("DELETE");
        request.setURI("/ctx/content");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("If-Match", __contentETagGzip);
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(HttpServletResponse.SC_NO_CONTENT));
        assertThat(response.get("Content-Encoding"), not(Matchers.equalToIgnoringCase("gzip")));
    }

    @Test
    public void testForwardGzipHandler() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");
        request.setURI("/ctx/forward");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.get("ETag"), is(__contentETagGzip));
        assertThat(response.get("Vary"), is("Accept-Encoding"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(__content, testOut.toString("UTF8"));
    }

    @Test
    public void testIncludeGzipHandler() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");
        request.setURI("/ctx/include");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.get("ETag"), nullValue());
        assertThat(response.get("Vary"), is("Accept-Encoding"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(__icontent, testOut.toString("UTF8"));
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

    public static class CheckFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            if (request.getParameter("X-Content-Encoding") != null)
                assertEquals(-1, request.getContentLength());
            else if (request.getContentLength() >= 0)
                assertThat(request.getParameter("X-Content-Encoding"), Matchers.nullValue());
            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {
        }
    }
}
