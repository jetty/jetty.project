//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class ProxyProtocolTest
{
    private Server server;
    private ServerConnector connector;

    private void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, new ProxyConnectionFactory(), new HttpConnectionFactory());
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @After
    public void destroy() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testProxyProtocol() throws Exception
    {
        final String remoteAddr = "192.168.0.0";
        final int remotePort = 12345;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (remoteAddr.equals(request.getRemoteAddr()) &&
                        remotePort == request.getRemotePort())
                    baseRequest.setHandled(true);
            }
        });

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String request1 = "" +
                    "PROXY TCP4 " + remoteAddr + " 127.0.0.0 " + remotePort + " 8080\r\n" +
                    "GET /1 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request1.getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String response1 = reader.readLine();
            Assert.assertTrue(response1.startsWith("HTTP/1.1 200 "));
            while (true)
            {
                if (reader.readLine().isEmpty())
                    break;
            }

            // Send a second request to verify that the proxied IP is retained.
            String request2 = "" +
                    "GET /2 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            output.write(request2.getBytes(StandardCharsets.UTF_8));
            output.flush();

            String response2 = reader.readLine();
            Assert.assertTrue(response2.startsWith("HTTP/1.1 200 "));
            while (true)
            {
                if (reader.readLine() == null)
                    break;
            }
        }
    }
}
