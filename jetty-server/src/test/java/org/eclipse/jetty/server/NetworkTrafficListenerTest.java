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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.NetworkTrafficListener;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.After;
import org.junit.Test;

public class NetworkTrafficListenerTest
{
    private static final byte END_OF_CONTENT = '~';

    private Server server;
    private NetworkTrafficServerConnector connector;

    public void initConnector(Handler handler) throws Exception
    {
        server = new Server();

        connector = new NetworkTrafficServerConnector(server);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @After
    public void destroyConnector() throws Exception
    {
        if (server != null)
        {
            server.stop();
            server.join();
        }
    }

    @Test
    public void testOpenedClosedAreInvoked() throws Exception
    {
        initConnector(null);

        final CountDownLatch openedLatch = new CountDownLatch(1);
        final CountDownLatch closedLatch = new CountDownLatch(1);
        connector.addNetworkTrafficListener(new NetworkTrafficListener.Adapter()
        {
            public volatile Socket socket;

            @Override
            public void opened(Socket socket)
            {
                this.socket = socket;
                openedLatch.countDown();
            }

            @Override
            public void closed(Socket socket)
            {
                if (this.socket == socket)
                    closedLatch.countDown();
            }
        });
        int port = connector.getLocalPort();

        // Connect to the server
        Socket socket = new Socket("localhost", port);
        assertTrue(openedLatch.await(10, TimeUnit.SECONDS));

        socket.close();
        assertTrue(closedLatch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testTrafficWithNoResponseContentOnNonPersistentConnection() throws Exception
    {
        initConnector(new AbstractHandler()
        {
            @Override
            public void handle(String uri, Request request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException, ServletException
            {
                request.setHandled(true);
            }
        });

        final AtomicReference<String> incomingData = new AtomicReference<>();
        final CountDownLatch incomingLatch = new CountDownLatch(1);
        final AtomicReference<String> outgoingData = new AtomicReference<>("");
        final CountDownLatch outgoingLatch = new CountDownLatch(1);
        connector.addNetworkTrafficListener(new NetworkTrafficListener.Adapter()
        {
            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                incomingData.set(BufferUtil.toString(bytes,StandardCharsets.UTF_8));
                incomingLatch.countDown();
            }

            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                outgoingData.set(outgoingData.get() + BufferUtil.toString(bytes,StandardCharsets.UTF_8));
                outgoingLatch.countDown();
            }
        });
        int port = connector.getLocalPort();

        String request = "" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String expectedResponse = "" +
                "HTTP/1.1 200 OK\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        Socket socket = new Socket("localhost", port);
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.UTF_8));
        output.flush();

        assertTrue(incomingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(request, incomingData.get());

        assertTrue(outgoingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(expectedResponse, outgoingData.get());

        byte[] responseBytes = readResponse(socket);
        String response = new String(responseBytes, StandardCharsets.UTF_8);
        assertEquals(expectedResponse, response);

        socket.close();
    }

    @Test
    public void testTrafficWithResponseContentOnPersistentConnection() throws Exception
    {
        final String responseContent = "response_content";
        initConnector(new AbstractHandler()
        {
            @Override
            public void handle(String uri, Request request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException, ServletException
            {
                request.setHandled(true);
                ServletOutputStream output = servletResponse.getOutputStream();
                output.write(responseContent.getBytes(StandardCharsets.UTF_8));
                output.write(END_OF_CONTENT);
            }
        });

        final AtomicReference<String> incomingData = new AtomicReference<>();
        final CountDownLatch incomingLatch = new CountDownLatch(1);
        final AtomicReference<String> outgoingData = new AtomicReference<>("");
        final CountDownLatch outgoingLatch = new CountDownLatch(2);
        connector.addNetworkTrafficListener(new NetworkTrafficListener.Adapter()
        {
            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                incomingData.set(BufferUtil.toString(bytes,StandardCharsets.UTF_8));
                incomingLatch.countDown();
            }

            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                outgoingData.set(outgoingData.get() + BufferUtil.toString(bytes,StandardCharsets.UTF_8));
                outgoingLatch.countDown();
            }
        });
        int port = connector.getLocalPort();

        String request = "" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "\r\n";
        String expectedResponse = "" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: " + (responseContent.length() + 1) + "\r\n" +
                "\r\n" +
                "" + responseContent + (char)END_OF_CONTENT;

        Socket socket = new Socket("localhost", port);
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.UTF_8));
        output.flush();

        assertTrue(incomingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(request, incomingData.get());

        assertTrue(outgoingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(expectedResponse, outgoingData.get());

        byte[] responseBytes = readResponse(socket);
        String response = new String(responseBytes, StandardCharsets.UTF_8);
        assertEquals(expectedResponse, response);

        socket.close();
    }

    @Test
    public void testTrafficWithResponseContentChunkedOnPersistentConnection() throws Exception
    {
        final String responseContent = "response_content";
        final String responseChunk1 = "response_content".substring(0, responseContent.length() / 2);
        final String responseChunk2 = "response_content".substring(responseContent.length() / 2, responseContent.length());
        initConnector(new AbstractHandler()
        {
            @Override
            public void handle(String uri, Request request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException, ServletException
            {
                request.setHandled(true);
                ServletOutputStream output = servletResponse.getOutputStream();
                output.write(responseChunk1.getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.write(responseChunk2.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        });

        final AtomicReference<String> incomingData = new AtomicReference<>();
        final CountDownLatch incomingLatch = new CountDownLatch(1);
        final AtomicReference<String> outgoingData = new AtomicReference<>("");
        final CountDownLatch outgoingLatch = new CountDownLatch(1);
        connector.addNetworkTrafficListener(new NetworkTrafficListener.Adapter()
        {
            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                incomingData.set(BufferUtil.toString(bytes,StandardCharsets.UTF_8));
                incomingLatch.countDown();
            }

            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                outgoingData.set(outgoingData.get() + BufferUtil.toString(bytes,StandardCharsets.UTF_8));                
                if (outgoingData.get().endsWith("\r\n0\r\n\r\n"))
                    outgoingLatch.countDown();
            }
        });
        int port = connector.getLocalPort();

        String request = "" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "\r\n";
        String expectedResponse = "" +
                "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                responseChunk1.length() + "\r\n" +
                responseChunk1 + "\r\n" +
                responseChunk2.length() + "\r\n" +
                responseChunk2 + "\r\n" +
                "0\r\n" +
                "\r\n";

        Socket socket = new Socket("localhost", port);
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.UTF_8));
        output.flush();

        assertTrue(incomingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(request, incomingData.get());

        assertTrue(outgoingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(expectedResponse, outgoingData.get());

        byte[] responseBytes = readResponse(socket);
        String response = new String(responseBytes, StandardCharsets.UTF_8);
        assertEquals(expectedResponse, response);

        socket.close();
    }

    @Test
    public void testTrafficWithRequestContentWithResponseRedirectOnPersistentConnection() throws Exception
    {
        final String location = "/redirect";
        initConnector(new AbstractHandler()
        {
            @Override
            public void handle(String uri, Request request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException, ServletException
            {
                request.setHandled(true);
                servletResponse.sendRedirect(location);
            }
        });

        final AtomicReference<String> incomingData = new AtomicReference<>();
        final CountDownLatch incomingLatch = new CountDownLatch(1);
        final AtomicReference<String> outgoingData = new AtomicReference<>("");
        final CountDownLatch outgoingLatch = new CountDownLatch(1);
        connector.addNetworkTrafficListener(new NetworkTrafficListener.Adapter()
        {
            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                incomingData.set(BufferUtil.toString(bytes,StandardCharsets.UTF_8));
                incomingLatch.countDown();
            }

            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                outgoingData.set(outgoingData.get() + BufferUtil.toString(bytes,StandardCharsets.UTF_8));
                outgoingLatch.countDown();
            }
        });
        int port = connector.getLocalPort();

        String requestContent = "a=1&b=2";
        String request = "" +
                "POST / HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + requestContent.length() + "\r\n" +
                "\r\n" +
                requestContent;
        String expectedResponse = "" +
                "HTTP/1.1 302 Found\r\n" +
                "Location: http://localhost:" + port + location + "\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";

        Socket socket = new Socket("localhost", port);
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.UTF_8));
        output.flush();

        assertTrue(incomingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(request, incomingData.get());

        assertTrue(outgoingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(expectedResponse, outgoingData.get());

        byte[] responseBytes = readResponse(socket);
        String response = new String(responseBytes, StandardCharsets.UTF_8);
        assertEquals(expectedResponse, response);

        socket.close();
    }

    @Test
    public void testTrafficWithBigRequestContentOnPersistentConnection() throws Exception
    {
        initConnector(new AbstractHandler()
        {
            @Override
            public void handle(String uri, Request request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException, ServletException
            {
                // Read and discard the request body to make the test more
                // reliable, otherwise there is a race between request body
                // upload and response download
                InputStream input = servletRequest.getInputStream();
                byte[] buffer = new byte[4096];
                while (true)
                {
                    int read = input.read(buffer);
                    if (read < 0)
                        break;
                }
                request.setHandled(true);
            }
        });

        final AtomicReference<String> incomingData = new AtomicReference<>("");
        final AtomicReference<String> outgoingData = new AtomicReference<>("");
        final CountDownLatch outgoingLatch = new CountDownLatch(1);
        connector.addNetworkTrafficListener(new NetworkTrafficListener.Adapter()
        {
            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                incomingData.set(incomingData.get() + BufferUtil.toString(bytes,StandardCharsets.UTF_8));
            }

            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                outgoingData.set(outgoingData.get() + BufferUtil.toString(bytes,StandardCharsets.UTF_8));
                outgoingLatch.countDown();
            }
        });
        int port = connector.getLocalPort();

        // Generate 32 KiB of request content
        String requestContent = "0123456789ABCDEF";
        for (int i = 0; i < 11; ++i)
            requestContent += requestContent;
        String request = "" +
                "POST / HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + requestContent.length() + "\r\n" +
                "\r\n" +
                requestContent;
        String expectedResponse = "" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";

        Socket socket = new Socket("localhost", port);
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.UTF_8));
        output.flush();

        assertTrue(outgoingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(expectedResponse, outgoingData.get());

        byte[] responseBytes = readResponse(socket);
        String response = new String(responseBytes, StandardCharsets.UTF_8);
        assertEquals(expectedResponse, response);

        assertEquals(request, incomingData.get());

        socket.close();
    }

    private byte[] readResponse(Socket socket) throws IOException
    {
        socket.setSoTimeout(5000);
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int read;
        while ((read = input.read()) >= 0)
        {
            baos.write(read);

            // Handle non-chunked end of response
            if (read == END_OF_CONTENT)
                break;

            // Handle chunked end of response
            String response = baos.toString("UTF-8");
            if (response.endsWith("\r\n0\r\n\r\n"))
                break;

            // Handle non-content responses
            if (response.contains("Content-Length: 0") && response.endsWith("\r\n\r\n"))
                break;
        }
        return baos.toByteArray();
    }
}
