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

package org.eclipse.jetty.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GzipWithSendErrorTest
{
    private static final Logger LOG = Log.getLogger(GzipWithSendErrorTest.class);
    private Server server;
    private HttpClient client;
    private ServerConnector connector;

    @BeforeEach
    public void setup() throws Exception
    {
        server = new Server();

        connector = new ServerConnector(server);
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
            .content(new BytesContentProvider("text/plain", compressed("normal-A")))
            .send();

        assertEquals(200, response.getStatus(), "Response status on /submit (normal-A)");
        assertEquals("normal-A", response.getContentAsString(), "Response content on /submit (normal-A)");

        response = client.newRequest(serverURI.resolve("/fail"))
            .method(HttpMethod.POST)
            .header(HttpHeader.CONTENT_ENCODING, "gzip")
            .content(new BytesContentProvider("text/plain", compressed("normal-B")))
            .send();

        assertEquals(400, response.getStatus(), "Response status on /fail (normal-B)");
        assertThat("Response content on /fail (normal-B)", response.getContentAsString(), containsString("<title>Error 400 Bad Request</title>"));

        response = client.newRequest(serverURI.resolve("/submit"))
            .method(HttpMethod.POST)
            .header(HttpHeader.CONTENT_ENCODING, "gzip")
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

    /**
     * Make request with compressed content.
     * <p>
     * Request contains (roughly) 1 MB of request network data.
     * Which unpacks to 1 GB of zeros.
     * </p>
     * <p>
     * This test is to ensure that consumeAll only reads the network data,
     * and doesn't process it through the interceptors.
     * </p>
     */
    @Test
    public void testGzipConsumeAllContentLengthBlocking() throws Exception
    {
        URI serverURI = server.getURI();

        CountDownLatch serverRequestCompleteLatch = new CountDownLatch(1);
        // count of bytes against network read
        AtomicLong inputBytesIn = new AtomicLong(0L);
        AtomicLong inputContentReceived = new AtomicLong(0L);
        // count of bytes against API read
        AtomicLong inputContentConsumed = new AtomicLong(0L);

        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onComplete(Request request)
            {
                HttpConnection connection = (HttpConnection)request.getHttpChannel().getConnection();
                HttpInput httpInput = request.getHttpInput();
                inputContentConsumed.set(httpInput.getContentConsumed());
                inputContentReceived.set(httpInput.getContentReceived());
                inputBytesIn.set(connection.getBytesIn());
                serverRequestCompleteLatch.countDown();
            }
        });

        // This is a doubly-compressed (with gzip) test resource.
        // There's no point putting into SCM the full 1MB file input file.
        // So the zeros.gz.gz is committed.  This file uses full sized gzip
        // headers and data segments.  Java's Gzip/Deflate handling cannot
        // produce a gzip data stream like this.
        // This test resource will unpack to
        // -rw-rw-r-- 1 joakim joakim 1,042,069 Nov 19 10:14 zeros.gz
        // That will unpack to
        // -rw-rw-r-- 1 joakim joakim 1,073,741,824 Nov 19 10:14 zeros
        Path zerosCompressed = MavenTestingUtils.getTestResourcePathFile("zeros.gz.gz");
        byte[] compressedRequest;
        try (InputStream in = Files.newInputStream(zerosCompressed);
             GZIPInputStream gzipIn = new GZIPInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            IO.copy(gzipIn, out);
            compressedRequest = out.toByteArray();
        }

        // We want to write a request with request body content size / length
        // that will exceed the various buffers in the network, the client, the server,
        // etc.
        int sizeActuallySent = compressedRequest.length / 2;
        ByteBuffer start = ByteBuffer.wrap(compressedRequest, 0, sizeActuallySent);

        // Using deferred content to allow us to write SOME of the request body content
        // but not all of it (yet)
        // We override the getLength to ensure that Content-Length is used.
        DeferredContentProvider contentProvider = new DeferredContentProvider(start)
        {
            @Override
            public long getLength()
            {
                return compressedRequest.length;
            }
        };
        AtomicReference<Response> clientResponseRef = new AtomicReference<>();
        CountDownLatch clientResponseSuccessLatch = new CountDownLatch(1);
        CountDownLatch clientResultComplete = new CountDownLatch(1);

        client.newRequest(serverURI.resolve("/fail"))
            .method(HttpMethod.POST)
            .header(HttpHeader.CONTENT_TYPE, "application/octet-stream")
            .header(HttpHeader.CONTENT_ENCODING, "gzip")
            .content(contentProvider)
            .onResponseSuccess((response) ->
            {
                clientResponseRef.set(response);
                clientResponseSuccessLatch.countDown();
            })
            .send((result) -> clientResultComplete.countDown());

        assertTrue(clientResponseSuccessLatch.await(5, TimeUnit.SECONDS), "Result not received");
        Response response = clientResponseRef.get();
        assertEquals(400, response.getStatus(), "Response status on /fail");

        // We expect the server to set `Connection: close`, as the request body content isn't fully sent (yet)
        assertEquals("close", response.getHeaders().get(HttpHeader.CONNECTION), "Response Connection header");

        // Await for server side to complete the request
        assertTrue(serverRequestCompleteLatch.await(5, TimeUnit.SECONDS), "Request complete never occurred?");

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Input Content Consumed: %,d%n", inputContentConsumed.get());
            LOG.debug("Input Content Received: %,d%n", inputContentReceived.get());
            LOG.debug("Input BytesIn Count: %,d%n", inputBytesIn.get());
        }

        // Servlet didn't read body content
        assertThat("Request Input Content Consumed not have been used", inputContentConsumed.get(), is(0L));
        // Network reads
        assertThat("Request Input Content Received should have seen content", inputContentReceived.get(), greaterThan(0L));
        assertThat("Request Input Content Received less then initial buffer", inputContentReceived.get(), lessThanOrEqualTo((long)sizeActuallySent));
        assertThat("Request Connection BytesIn should have some minimal data", inputBytesIn.get(), greaterThanOrEqualTo(1024L));
        long requestBytesSent = sizeActuallySent + 512; // Take into account headers and chunked metadata.
        assertThat("Request Connection BytesIn read should not have read all of the data", inputBytesIn.get(), lessThanOrEqualTo(requestBytesSent));

        // Now use the deferred content to complete writing of the request body content
        contentProvider.offer(ByteBuffer.wrap(compressedRequest, sizeActuallySent, compressedRequest.length - sizeActuallySent));
        contentProvider.close();

        assertTrue(clientResultComplete.await(5, TimeUnit.SECONDS));
    }

    /**
     * Make request with compressed content.
     * <p>
     * Request contains (roughly) 1 MB of request network data.
     * Which unpacks to 1 GB of zeros.
     * </p>
     * <p>
     * This test is to ensure that consumeAll only reads the network data,
     * and doesn't process it through the interceptors.
     * </p>
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testGzipConsumeAllChunkedBlockingOnLastBuffer(boolean read) throws Exception
    {
        URI serverURI = server.getURI();

        CountDownLatch serverRequestCompleteLatch = new CountDownLatch(1);
        // count of bytes against network read
        AtomicLong inputBytesIn = new AtomicLong(0L);
        AtomicLong inputContentReceived = new AtomicLong(0L);
        // count of bytes against API read
        AtomicLong inputContentConsumed = new AtomicLong(0L);

        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onComplete(Request request)
            {
                HttpConnection connection = (HttpConnection)request.getHttpChannel().getConnection();
                HttpInput httpInput = request.getHttpInput();
                inputContentConsumed.set(httpInput.getContentConsumed());
                inputContentReceived.set(httpInput.getContentReceived());
                inputBytesIn.set(connection.getBytesIn());
                serverRequestCompleteLatch.countDown();
            }
        });

        // This is a doubly-compressed (with gzip) test resource.
        // There's no point putting into SCM the full 1MB file input file.
        // So the zeros.gz.gz is committed.  This file uses full sized gzip
        // headers and data segments.  Java's Gzip/Deflate handling cannot
        // produce a gzip data stream like this.
        // This test resource will unpack to
        // -rw-rw-r-- 1 joakim joakim 1,042,069 Nov 19 10:14 zeros.gz
        // That will unpack to
        // -rw-rw-r-- 1 joakim joakim 1,073,741,824 Nov 19 10:14 zeros
        Path zerosCompressed = MavenTestingUtils.getTestResourcePathFile("zeros.gz.gz");
        byte[] compressedRequest;
        try (InputStream in = Files.newInputStream(zerosCompressed);
             GZIPInputStream gzipIn = new GZIPInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            IO.copy(gzipIn, out);
            compressedRequest = out.toByteArray();
        }

        // We want to write a request with request body content size / length
        // that will exceed the various buffers in the network, the client, the server,
        // etc.

        int sizeActuallySent = compressedRequest.length / 2;
        ByteBuffer start = ByteBuffer.wrap(compressedRequest, 0, sizeActuallySent);

        // Using deferred content to allow us to write SOME of the request body content
        // but not all of it (yet)
        DeferredContentProvider contentProvider = new DeferredContentProvider(start);
        AtomicReference<Response> clientResponseRef = new AtomicReference<>();
        CountDownLatch clientResponseSuccessLatch = new CountDownLatch(1);
        CountDownLatch clientResultComplete = new CountDownLatch(1);

        URI uri = serverURI.resolve("/fail?read=" + read);

        client.newRequest(uri)
            .method(HttpMethod.POST)
            .header(HttpHeader.CONTENT_TYPE, "application/octet-stream")
            .header(HttpHeader.CONTENT_ENCODING, "gzip")
            .content(contentProvider)
            .onResponseSuccess((response) ->
            {
                clientResponseRef.set(response);
                clientResponseSuccessLatch.countDown();
            })
            .send((result) -> clientResultComplete.countDown());

        assertTrue(clientResponseSuccessLatch.await(5, TimeUnit.SECONDS), "Result not received");
        Response response = clientResponseRef.get();
        assertEquals(400, response.getStatus(), "Response status on /fail");

        // We expect the server to set `Connection: close`, as the request body content isn't fully sent (yet)
        assertEquals("close", response.getHeaders().get(HttpHeader.CONNECTION), "Response Connection header");

        // Await for server side to complete the request
        assertTrue(serverRequestCompleteLatch.await(5, TimeUnit.SECONDS), "Request complete never occurred?");

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Input Content Consumed: %,d%n", inputContentConsumed.get());
            LOG.debug("Input Content Received: %,d%n", inputContentReceived.get());
            LOG.debug("Input BytesIn Count: %,d%n", inputBytesIn.get());
        }

        long readCount = read ? 1L : 0L;

        // Servlet read of body content
        assertThat("Request Input Content Consumed not have been used", inputContentConsumed.get(), is(readCount));
        // Network reads
        assertThat("Request Input Content Received should have seen content", inputContentReceived.get(), greaterThan(0L));
        assertThat("Request Input Content Received less then initial buffer", inputContentReceived.get(), lessThanOrEqualTo((long)sizeActuallySent));
        assertThat("Request Connection BytesIn should have some minimal data", inputBytesIn.get(), greaterThanOrEqualTo(1024L));
        long requestBytesSent = sizeActuallySent + 512; // Take into account headers and chunked metadata.
        assertThat("Request Connection BytesIn read should not have read all of the data", inputBytesIn.get(), lessThanOrEqualTo(requestBytesSent));

        // Now use the deferred content to complete writing of the request body content
        contentProvider.offer(ByteBuffer.wrap(compressedRequest, sizeActuallySent, compressedRequest.length - sizeActuallySent));
        contentProvider.close();

        assertTrue(clientResultComplete.await(5, TimeUnit.SECONDS));
    }

    public static class PostServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setCharacterEncoding("utf-8");
            resp.setContentType("text/plain");
            String reqBody = IO.toString(req.getInputStream(), UTF_8);
            resp.getWriter().append(reqBody);
        }
    }

    public static class FailServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            boolean read = Boolean.parseBoolean(req.getParameter("read"));
            if (read)
            {
                int val = req.getInputStream().read();
                assertNotEquals(-1, val);
            }
            // intentionally do not read request body here.
            resp.sendError(400);
        }
    }
}
