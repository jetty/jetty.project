//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.Promise;
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
        destination.start();
        endPoint = new ByteArrayEndPoint();
        connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<>());
        endPoint.setConnection(connection);
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
        boolean associated = connection.getHttpChannel().associate(exchange);
        Assert.assertTrue(associated);
        exchange.requestComplete(null);
        exchange.terminateRequest();
        return exchange;
    }

    @Test
    public void test_Receive_NoResponseContent() throws Exception
    {
        endPoint.addInput("" +
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
        endPoint.addInput("" +
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
        endPoint.addInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: " + (content1.length() + content2.length()) + "\r\n" +
                "\r\n" +
                content1);
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();
        endPoint.addInputEOF();
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
        endPoint.addInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: 1\r\n" +
                "\r\n");
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();
        // ByteArrayEndPoint has an idle timeout of 0 by default,
        // so to simulate an idle timeout is enough to wait a bit.
        Thread.sleep(100);
        connection.onIdleExpired();

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
        endPoint.addInput("" +
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
    public void test_FillInterested_RacingWith_BufferRelease() throws Exception
    {
        connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<>())
        {
            @Override
            protected HttpChannelOverHTTP newHttpChannel()
            {
                return new HttpChannelOverHTTP(this)
                {
                    @Override
                    protected HttpReceiverOverHTTP newHttpReceiver()
                    {
                        return new HttpReceiverOverHTTP(this)
                        {
                            @Override
                            protected void fillInterested()
                            {
                                // Verify that the buffer has been released
                                // before fillInterested() is called.
                                Assert.assertNull(getResponseBuffer());
                                // Fill the endpoint so receive is called again.
                                endPoint.addInput("X");
                                super.fillInterested();
                            }
                        };
                    }
                };
            }
        };
        endPoint.setConnection(connection);
        
        // Partial response to trigger the call to fillInterested().
        endPoint.addInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 1\r\n" +
                "\r\n");

        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();

        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
    }
}
