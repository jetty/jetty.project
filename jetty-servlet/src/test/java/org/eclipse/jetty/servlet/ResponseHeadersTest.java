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

package org.eclipse.jetty.servlet;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class ResponseHeadersTest
{
    /** Pretend to be a WebSocket Upgrade (not real) */
    @SuppressWarnings("serial")
    private static class SimulateUpgradeServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            response.setHeader("Upgrade","WebSocket");
            response.addHeader("Connection","Upgrade");
            response.addHeader("Sec-WebSocket-Accept","123456789==");

            response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        }
    }

    private static Server server;
    private static ServerConnector connector;
    private static URI serverUri;

    @BeforeClass
    public static void startServer() throws Exception
    {
        // Configure Server
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        // Serve capture servlet
        context.addServlet(new ServletHolder(new SimulateUpgradeServlet()),"/*");

        // Start Server
        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("http://%s:%d/",host,port));
    }

    @AfterClass
    public static void stopServer()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    @Test
    public void testResponseHeaderFormat() throws IOException
    {
        Socket socket = new Socket();
        SocketAddress endpoint = new InetSocketAddress(serverUri.getHost(),serverUri.getPort());
        socket.connect(endpoint);

        StringBuilder req = new StringBuilder();
        req.append("GET / HTTP/1.1\r\n");
        req.append(String.format("Host: %s:%d\r\n",serverUri.getHost(),serverUri.getPort()));
        req.append("\r\n");

        OutputStream out = null;
        InputStream in = null;
        try
        {
            out = socket.getOutputStream();
            in = socket.getInputStream();

            // Write request
            out.write(req.toString().getBytes());
            out.flush();

            // Read response
            String respHeader = readResponseHeader(in);

            // Now test for properly formatted HTTP Response Headers.
            Assert.assertThat("Response Code",respHeader,startsWith("HTTP/1.1 101 Switching Protocols"));
            Assert.assertThat("Response Header Upgrade",respHeader,containsString("Upgrade: WebSocket\r\n"));
            Assert.assertThat("Response Header Connection",respHeader,containsString("Connection: Upgrade\r\n"));
        }
        finally
        {
            IO.close(in);
            IO.close(out);
            socket.close();
        }
    }

    private String readResponseHeader(InputStream in) throws IOException
    {
        InputStreamReader isr = new InputStreamReader(in);
        BufferedReader reader = new BufferedReader(isr);
        StringBuilder header = new StringBuilder();
        // Read the response header
        String line = reader.readLine();
        Assert.assertNotNull(line);
        Assert.assertThat(line,startsWith("HTTP/1.1 "));
        header.append(line).append("\r\n");
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
            {
                break;
            }
            header.append(line).append("\r\n");
        }
        return header.toString();
    }
}
