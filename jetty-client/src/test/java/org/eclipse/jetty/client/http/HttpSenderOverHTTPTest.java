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

package org.eclipse.jetty.client.http;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.Promise;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener.Adapter()
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
    @DisabledIfSystemProperty(named = "env", matches = "ci") // TODO: SLOW, needs review
    public void testSendNoRequestContentIncompleteFlush() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint("", 16);
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        connection.send(request, null);

        // This take will free space in the buffer and allow for the write to complete
        StringBuilder builder = new StringBuilder(endPoint.takeOutputString());

        // Wait for the write to complete
        TimeUnit.SECONDS.sleep(1);

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
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch failureLatch = new CountDownLatch(2);
        request.listener(new Request.Listener.Adapter()
        {
            @Override
            public void onFailure(Request request, Throwable x)
            {
                failureLatch.countDown();
            }
        });
        connection.send(request, new Response.Listener.Adapter()
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
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch failureLatch = new CountDownLatch(2);
        request.listener(new Request.Listener.Adapter()
        {
            @Override
            public void onFailure(Request request, Throwable x)
            {
                failureLatch.countDown();
            }
        });
        connection.send(request, new Response.Listener.Adapter()
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
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content = "abcdef";
        request.content(new ByteBufferContentProvider(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8))));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener.Adapter()
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
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content1 = "0123456789";
        String content2 = "abcdef";
        request.content(new ByteBufferContentProvider(ByteBuffer.wrap(content1.getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(content2.getBytes(StandardCharsets.UTF_8))));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener.Adapter()
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
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", 8080));
        destination.start();
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<Connection>());
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content1 = "0123456789";
        String content2 = "ABCDEF";
        request.content(new ByteBufferContentProvider(ByteBuffer.wrap(content1.getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(content2.getBytes(StandardCharsets.UTF_8)))
        {
            @Override
            public long getLength()
            {
                return -1;
            }
        });
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener.Adapter()
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
}
