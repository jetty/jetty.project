//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.RejectedExecutionException;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class HttpDestinationQueueTest
{
    private static HttpClient _httpClient;
    private static long _timeout = 200;

    @BeforeClass
    public static void beforeOnce() throws Exception
    {
        _httpClient = new HttpClient();
        _httpClient.setMaxConnectionsPerAddress(1);
        _httpClient.setMaxQueueSizePerAddress(1);
        _httpClient.setTimeout(_timeout);
        _httpClient.start();
    }

    @Test
    public void testDestinationMaxQueueSize() throws Exception
    {
        ServerSocket server = new ServerSocket(0);

        // This will keep the connection busy
        HttpExchange exchange1 = new HttpExchange();
        exchange1.setMethod("GET");
        exchange1.setURL("http://localhost:" + server.getLocalPort() + "/exchange1");
        _httpClient.send(exchange1);

        // Read request so we are sure that this exchange is out of the queue
        Socket socket = server.accept();
        byte[] buffer = new byte[1024];
        StringBuilder request = new StringBuilder();
        while (true)
        {
            int read = socket.getInputStream().read(buffer);
            request.append(new String(buffer,0,read,"UTF-8"));
            if (request.toString().endsWith("\r\n\r\n"))
                break;
        }
        Assert.assertTrue(request.toString().contains("exchange1"));

        // This will be queued
        HttpExchange exchange2 = new HttpExchange();
        exchange2.setMethod("GET");
        exchange2.setURL("http://localhost:" + server.getLocalPort() + "/exchange2");
        _httpClient.send(exchange2);

        // This will be rejected, since the connection is busy and the queue is full
        HttpExchange exchange3 = new HttpExchange();
        exchange3.setMethod("GET");
        exchange3.setURL("http://localhost:" + server.getLocalPort() + "/exchange3");
        try
        {
            _httpClient.send(exchange3);
            Assert.fail();
        }
        catch (RejectedExecutionException x)
        {
            // Expected
        }

        // Send the response to avoid exceptions in the console
        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes("UTF-8"));
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED,exchange1.waitForDone());

        // Be sure that the second exchange can be sent
        request.setLength(0);
        while (true)
        {
            int read = socket.getInputStream().read(buffer);
            request.append(new String(buffer,0,read,"UTF-8"));
            if (request.toString().endsWith("\r\n\r\n"))
                break;
        }
        Assert.assertTrue(request.toString().contains("exchange2"));

        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
        socket.close();
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED,exchange2.waitForDone());

        server.close();
    }

    @Test
    public void testDefaultTimeoutIncludesQueuingExchangeExpiresInQueue() throws Exception
    {

        ServerSocket server = new ServerSocket(0);

        // This will keep the connection busy
        HttpExchange exchange1 = new HttpExchange();
        exchange1.setTimeout(_timeout * 3); // Be sure it does not expire
        exchange1.setMethod("GET");
        exchange1.setURL("http://localhost:" + server.getLocalPort() + "/exchange1");
        _httpClient.send(exchange1);

        // Read request so we are sure that this exchange is out of the queue
        Socket socket = server.accept();
        byte[] buffer = new byte[1024];
        StringBuilder request = new StringBuilder();
        while (true)
        {
            int read = socket.getInputStream().read(buffer);
            request.append(new String(buffer,0,read,"UTF-8"));
            if (request.toString().endsWith("\r\n\r\n"))
                break;
        }
        Assert.assertTrue(request.toString().contains("exchange1"));

        // This will be queued
        HttpExchange exchange2 = new HttpExchange();
        exchange2.setMethod("GET");
        exchange2.setURL("http://localhost:" + server.getLocalPort() + "/exchange2");
        _httpClient.send(exchange2);

        // Wait until the queued exchange times out in the queue
        Thread.sleep(_timeout * 2);

        Assert.assertEquals(HttpExchange.STATUS_EXPIRED,exchange2.getStatus());

        // Send the response to the first exchange to avoid exceptions in the console
        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes("UTF-8"));
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED,exchange1.waitForDone());
        socket.close();

        server.close();
    }

    @Test
    public void testDefaultTimeoutIncludesQueuingExchangeExpiresDuringRequest() throws Exception
    {
        ServerSocket server = new ServerSocket(0);

        HttpExchange exchange1 = new HttpExchange();
        exchange1.setMethod("GET");
        exchange1.setURL("http://localhost:" + server.getLocalPort() + "/exchange1");
        _httpClient.send(exchange1);

        // Read request so we are sure that this exchange is out of the queue
        Socket socket = server.accept();
        byte[] buffer = new byte[1024];
        StringBuilder request = new StringBuilder();
        while (true)
        {
            int read = socket.getInputStream().read(buffer);
            request.append(new String(buffer,0,read,"UTF-8"));
            if (request.toString().endsWith("\r\n\r\n"))
                break;
        }
        Assert.assertTrue(request.toString().contains("exchange1"));

        // Wait until the exchange times out during the request
        Thread.sleep(_timeout * 2);

        Assert.assertEquals(HttpExchange.STATUS_EXPIRED,exchange1.getStatus());

        socket.close();

        server.close();
    }

    @Test
    public void testExchangeTimeoutIncludesQueuingExchangeExpiresDuringResponse() throws Exception
    {
        ServerSocket server = new ServerSocket(0);

        long timeout = 1000;
        HttpExchange exchange1 = new HttpExchange();
        exchange1.setTimeout(timeout);
        exchange1.setMethod("GET");
        exchange1.setURL("http://localhost:" + server.getLocalPort() + "/exchange1");
        _httpClient.send(exchange1);

        // Read request so we are sure that this exchange is out of the queue
        Socket socket = server.accept();
        byte[] buffer = new byte[1024];
        StringBuilder request = new StringBuilder();
        while (true)
        {
            int read = socket.getInputStream().read(buffer);
            request.append(new String(buffer,0,read,"UTF-8"));
            if (request.toString().endsWith("\r\n\r\n"))
                break;
        }
        Assert.assertTrue(request.toString().contains("exchange1"));

        // Write part of the response
        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 1\r\n\r\n".getBytes("UTF-8"));

        // Wait until the exchange times out during the response
        Thread.sleep(timeout * 2);

        Assert.assertEquals(HttpExchange.STATUS_EXPIRED,exchange1.getStatus());

        socket.close();

        server.close();
    }
}