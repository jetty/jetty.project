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

package org.eclipse.jetty.server.handler;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.toolchain.test.OS;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class ConnectHandlerTest extends AbstractConnectHandlerTest
{
    @BeforeClass
    public static void init() throws Exception
    {
        startServer(new SelectChannelConnector(), new ServerHandler());
        startProxy();
    }

    @Test
    public void testCONNECT() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testCONNECTAndGET() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        socket.setSoTimeout(30000);
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            request = "" +
                    "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            assertEquals("200", response.getCode());
            assertEquals("GET /echo", response.getBody());
        }
        finally
        {
            socket.close();
        }
    }

    
    @Test
    public void testCONNECTBadHostPort() throws Exception
    {
        String invalidHostname = "AMAZEBALLS_BADHOST.webtide.com";
        
        try
        {
            InetAddress addr = InetAddress.getByName(invalidHostname);
            StringBuilder err = new StringBuilder();
            err.append("DNS Hijacking detected: ");
            err.append(invalidHostname).append(" should have not returned a valid IP address [");
            err.append(addr.getHostAddress()).append("].  ");
            err.append("Fix your DNS provider to have this test pass.");
            err.append("\nFor more info see https://en.wikipedia.org/wiki/DNS_hijacking");
            Assert.assertNull(err.toString(), addr);
        }
        catch (UnknownHostException e)
        {
            // expected path
        }
        
        String hostPort = String.format("%s:%d",invalidHostname,serverConnector.getLocalPort());
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        socket.setSoTimeout(30000);
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 500 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("Response Code", "500", response.getCode());
        }
        finally
        {
            socket.close();
        }
    }
    
    @Test
    public void testCONNECT10AndGET() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.0\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            request = "" +
                    "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            assertEquals("200", response.getCode());
            assertEquals("GET /echo", response.getBody());
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testCONNECTAndGETPipelined() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n" +
                "GET /echo" + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            // The pipelined request must have gone up to the server as is
            response = readResponse(input);
            assertEquals("200", response.getCode());
            assertEquals("GET /echo", response.getBody());
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testCONNECTAndMultipleGETs() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            for (int i = 0; i < 10; ++i)
            {
                request = "" +
                        "GET /echo" + " HTTP/1.1\r\n" +
                        "Host: " + hostPort + "\r\n" +
                        "\r\n";
                output.write(request.getBytes("UTF-8"));
                output.flush();

                response = readResponse(input);
                assertEquals("200", response.getCode());
                assertEquals("GET /echo", response.getBody());
            }
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testCONNECTAndGETServerStop() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            request = "" +
                    "GET /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            assertEquals("200", response.getCode());
            assertEquals("GET /echo", response.getBody());

            // Idle server is shut down
            stopServer();

            int read = input.read();
            assertEquals(-1, read);
        }
        finally
        {
            socket.close();
            // Restart the server for the next test
            server.start();
        }
    }

    @Test
    public void testCONNECTAndGETAndServerSideClose() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            request = "" +
                    "GET /close HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            int read = input.read();
            assertEquals(-1, read);
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testCONNECTAndPOSTAndGET() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            request = "" +
                    "POST /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "Content-Length: 5\r\n" +
                    "\r\n" +
                    "HELLO";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            assertEquals("200", response.getCode());
            assertEquals("POST /echo\r\nHELLO", response.getBody());

            request = "" +
                    "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            assertEquals("200", response.getCode());
            assertEquals("GET /echo", response.getBody());
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testCONNECTAndPOSTWithBigBody() throws Exception
    {
    	// fails under windows and occasionally on mac due to OOME
    	boolean stress = Boolean.getBoolean( "STRESS" );
    	
    	if (!stress)
    	{
    		return;
    	}
    	
        // Log.getLogger(ConnectHandler.class).setDebugEnabled(true);
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            StringBuilder body = new StringBuilder();
            String chunk = "0123456789ABCDEF";
            for (int i = 0; i < 1024 * 1024; ++i)
                body.append(chunk);

            request = "" +
                    "POST /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "Content-Length: " + body.length() + "\r\n" +
                    "\r\n" +
                    body;
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            assertEquals("200", response.getCode());
            assertEquals("POST /echo\r\n" + body, response.getBody());
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testCONNECTAndPOSTWithContext() throws Exception
    {
        final String contextKey = "contextKey";
        final String contextValue = "contextValue";

        // Replace the default ProxyHandler with a subclass to test context information passing
        stopProxy();
        proxy.setHandler(new ConnectHandler()
        {
            @Override
            protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String address) throws ServletException, IOException
            {
                request.setAttribute(contextKey, contextValue);
                return super.handleAuthentication(request, response, address);
            }

            @Override
            protected SocketChannel connect(HttpServletRequest request, String host, int port) throws IOException
            {
                assertEquals(contextValue, request.getAttribute(contextKey));
                return super.connect(request, host, port);
            }

            @Override
            protected void prepareContext(HttpServletRequest request, ConcurrentMap<String, Object> context)
            {
                // Transfer data from the HTTP request to the connection context
                assertEquals(contextValue, request.getAttribute(contextKey));
                context.put(contextKey, request.getAttribute(contextKey));
            }

            @Override
            protected int read(EndPoint endPoint, Buffer buffer, ConcurrentMap<String, Object> context) throws IOException
            {
                assertEquals(contextValue, context.get(contextKey));
                return super.read(endPoint, buffer, context);
            }

            @Override
            protected int write(EndPoint endPoint, Buffer buffer, ConcurrentMap<String, Object> context) throws IOException
            {
                assertEquals(contextValue, context.get(contextKey));
                return super.write(endPoint, buffer, context);
            }
        });
        proxy.start();

        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            String body = "0123456789ABCDEF";
            request = "" +
                    "POST /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "Content-Length: " + body.length() + "\r\n" +
                    "\r\n" +
                    body;
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            assertEquals("200", response.getCode());
            assertEquals("POST /echo\r\n" + body, response.getBody());
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testCONNECTAndGETPipelinedAndOutputShutdown() throws Exception
    {
    	// TODO needs to be further investigated
    	assumeTrue(!OS.IS_OSX);

        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n" +
                "GET /echo" + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();
            socket.shutdownOutput();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            // The pipelined request must have gone up to the server as is
            response = readResponse(input);
            assertEquals("200", response.getCode());
            assertEquals("GET /echo", response.getBody());
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testCONNECTAndGETAndOutputShutdown() throws Exception
    {
    	// TODO needs to be further investigated
    	assumeTrue(!OS.IS_OSX);

        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            assertEquals("200", response.getCode());

            request = "" +
                    "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();
            socket.shutdownOutput();

            // The pipelined request must have gone up to the server as is
            response = readResponse(input);
            assertEquals("200", response.getCode());
            assertEquals("GET /echo", response.getBody());
        }
        finally
        {
            socket.close();
        }
    }

    private static class ServerHandler extends AbstractHandler
    {
        public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            request.setHandled(true);

            String uri = httpRequest.getRequestURI();
            if ("/echo".equals(uri))
            {
                StringBuilder builder = new StringBuilder();
                builder.append(httpRequest.getMethod()).append(" ").append(uri);
                if (httpRequest.getQueryString() != null)
                    builder.append("?").append(httpRequest.getQueryString());

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                InputStream input = httpRequest.getInputStream();
                int read = -1;
                while ((read = input.read()) >= 0)
                    baos.write(read);
                baos.close();

                ServletOutputStream output = httpResponse.getOutputStream();
                output.println(builder.toString());
                output.write(baos.toByteArray());
            }
            else if ("/close".equals(uri))
            {
                request.getConnection().getEndPoint().close();
            }
            else
            {
                throw new ServletException();
            }
        }
    }
}
