/*
 * Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package org.eclipse.jetty.client;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

/**
 * @version $Revision$ $Date$
 */
public class ConnectionTest extends TestCase
{
    public void testConnectionFailed() throws Exception
    {
        ServerSocket socket = new ServerSocket();
        socket.bind(null);
        int port=socket.getLocalPort();
        socket.close();

        HttpClient httpClient = new HttpClient();
        httpClient.start();

        CountDownLatch latch = new CountDownLatch(1);
        HttpExchange exchange = new ConnectionExchange(latch);
        exchange.setAddress(new Address("localhost", port));
        exchange.setURI("/");
        httpClient.send(exchange);

        boolean passed = latch.await(1000, TimeUnit.MILLISECONDS);
        assertTrue(passed);

        long wait = 100;
        long maxWait = 10 * wait;
        long curWait = wait;
        while (curWait < maxWait && !exchange.isDone())
        {
            Thread.sleep(wait);
            curWait += wait;
        }

        assertEquals(HttpExchange.STATUS_EXCEPTED, exchange.getStatus());
    }

    public void testConnectionTimeoutWithSocketConnector() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        int connectTimeout = 5000;
        httpClient.setConnectTimeout(connectTimeout);
        httpClient.start();

        try
        {
            CountDownLatch latch = new CountDownLatch(1);
            HttpExchange exchange = new ConnectionExchange(latch);
            // Using a IP address has a different behavior than using a host name
            exchange.setAddress(new Address("1.2.3.4", 8080));
            exchange.setURI("/");
            httpClient.send(exchange);

            boolean passed = latch.await(connectTimeout * 2L, TimeUnit.MILLISECONDS);
            assertTrue(passed);

            int status = exchange.waitForDone();
            assertEquals(HttpExchange.STATUS_EXCEPTED, status);
        }
        finally
        {
            httpClient.stop();
        }
    }

    public void testConnectionTimeoutWithSelectConnector() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        int connectTimeout = 5000;
        httpClient.setConnectTimeout(connectTimeout);
        httpClient.start();

        try
        {
            CountDownLatch latch = new CountDownLatch(1);
            HttpExchange exchange = new ConnectionExchange(latch);
            // Using a IP address has a different behavior than using a host name
            exchange.setAddress(new Address("1.2.3.4", 8080));
            exchange.setURI("/");
            httpClient.send(exchange);

            boolean passed = latch.await(connectTimeout * 2L, TimeUnit.MILLISECONDS);
            assertTrue(passed);

            int status = exchange.waitForDone();
            assertEquals(HttpExchange.STATUS_EXCEPTED, status);
        }
        finally
        {
            httpClient.stop();
        }
    }

    public void testIdleConnection() throws Exception
    {
        ServerSocket socket = new ServerSocket();
        socket.bind(null);
        int port=socket.getLocalPort();

        HttpClient httpClient = new HttpClient();
        httpClient.setIdleTimeout(700);
        httpClient.start();

        HttpExchange exchange = new ConnectionExchange();
        exchange.setAddress(new Address("localhost", port));
        exchange.setURI("/");
        HttpDestination dest = httpClient.getDestination(new Address("localhost", port),false);

        httpClient.send(exchange);
        Socket s = socket.accept();
        byte[] buf = new byte[4096];
        s.getInputStream().read(buf);
        assertEquals(1,dest.getConnections());
        assertEquals(0,dest.getIdleConnections());

        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes());

        Thread.sleep(300);
        assertEquals(1,dest.getConnections());
        assertEquals(1,dest.getIdleConnections());

        exchange = new ConnectionExchange();
        exchange.setAddress(new Address("localhost", port));
        exchange.setURI("/");

        httpClient.send(exchange);
        s.getInputStream().read(buf);
        assertEquals(1,dest.getConnections());
        assertEquals(0,dest.getIdleConnections());
        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes());

        Thread.sleep(500);

        assertEquals(1,dest.getConnections());
        assertEquals(1,dest.getIdleConnections());

        Thread.sleep(500);

        assertEquals(0,dest.getConnections());
        assertEquals(0,dest.getIdleConnections());

        socket.close();

    }

    private class ConnectionExchange extends HttpExchange
    {
        private final CountDownLatch latch;

        private ConnectionExchange()
        {
            this.latch = null;
        }

        private ConnectionExchange(CountDownLatch latch)
        {
            this.latch = latch;
        }

        @Override
        protected void onConnectionFailed(Throwable ex)
        {
            if (latch!=null)
                latch.countDown();
        }
    }
}
