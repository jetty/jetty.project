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

package org.eclipse.jetty.servlets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class EventSourceServletTest
{
    private Server server;
    private NetworkConnector connector;
    private ServletContextHandler context;

    @Before
    public void startServer() throws Exception
    {
        server = new Server(0);
        connector = (NetworkConnector)server.getConnectors()[0];

        String contextPath = "/test";
        context = new ServletContextHandler(server, contextPath, ServletContextHandler.SESSIONS);
        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testBasicFunctionality() throws Exception
    {
        final AtomicReference<EventSource.Emitter> emitterRef = new AtomicReference<EventSource.Emitter>();
        final CountDownLatch emitterLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);
        class S extends EventSourceServlet
        {
            @Override
            protected EventSource newEventSource(HttpServletRequest request)
            {
                return new EventSource()
                {
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitterRef.set(emitter);
                        emitterLatch.countDown();
                    }

                    public void onClose()
                    {
                        closeLatch.countDown();
                    }
                };
            }
        }

        String servletPath = "/eventsource";
        ServletHolder servletHolder = new ServletHolder(new S());
        int heartBeatPeriod = 2;
        servletHolder.setInitParameter("heartBeatPeriod", String.valueOf(heartBeatPeriod));
        context.addServlet(servletHolder, servletPath);

        Socket socket = new Socket("localhost", connector.getLocalPort());
        writeHTTPRequest(socket, servletPath);
        BufferedReader reader = readAndDiscardHTTPResponse(socket);

        Assert.assertTrue(emitterLatch.await(1, TimeUnit.SECONDS));
        EventSource.Emitter emitter = emitterRef.get();
        Assert.assertNotNull(emitter);

        String data = "foo";
        emitter.data(data);

        String line = reader.readLine();
        String received = "";
        while (line != null)
        {
            received += line;
            if (line.length() == 0)
                break;
            line = reader.readLine();
        }

        Assert.assertEquals("data: " + data, received);

        socket.close();
        Assert.assertTrue(closeLatch.await(heartBeatPeriod * 3, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSideClose() throws Exception
    {
        final AtomicReference<EventSource.Emitter> emitterRef = new AtomicReference<EventSource.Emitter>();
        final CountDownLatch emitterLatch = new CountDownLatch(1);
        class S extends EventSourceServlet
        {
            @Override
            protected EventSource newEventSource(HttpServletRequest request)
            {
                return new EventSource()
                {
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitterRef.set(emitter);
                        emitterLatch.countDown();
                    }

                    public void onClose()
                    {
                    }
                };
            }
        }

        String servletPath = "/eventsource";
        context.addServlet(new ServletHolder(new S()), servletPath);

        Socket socket = new Socket("localhost", connector.getLocalPort());
        writeHTTPRequest(socket, servletPath);
        BufferedReader reader = readAndDiscardHTTPResponse(socket);

        Assert.assertTrue(emitterLatch.await(1, TimeUnit.SECONDS));
        EventSource.Emitter emitter = emitterRef.get();
        Assert.assertNotNull(emitter);

        String comment = "foo";
        emitter.comment(comment);

        String line = reader.readLine();
        String received = "";
        while (line != null)
        {
            received += line;
            if (line.length() == 0)
                break;
            line = reader.readLine();
        }

        Assert.assertEquals(": " + comment, received);

        emitter.close();

        line = reader.readLine();
        Assert.assertNull(line);

        socket.close();
    }

    @Test
    public void testEncoding() throws Exception
    {
        // The EURO symbol
        final String data = "\u20AC";
        class S extends EventSourceServlet
        {
            @Override
            protected EventSource newEventSource(HttpServletRequest request)
            {
                return new EventSource()
                {
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitter.data(data);
                    }

                    public void onClose()
                    {
                    }
                };
            }
        }

        String servletPath = "/eventsource";
        context.addServlet(new ServletHolder(new S()), servletPath);

        Socket socket = new Socket("localhost", connector.getLocalPort());
        writeHTTPRequest(socket, servletPath);
        BufferedReader reader = readAndDiscardHTTPResponse(socket);

        String line = reader.readLine();
        String received = "";
        while (line != null)
        {
            received += line;
            if (line.length() == 0)
                break;
            line = reader.readLine();
        }

        Assert.assertEquals("data: " + data, received);

        socket.close();
    }

    @Test
    public void testMultiLineData() throws Exception
    {
        String data1 = "data1";
        String data2 = "data2";
        String data3 = "data3";
        String data4 = "data4";
        final String data = data1 + "\r\n" + data2 + "\r" + data3 + "\n" + data4;
        class S extends EventSourceServlet
        {
            @Override
            protected EventSource newEventSource(HttpServletRequest request)
            {
                return new EventSource()
                {
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitter.data(data);
                    }

                    public void onClose()
                    {
                    }
                };
            }
        }

        String servletPath = "/eventsource";
        context.addServlet(new ServletHolder(new S()), servletPath);

        Socket socket = new Socket("localhost", connector.getLocalPort());
        writeHTTPRequest(socket, servletPath);
        BufferedReader reader = readAndDiscardHTTPResponse(socket);

        String line1 = reader.readLine();
        Assert.assertEquals("data: " + data1, line1);
        String line2 = reader.readLine();
        Assert.assertEquals("data: " + data2, line2);
        String line3 = reader.readLine();
        Assert.assertEquals("data: " + data3, line3);
        String line4 = reader.readLine();
        Assert.assertEquals("data: " + data4, line4);
        String line5 = reader.readLine();
        Assert.assertEquals(0, line5.length());

        socket.close();
    }

    @Test
    public void testEvents() throws Exception
    {
        final String name = "event1";
        final String data = "data2";
        class S extends EventSourceServlet
        {
            @Override
            protected EventSource newEventSource(HttpServletRequest request)
            {
                return new EventSource()
                {
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitter.event(name, data);
                    }

                    public void onClose()
                    {
                    }
                };
            }
        }

        String servletPath = "/eventsource";
        context.addServlet(new ServletHolder(new S()), servletPath);

        Socket socket = new Socket("localhost", connector.getLocalPort());
        writeHTTPRequest(socket, servletPath);
        BufferedReader reader = readAndDiscardHTTPResponse(socket);

        String line1 = reader.readLine();
        Assert.assertEquals("event: " + name, line1);
        String line2 = reader.readLine();
        Assert.assertEquals("data: " + data, line2);
        String line3 = reader.readLine();
        Assert.assertEquals(0, line3.length());

        socket.close();
    }

    private void writeHTTPRequest(Socket socket, String servletPath) throws IOException
    {
        int serverPort = socket.getPort();
        OutputStream output = socket.getOutputStream();

        String handshake = "";
        handshake += "GET " + context.getContextPath() + servletPath + " HTTP/1.1\r\n";
        handshake += "Host: localhost:" + serverPort + "\r\n";
        handshake += "Accept: text/event-stream\r\n";
        handshake += "\r\n";
        output.write(handshake.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private BufferedReader readAndDiscardHTTPResponse(Socket socket) throws IOException
    {
        // Read and discard the HTTP response
        InputStream input = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line = reader.readLine();
        while (line != null)
        {
            if (line.length() == 0)
                break;
            line = reader.readLine();
        }
        // Now we can parse the event-source stream
        return reader;
    }
}
