//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventSourceHandlerTest
{
    private Server server;
    private ServerConnector connector;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    private void startServer(EventSourceHandler handler) throws Exception
    {
        server.setHandler(handler);
        server.start();
    }

    @Test
    public void testBasicFunctionality() throws Exception
    {
        AtomicReference<EventSourceHandler.Emitter> emitterRef = new AtomicReference<>();
        CountDownLatch emitterLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);

        EventSourceHandler handler = new EventSourceHandler()
        {
            {
                setHeartBeatPeriod(Duration.ofSeconds(2));
            }

            @Override
            protected EventSource newEventSource(Request request)
            {
                return new EventSource()
                {
                    @Override
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitterRef.set(emitter);
                        emitterLatch.countDown();
                    }

                    @Override
                    public void onClose()
                    {
                        closeLatch.countDown();
                    }
                };
            }
        };

        startServer(handler);

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            writeHTTPRequest(socket);
            BufferedReader reader = readAndDiscardHTTPResponse(socket);

            assertTrue(emitterLatch.await(1, TimeUnit.SECONDS));
            EventSourceHandler.Emitter emitter = emitterRef.get();
            assertNotNull(emitter);

            String data = "foo";
            emitter.data(data);

            String line = reader.readLine();
            String received = "";
            while (line != null)
            {
                received += line;
                if (line.isEmpty())
                    break;
                line = reader.readLine();
            }

            assertEquals("data: " + data, received);

            socket.close();
            assertTrue(closeLatch.await(6, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testServerSideClose() throws Exception
    {
        AtomicReference<EventSourceHandler.Emitter> emitterRef = new AtomicReference<>();
        CountDownLatch emitterLatch = new CountDownLatch(1);

        EventSourceHandler handler = new EventSourceHandler()
        {
            @Override
            protected EventSource newEventSource(Request request)
            {
                return new EventSource()
                {
                    @Override
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitterRef.set(emitter);
                        emitterLatch.countDown();
                    }

                    @Override
                    public void onClose()
                    {
                    }
                };
            }
        };

        startServer(handler);

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            writeHTTPRequest(socket);
            BufferedReader reader = readAndDiscardHTTPResponse(socket);

            assertTrue(emitterLatch.await(1, TimeUnit.SECONDS));
            EventSourceHandler.Emitter emitter = emitterRef.get();
            assertNotNull(emitter);

            String comment = "foo";
            emitter.comment(comment);

            String line = reader.readLine();
            String received = "";
            while (line != null)
            {
                received += line;
                if (line.isEmpty())
                    break;
                line = reader.readLine();
            }

            assertEquals(": " + comment, received);

            emitter.close();

            line = reader.readLine();
            assertNull(line);
        }
    }

    @Test
    public void testEncoding() throws Exception
    {
        // The EURO symbol
        String data = "%E2%82%AC";

        EventSourceHandler handler = new EventSourceHandler()
        {
            @Override
            protected EventSource newEventSource(Request request)
            {
                return new EventSource()
                {
                    @Override
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitter.data(data);
                    }

                    @Override
                    public void onClose()
                    {
                    }
                };
            }
        };

        startServer(handler);

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            writeHTTPRequest(socket);
            BufferedReader reader = readAndDiscardHTTPResponse(socket);

            String line = reader.readLine();
            String received = "";
            while (line != null)
            {
                received += line;
                if (line.isEmpty())
                    break;
                line = reader.readLine();
            }

            assertEquals("data: " + data, received);
        }
    }

    @Test
    public void testMultiLineData() throws Exception
    {
        String data1 = "data1";
        String data2 = "data2";
        String data3 = "data3";
        String data4 = "data4";
        String data = data1 + "\r\n" + data2 + "\r" + data3 + "\n" + data4;

        EventSourceHandler handler = new EventSourceHandler()
        {
            @Override
            protected EventSource newEventSource(Request request)
            {
                return new EventSource()
                {
                    @Override
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitter.data(data);
                    }

                    @Override
                    public void onClose()
                    {
                    }
                };
            }
        };

        startServer(handler);

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            writeHTTPRequest(socket);
            BufferedReader reader = readAndDiscardHTTPResponse(socket);

            String line1 = reader.readLine();
            assertEquals("data: " + data1, line1);
            String line2 = reader.readLine();
            assertEquals("data: " + data2, line2);
            String line3 = reader.readLine();
            assertEquals("data: " + data3, line3);
            String line4 = reader.readLine();
            assertEquals("data: " + data4, line4);
            String line5 = reader.readLine();
            assertEquals(0, line5.length());
        }
    }

    @Test
    public void testNamedEvent() throws Exception
    {
        String name = "event1";
        String data = "data2";

        EventSourceHandler handler = new EventSourceHandler()
        {
            @Override
            protected EventSource newEventSource(Request request)
            {
                return new EventSource()
                {
                    @Override
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitter.event(name, data);
                    }

                    @Override
                    public void onClose()
                    {
                    }
                };
            }
        };

        startServer(handler);

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            writeHTTPRequest(socket);
            BufferedReader reader = readAndDiscardHTTPResponse(socket);

            String line1 = reader.readLine();
            assertEquals("event: " + name, line1);
            String line2 = reader.readLine();
            assertEquals("data: " + data, line2);
            String line3 = reader.readLine();
            assertEquals(0, line3.length());
        }
    }

    @Test
    public void testHeartBeat() throws Exception
    {
        AtomicReference<EventSourceHandler.Emitter> emitterRef = new AtomicReference<>();
        CountDownLatch emitterLatch = new CountDownLatch(1);

        EventSourceHandler handler = new EventSourceHandler()
        {
            {
                setHeartBeatPeriod(Duration.ofSeconds(1));
            }

            @Override
            protected EventSource newEventSource(Request request)
            {
                return new EventSource()
                {
                    @Override
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitterRef.set(emitter);
                        emitterLatch.countDown();
                    }

                    @Override
                    public void onClose()
                    {
                    }
                };
            }
        };

        startServer(handler);

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            writeHTTPRequest(socket);
            BufferedReader reader = readAndDiscardHTTPResponse(socket);

            assertTrue(emitterLatch.await(1, TimeUnit.SECONDS));
            EventSourceHandler.Emitter emitter = emitterRef.get();
            assertNotNull(emitter);

            // Wait for heartbeat (empty line)
            long start = System.nanoTime();
            String line = reader.readLine();
            long elapsed = System.nanoTime() - start;
            // Should receive heartbeat within about 1 second
            assertTrue(elapsed >= Duration.ofMillis(500).toNanos(), "Heartbeat received too quickly");
            assertEquals("", line);

            emitter.close();
        }
    }

    @Test
    public void testNullEventSourceReturns503() throws Exception
    {
        EventSourceHandler handler = new EventSourceHandler()
        {
            @Override
            protected EventSource newEventSource(Request request)
            {
                return null;
            }
        };

        startServer(handler);

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            writeHTTPRequest(socket);

            InputStream input = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            assertTrue(line.contains("503"));
        }
    }

    @Test
    public void testNonGetRequestNotHandled() throws Exception
    {
        EventSourceHandler handler = new EventSourceHandler()
        {
            @Override
            protected EventSource newEventSource(Request request)
            {
                return new EventSource()
                {
                    @Override
                    public void onOpen(Emitter emitter)
                    {
                    }

                    @Override
                    public void onClose()
                    {
                    }
                };
            }
        };

        // Wrap with a fallback handler that returns 404
        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setHandler(new Handler.Sequence(handler, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(404);
                callback.succeeded();
                return true;
            }
        }));

        server.setHandler(contextHandler);
        server.start();

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            int serverPort = socket.getPort();
            OutputStream output = socket.getOutputStream();

            String request = "POST /eventsource HTTP/1.1\r\n" +
                "Host: localhost:" + serverPort + "\r\n" +
                "Accept: text/event-stream\r\n" +
                "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            assertTrue(line.contains("404"));
        }
    }

    @Test
    public void testMissingAcceptHeaderNotHandled() throws Exception
    {
        EventSourceHandler handler = new EventSourceHandler()
        {
            @Override
            protected EventSource newEventSource(Request request)
            {
                return new EventSource()
                {
                    @Override
                    public void onOpen(Emitter emitter)
                    {
                    }

                    @Override
                    public void onClose()
                    {
                    }
                };
            }
        };

        // Wrap with a fallback handler that returns 404
        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setHandler(new Handler.Sequence(handler, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(404);
                callback.succeeded();
                return true;
            }
        }));

        server.setHandler(contextHandler);
        server.start();

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            int serverPort = socket.getPort();
            OutputStream output = socket.getOutputStream();

            // Request without Accept: text/event-stream header
            String request = "GET /eventsource HTTP/1.1\r\n" +
                "Host: localhost:" + serverPort + "\r\n" +
                "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            assertTrue(line.contains("404"));
        }
    }

    private void writeHTTPRequest(Socket socket) throws IOException
    {
        int serverPort = socket.getPort();
        OutputStream output = socket.getOutputStream();

        String handshake = "GET /eventsource HTTP/1.1\r\n" +
            "Host: localhost:" + serverPort + "\r\n" +
            "Accept: text/event-stream\r\n" +
            "\r\n";
        output.write(handshake.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private BufferedReader readAndDiscardHTTPResponse(Socket socket) throws IOException
    {
        // Read and discard the HTTP response headers
        InputStream input = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line = reader.readLine();
        while (line != null)
        {
            if (line.isEmpty())
                break;
            line = reader.readLine();
        }
        // Now we can parse the event-source stream
        return reader;
    }
}
