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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public abstract class AbstractConnectionTest
{
    protected HttpClient newHttpClient()
    {
        HttpClient httpClient = new HttpClient();

        // httpClient.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        return httpClient;
    }


    protected ServerSocket newServerSocket() throws IOException
    {
        ServerSocket serverSocket=new ServerSocket();
        serverSocket.bind(null);
        return serverSocket;
    }
    
    @Test
    public void testServerClosedConnection() throws Exception
    {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(null);
        int port=serverSocket.getLocalPort();

        HttpClient httpClient = newHttpClient();
        httpClient.setMaxConnectionsPerAddress(1);
        httpClient.start();
        try
        {
            CountDownLatch latch = new CountDownLatch(1);
            HttpExchange exchange = new ConnectionExchange(latch);
            exchange.setAddress(new Address("localhost", port));
            exchange.setRequestURI("/");
            httpClient.send(exchange);

            Socket remote = serverSocket.accept();

            // HttpClient.send() above is async, so if we write the response immediately
            // there is a chance that it arrives before the request is being sent, so we
            // read the request before sending the response to avoid the race
            InputStream input = remote.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.length() == 0)
                    break;
            }

            OutputStream output = remote.getOutputStream();
            output.write("HTTP/1.1 200 OK\r\n".getBytes("UTF-8"));
            output.write("Content-Length: 0\r\n".getBytes("UTF-8"));
            output.write("\r\n".getBytes("UTF-8"));
            output.flush();

            assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());

            remote.close();

            // Need to wait a bit to allow the client to detect
            // that the server has closed the connection
            Thread.sleep(500);

            // The server has closed the connection and another attempt to send
            // with the same connection would fail because the connection has been
            // closed by the client as well.
            // The client must open a new connection in this case, and we check
            // that the new request completes correctly
            exchange.reset();
            httpClient.send(exchange);

            remote = serverSocket.accept();

            input = remote.getInputStream();
            reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            while ((line = reader.readLine()) != null)
            {
                if (line.length() == 0)
                    break;
            }

            output = remote.getOutputStream();
            output.write("HTTP/1.1 200 OK\r\n".getBytes("UTF-8"));
            output.write("Content-Length: 0\r\n".getBytes("UTF-8"));
            output.write("\r\n".getBytes("UTF-8"));
            output.flush();

            assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
        }
        finally
        {
            httpClient.stop();
        }
    }

    protected String getScheme()
    {
        return "http";
    }
    
    @Test
    public void testServerClosedIncomplete() throws Exception
    {
        ServerSocket serverSocket = newServerSocket();
        int port=serverSocket.getLocalPort();

        HttpClient httpClient = newHttpClient();
        httpClient.setMaxConnectionsPerAddress(1);
        httpClient.start();
        try
        {
            CountDownLatch latch = new CountDownLatch(1);
            HttpExchange exchange = new ConnectionExchange(latch);
            exchange.setScheme(getScheme());
            exchange.setAddress(new Address("localhost", port));
            exchange.setRequestURI("/");
            httpClient.send(exchange);

            Socket remote = serverSocket.accept();

            // HttpClient.send() above is async, so if we write the response immediately
            // there is a chance that it arrives before the request is being sent, so we
            // read the request before sending the response to avoid the race
            InputStream input = remote.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.length() == 0)
                    break;
            }

            OutputStream output = remote.getOutputStream();
            output.write("HTTP/1.1 200 OK\r\n".getBytes("UTF-8"));
            output.write("Content-Length: 10\r\n".getBytes("UTF-8"));
            output.write("\r\n".getBytes("UTF-8"));
            output.flush();

            remote.close();

            int status = exchange.waitForDone();
            
            assertEquals(HttpExchange.STATUS_EXCEPTED, status);

        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testServerHalfClosedIncomplete() throws Exception
    {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(null);
        int port=serverSocket.getLocalPort();

        HttpClient httpClient = newHttpClient();
        httpClient.setIdleTimeout(10000);
        httpClient.setMaxConnectionsPerAddress(1);
        httpClient.start();
        try
        {
            CountDownLatch latch = new CountDownLatch(1);
            HttpExchange exchange = new ConnectionExchange(latch);
            exchange.setAddress(new Address("localhost", port));
            exchange.setRequestURI("/");
            httpClient.send(exchange);

            Socket remote = serverSocket.accept();

            // HttpClient.send() above is async, so if we write the response immediately
            // there is a chance that it arrives before the request is being sent, so we
            // read the request before sending the response to avoid the race
            InputStream input = remote.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.length() == 0)
                    break;
            }

            OutputStream output = remote.getOutputStream();
            output.write("HTTP/1.1 200 OK\r\n".getBytes("UTF-8"));
            output.write("Content-Length: 10\r\n".getBytes("UTF-8"));
            output.write("\r\n".getBytes("UTF-8"));
            output.flush();

            remote.shutdownOutput();

            assertEquals(HttpExchange.STATUS_EXCEPTED, exchange.waitForDone());

        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testConnectionFailed() throws Exception
    {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(null);
        int port=serverSocket.getLocalPort();
        serverSocket.close();

        HttpClient httpClient = newHttpClient();
        httpClient.start();
        try
        {
            CountDownLatch latch = new CountDownLatch(1);
            HttpExchange exchange = new ConnectionExchange(latch);
            exchange.setAddress(new Address("localhost", port));
            exchange.setRequestURI("/");
            httpClient.send(exchange);

            boolean passed = latch.await(4000, TimeUnit.MILLISECONDS);
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
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testMultipleConnectionsFailed() throws Exception
    {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(null);
        int port=serverSocket.getLocalPort();
        serverSocket.close();

        HttpClient httpClient = newHttpClient();
        httpClient.setMaxConnectionsPerAddress(1);
        httpClient.start();
        try
        {
            HttpExchange[] exchanges = new HttpExchange[20];
            final CountDownLatch latch = new CountDownLatch(exchanges.length);
            for (int i = 0; i < exchanges.length; ++i)
            {
                HttpExchange exchange = new HttpExchange()
                {
                    @Override
                    protected void onConnectionFailed(Throwable x)
                    {
                        latch.countDown();
                    }
                };
                exchange.setAddress(new Address("localhost", port));
                exchange.setRequestURI("/");
                exchanges[i] = exchange;
            }

            for (HttpExchange exchange : exchanges)
                httpClient.send(exchange);

            for (HttpExchange exchange : exchanges)
                assertEquals(HttpExchange.STATUS_EXCEPTED, exchange.waitForDone());

            assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testConnectionTimeout() throws Exception
    {
        HttpClient httpClient = newHttpClient();
        int connectTimeout = 5000;
        httpClient.setConnectTimeout(connectTimeout);
        httpClient.start();
        try
        {
            CountDownLatch latch = new CountDownLatch(1);
            HttpExchange exchange = new ConnectionExchange(latch);
            // Using a IP address has a different behavior than using a host name
            exchange.setAddress(new Address("127.0.0.1", 1));
            exchange.setRequestURI("/");
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

    @Test
    public void testIdleConnection() throws Exception
    {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(null);
        int port=serverSocket.getLocalPort();

        HttpClient httpClient = newHttpClient();
        httpClient.setIdleTimeout(700);
        httpClient.start();
        try
        {
            HttpExchange exchange = new ConnectionExchange();
            exchange.setAddress(new Address("localhost", port));
            exchange.setRequestURI("/");
            HttpDestination dest = httpClient.getDestination(new Address("localhost", port),false);

            httpClient.send(exchange);
            Socket server = serverSocket.accept();
            server.setSoTimeout(5000);
            byte[] buf = new byte[4096];
            
            int len=server.getInputStream().read(buf);
            assertEquals(1,dest.getConnections());
            assertEquals(0,dest.getIdleConnections());

            server.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes());
            
            assertEquals(HttpExchange.STATUS_COMPLETED,exchange.waitForDone());
            Thread.sleep(200); // TODO get rid of this
            assertEquals(1,dest.getConnections());
            assertEquals(1,dest.getIdleConnections());

            exchange = new ConnectionExchange();
            exchange.setAddress(new Address("localhost", port));
            exchange.setRequestURI("/");
            httpClient.send(exchange);
            assertEquals(1,dest.getConnections());
            assertEquals(0,dest.getIdleConnections());
            
            
            len=server.getInputStream().read(buf);
            assertEquals(1,dest.getConnections());
            assertEquals(0,dest.getIdleConnections());
            server.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes());

            Thread.sleep(500);

            assertEquals(1,dest.getConnections());
            assertEquals(1,dest.getIdleConnections());

            Thread.sleep(500);

            assertEquals(0,dest.getConnections());
            assertEquals(0,dest.getIdleConnections());

            serverSocket.close();
        }
        finally
        {
            httpClient.stop();
        }
    }

    protected class ConnectionExchange extends HttpExchange
    {
        private final CountDownLatch latch;

        protected ConnectionExchange()
        {
            this.latch = null;
        }

        protected ConnectionExchange(CountDownLatch latch)
        {
            this.latch = latch;
        }

        @Override
        protected void onConnectionFailed(Throwable ex)
        {
            if (latch!=null)
                latch.countDown();
        }

        @Override
        protected void onException(Throwable x)
        {
            if (latch!=null)
                latch.countDown();
        }
    }
}
