//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class HttpReceiverOverHTTPTest
{
    @Rule
    public final TestTracker tracker = new TestTracker();

    private HttpClient client;
    private HttpDestinationOverHTTP destination;
    private ByteArrayEndPoint endPoint;
    private HttpConnectionOverHTTP connection;

    @Before
    public void init() throws Exception
    {
        client = new HttpClient();
        client.start();
        destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", 8080));
        endPoint = new ByteArrayEndPoint();
        connection = new HttpConnectionOverHTTP(endPoint, destination);
    }

    @After
    public void destroy() throws Exception
    {
        client.stop();
    }

    protected HttpExchange newExchange()
    {
        HttpRequest request = (HttpRequest)client.newRequest("http://localhost");
        FutureResponseListener listener = new FutureResponseListener(request);
        HttpExchange exchange = new HttpExchange(destination, request, Collections.<Response.ResponseListener>singletonList(listener));
        connection.getHttpChannel().associate(exchange);
        exchange.requestComplete();
        exchange.terminateRequest(null);
        return exchange;
    }

    @Test
    public void test_Receive_NoResponseContent() throws Exception
    {
        endPoint.setInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: 0\r\n" +
                "\r\n");
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();

        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals("OK", response.getReason());
        Assert.assertSame(HttpVersion.HTTP_1_1, response.getVersion());
        HttpFields headers = response.getHeaders();
        Assert.assertNotNull(headers);
        Assert.assertEquals(1, headers.size());
        Assert.assertEquals("0", headers.get(HttpHeader.CONTENT_LENGTH));
    }

    @Test
    public void test_Receive_ResponseContent() throws Exception
    {
        String content = "0123456789ABCDEF";
        endPoint.setInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: " + content.length() + "\r\n" +
                "\r\n" +
                content);
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();

        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals("OK", response.getReason());
        Assert.assertSame(HttpVersion.HTTP_1_1, response.getVersion());
        HttpFields headers = response.getHeaders();
        Assert.assertNotNull(headers);
        Assert.assertEquals(1, headers.size());
        Assert.assertEquals(String.valueOf(content.length()), headers.get(HttpHeader.CONTENT_LENGTH));
        String received = listener.getContentAsString(StandardCharsets.UTF_8);
        Assert.assertEquals(content, received);
    }

    @Test
    public void test_Receive_ResponseContent_EarlyEOF() throws Exception
    {
        String content1 = "0123456789";
        String content2 = "ABCDEF";
        endPoint.setInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: " + (content1.length() + content2.length()) + "\r\n" +
                "\r\n" +
                content1);
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();
        endPoint.setInputEOF();
        connection.getHttpChannel().receive();

        try
        {
            listener.get(5, TimeUnit.SECONDS);
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Assert.assertTrue(e.getCause() instanceof EOFException);
        }
    }

    @Test
    public void test_Receive_ResponseContent_IdleTimeout() throws Exception
    {
        endPoint.setInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: 1\r\n" +
                "\r\n");
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();
        // Simulate an idle timeout
        connection.onReadTimeout();

        try
        {
            listener.get(5, TimeUnit.SECONDS);
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Assert.assertTrue(e.getCause() instanceof TimeoutException);
        }
    }

    @Test
    public void test_Receive_BadResponse() throws Exception
    {
        endPoint.setInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: A\r\n" +
                "\r\n");
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();

        try
        {
            listener.get(5, TimeUnit.SECONDS);
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Assert.assertTrue(e.getCause() instanceof HttpResponseException);
        }
    }

    @Test
    public void test_Receive_GZIPResponseContent_Fragmented() throws Exception
    {
        byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutput = new GZIPOutputStream(baos))
        {
            gzipOutput.write(data);
        }
        byte[] gzip = baos.toByteArray();

        endPoint.setInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: " + gzip.length + "\r\n" +
                "Content-Encoding: gzip\r\n" +
                "\r\n");

        HttpRequest request = (HttpRequest)client.newRequest("http://localhost");
        final CountDownLatch latch = new CountDownLatch(1);
        FutureResponseListener listener = new FutureResponseListener(request)
        {
            @Override
            public void onContent(Response response, ByteBuffer content)
            {
                boolean hadRemaining=content.hasRemaining();
                super.onContent(response, content);
                
                // TODO gzip decoding can pass on empty chunks. Currently ignoring them here, but could be done at the decoder???
                if (hadRemaining) // Ignore empty chunks
                    latch.countDown();                
            }
        };
        HttpExchange exchange = new HttpExchange(destination, request, Collections.<Response.ResponseListener>singletonList(listener));
        connection.getHttpChannel().associate(exchange);
        exchange.requestComplete();
        exchange.terminateRequest(null);
        connection.getHttpChannel().receive();
        endPoint.reset();

        ByteBuffer buffer = ByteBuffer.wrap(gzip);
        int fragment = buffer.limit() - 1;
        buffer.limit(fragment);
        endPoint.setInput(buffer);
        connection.getHttpChannel().receive();
        endPoint.reset();

        buffer.limit(gzip.length);
        buffer.position(fragment);
        endPoint.setInput(buffer);
        connection.getHttpChannel().receive();

        ContentResponse response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertArrayEquals(data, response.getContent());
    }
}
