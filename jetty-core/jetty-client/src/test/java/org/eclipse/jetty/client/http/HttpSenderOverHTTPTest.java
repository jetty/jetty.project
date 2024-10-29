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

package org.eclipse.jetty.client.http;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ByteBufferRequestContent;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.Promise;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wildfly.common.Assert.assertFalse;

public class HttpSenderOverHTTPTest
{
    private HttpClient client;

    @BeforeEach
    public void init() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        client.stop();
    }

    @Test
    public void testSendNoRequestContent() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        assertTrue(requestString.startsWith("GET "));
        assertTrue(requestString.endsWith("\r\n\r\n"));
        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSendNoRequestContentIncompleteFlush() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint("", 16);
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        connection.send(request, null);

        // This take will free space in the buffer and allow for the write to complete
        StringBuilder builder = new StringBuilder(endPoint.takeOutputString());

        // Wait for the write to complete
        await().atMost(5, TimeUnit.SECONDS).until(() -> endPoint.toEndPointString().contains(",flush=P,"));

        String chunk = endPoint.takeOutputString();
        while (chunk.length() > 0)
        {
            builder.append(chunk);
            chunk = endPoint.takeOutputString();
        }

        String requestString = builder.toString();
        assertTrue(requestString.startsWith("GET "));
        assertTrue(requestString.endsWith("\r\n\r\n"));
    }

    @Test
    public void testSendNoRequestContentException() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        // Shutdown output to trigger the exception on write
        endPoint.shutdownOutput();
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch failureLatch = new CountDownLatch(2);
        request.listener(new Request.Listener()
        {
            @Override
            public void onFailure(Request request, Throwable x)
            {
                failureLatch.countDown();
            }
        });
        connection.send(request, new Response.Listener()
        {
            @Override
            public void onComplete(Result result)
            {
                assertTrue(result.isFailed());
                failureLatch.countDown();
            }
        });

        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSendNoRequestContentIncompleteFlushException() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint("", 16);
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch failureLatch = new CountDownLatch(2);
        request.listener(new Request.Listener()
        {
            @Override
            public void onFailure(Request request, Throwable x)
            {
                failureLatch.countDown();
            }
        });
        connection.send(request, new Response.Listener()
        {
            @Override
            public void onComplete(Result result)
            {
                assertTrue(result.isFailed());
                failureLatch.countDown();
            }
        });

        // Shutdown output to trigger the exception on write
        endPoint.shutdownOutput();
        // This take will free space in the buffer and allow for the write to complete
        // although it will fail because we shut down the output
        endPoint.takeOutputString();

        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSendSmallRequestContentInOneBuffer() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content = "abcdef";
        request.body(new ByteBufferRequestContent(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8))));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        assertTrue(requestString.startsWith("GET "));
        assertTrue(requestString.endsWith("\r\n\r\n" + content));
        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSendSmallRequestContentInTwoBuffers() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content1 = "0123456789";
        String content2 = "abcdef";
        request.body(new ByteBufferRequestContent(ByteBuffer.wrap(content1.getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(content2.getBytes(StandardCharsets.UTF_8))));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        assertTrue(requestString.startsWith("GET "));
        assertThat(requestString, Matchers.endsWith("\r\n\r\n" + content1 + content2));
        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSendSmallRequestContentChunkedInTwoChunks() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content1 = "0123456789";
        String content2 = "ABCDEF";
        request.body(new ByteBufferRequestContent(ByteBuffer.wrap(content1.getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(content2.getBytes(StandardCharsets.UTF_8)))
        {
            @Override
            public long getLength()
            {
                return -1;
            }
        });
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        assertTrue(requestString.startsWith("GET "));
        String content = Integer.toHexString(content1.length()).toUpperCase(Locale.ENGLISH) + "\r\n" + content1 + "\r\n";
        content += Integer.toHexString(content2.length()).toUpperCase(Locale.ENGLISH) + "\r\n" + content2 + "\r\n";
        content += "0\r\n\r\n";
        assertTrue(requestString.endsWith("\r\n\r\n" + content));
        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    private static Random rnd = new Random();
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890";

    public static final int CHARS_LENGTH = CHARS.length();

    protected static String getRandomString(int size) {
        StringBuilder sb = new StringBuilder(size);
        while (sb.length() < size) { // length of the random string.
            int index = rnd.nextInt(CHARS_LENGTH);
            sb.append(CHARS.charAt(index));
        }
        return sb.toString();
    }

    @Test
    public void testSmallHeadersSize() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        request.agent(getRandomString(888)); //More than the request buffer size, but less than the default max request headers size
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        request.listener(new Request.Listener()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }

            @Override
            public void onFailure(Request request, Throwable failure) {
                failureLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        assertTrue(requestString.startsWith("GET / HTTP/1.1\r\nAccept-Encoding: gzip\r\n"));
        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMaxRequestHeadersSize() throws Exception
    {
        byte[] buffer = new byte[32 * 1024];
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(buffer, buffer.length);
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        //More than the request buffer size, but less than the default max request headers size

        int desiredHeadersSize = 20 * 1024;
        int currentHeadersSize = 0;
        int i = 0;
        while(currentHeadersSize < desiredHeadersSize) {
            final int index = i ++;
            final String headerValue = getRandomString(800);
            final int headerSize = headerValue.length();
            currentHeadersSize += headerSize;
            request.cookie(new HttpCookie() {
                @Override
                public String getName() {
                    return "large" + index;
                }

                @Override
                public String getValue() {
                    return headerValue;
                }

                @Override
                public int getVersion() {
                    return 0;
                }

                @Override
                public Map<String, String> getAttributes() {
                    return new HashMap<>();
                }
            });
        }

        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        assertTrue(requestString.startsWith("GET / HTTP/1.1\r\nAccept-Encoding: gzip\r\n"));
        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMaxRequestHeadersSizeOverflow() throws Exception
    {
        byte[] buffer = new byte[32 * 1024];
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(buffer, buffer.length);
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        //More than the request buffer size, but less than the default max request headers size

        int desiredHeadersSize = 35 * 1024;
        int currentHeadersSize = 0;
        int i = 0;
        while(currentHeadersSize < desiredHeadersSize) {
            final int index = i ++;
            final String headerValue = getRandomString(800);
            final int headerSize = headerValue.length();
            currentHeadersSize += headerSize;
            request.cookie(new HttpCookie() {
                @Override
                public String getName() {
                    return "large" + index;
                }

                @Override
                public String getValue() {
                    return headerValue;
                }

                @Override
                public int getVersion() {
                    return 0;
                }

                @Override
                public Map<String, String> getAttributes() {
                    return new HashMap<>();
                }
            });
        }

        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        request.listener(new Request.Listener()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onFailure(Request request, Throwable failure)
            {
                failureLatch.countDown();
            }
        });
        connection.send(request, null);

        assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }
}
