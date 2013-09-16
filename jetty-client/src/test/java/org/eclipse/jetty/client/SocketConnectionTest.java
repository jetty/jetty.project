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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;

public class SocketConnectionTest extends AbstractConnectionTest
{
    protected HttpClient newHttpClient()
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        return httpClient;
    }

    @Override
    public void testServerClosedConnection() throws Exception
    {
        // Differently from the SelectConnector, the SocketConnector cannot detect server closes.
        // Therefore, upon a second send, the exchange will fail.
        // Applications needs to retry it explicitly.

        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(null);
        int port=serverSocket.getLocalPort();

        HttpClient httpClient = this.newHttpClient();
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

            exchange.reset();
            httpClient.send(exchange);

            assertEquals(HttpExchange.STATUS_EXCEPTED, exchange.waitForDone());
        }
        finally
        {
            httpClient.stop();
        }
    }
    
    public void testIdleConnection() throws Exception
    {
        super.testIdleConnection();
    }
}
