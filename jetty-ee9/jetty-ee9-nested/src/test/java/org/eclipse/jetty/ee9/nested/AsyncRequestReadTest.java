//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.nested;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncRequestReadTest
{
    private Server _server;
    private ContextHandler _context;
    private ServerConnector _connector;
    private static final BlockingQueue<Long> __total = new BlockingArrayQueue<>();

    @BeforeEach
    public void startServer() throws Exception
    {
        _server = new Server();
        _context = new ContextHandler(_server, "/");
        _connector = new ServerConnector(_server);
        _connector.setIdleTimeout(10000);
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);
        _server.addConnector(_connector);
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testPipelined() throws Exception
    {
        _context.setHandler(new AsyncStreamHandler());
        _server.start();

        try (final Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            socket.setSoTimeout(1000);

            byte[] content = new byte[32 * 4096];
            Arrays.fill(content, (byte)120);

            OutputStream out = socket.getOutputStream();
            String header =
                "POST / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: " + content.length + "\r\n" +
                    "Content-Type: bytes\r\n" +
                    "\r\n";
            byte[] h = header.getBytes(StandardCharsets.ISO_8859_1);
            out.write(h);
            out.write(content);

            header =
                "POST / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: " + content.length + "\r\n" +
                    "Content-Type: bytes\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            h = header.getBytes(StandardCharsets.ISO_8859_1);
            out.write(h);
            out.write(content);
            out.flush();

            InputStream in = socket.getInputStream();
            String response = IO.toString(in);
            assertTrue(response.indexOf("200 OK") > 0);

            long total = __total.poll(5, TimeUnit.SECONDS);
            assertEquals(content.length, total);
            total = __total.poll(5, TimeUnit.SECONDS);
            assertEquals(content.length, total);
        }
    }

    @Test
    public void testAsyncReadsWithDelays() throws Exception
    {
        _context.setHandler(new AsyncStreamHandler());
        _server.start();

        asyncReadTest(64, 4, 4, 20);
        asyncReadTest(256, 16, 16, 50);
        asyncReadTest(256, 1, 128, 10);
        asyncReadTest(128 * 1024, 1, 64, 10);
        asyncReadTest(256 * 1024, 5321, 10, 100);
        asyncReadTest(512 * 1024, 32 * 1024, 10, 10);
    }

    public void asyncReadTest(int contentSize, int chunkSize, int chunks, int delayMS) throws Exception
    {
        String tst = contentSize + "," + chunkSize + "," + chunks + "," + delayMS;
        //System.err.println(tst);

        try (final Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {

            byte[] content = new byte[contentSize];
            Arrays.fill(content, (byte)120);

            OutputStream out = socket.getOutputStream();
            out.write("POST / HTTP/1.1\r\n".getBytes());
            out.write("Host: localhost\r\n".getBytes());
            out.write(("Content-Length: " + content.length + "\r\n").getBytes());
            out.write("Content-Type: bytes\r\n".getBytes());
            out.write("Connection: close\r\n".getBytes());
            out.write("\r\n".getBytes());
            out.flush();

            int offset = 0;
            for (int i = 0; i < chunks; i++)
            {
                out.write(content, offset, chunkSize);
                offset += chunkSize;
                Thread.sleep(delayMS);
            }
            out.write(content, offset, content.length - offset);

            out.flush();

            InputStream in = socket.getInputStream();
            String response = IO.toString(in);
            assertThat(response, containsString("200 OK"));

            long total = __total.poll(30, TimeUnit.SECONDS);
            assertEquals(content.length, total, tst);
        }
    }

    private static class AsyncStreamHandler extends AbstractHandler
    {
        @Override
        public void handle(String path, final Request request, HttpServletRequest httpRequest, final HttpServletResponse httpResponse) throws IOException, ServletException
        {
            httpResponse.setStatus(500);
            request.setHandled(true);

            final AsyncContext async = request.startAsync();
            // System.err.println("handle "+request.getContentLength());

            new Thread()
            {
                @Override
                public void run()
                {
                    long total = 0;
                    try (InputStream in = request.getInputStream();)
                    {
                        // System.err.println("reading...");

                        byte[] b = new byte[4 * 4096];
                        int read;
                        while ((read = in.read(b)) >= 0)
                        {
                            total += read;
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        total = -1;
                    }
                    finally
                    {
                        httpResponse.setStatus(200);
                        async.complete();
                        // System.err.println("read "+total);
                        __total.offer(total);
                    }
                }
            }.start();
        }
    }

    @Test
    public void testPartialRead() throws Exception
    {
        _context.setHandler(new PartialReaderHandler());
        _server.start();

        try (final Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            socket.setSoTimeout(10000);

            byte[] content = new byte[32 * 4096];
            Arrays.fill(content, (byte)88);

            OutputStream out = socket.getOutputStream();
            String header =
                "POST /?read=10 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: " + content.length + "\r\n" +
                    "Content-Type: bytes\r\n" +
                    "\r\n";
            byte[] h = header.getBytes(StandardCharsets.ISO_8859_1);
            out.write(h);
            out.write(content);

            header = "POST /?read=10 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: " + content.length + "\r\n" +
                "Content-Type: bytes\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            h = header.getBytes(StandardCharsets.ISO_8859_1);
            out.write(h);
            out.write(content);
            out.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(in.readLine(), containsString("HTTP/1.1 200 OK"));
            assertThat(in.readLine(), containsString("Content-Length: 11"));
            assertThat(in.readLine(), containsString("Server:"));
            in.readLine();
            assertThat(in.readLine(), containsString("XXXXXXX"));
            assertThat(in.readLine(), containsString("HTTP/1.1 200 OK"));
            assertThat(in.readLine(), containsString("Connection: close"));
            assertThat(in.readLine(), containsString("Content-Length: 11"));
            assertThat(in.readLine(), containsString("Server:"));
            in.readLine();
            assertThat(in.readLine(), containsString("XXXXXXX"));
        }
    }

    @Test
    public void testPartialReadThenShutdown() throws Exception
    {
        _context.setHandler(new PartialReaderHandler());
        _server.start();

        try (final Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            socket.setSoTimeout(10000);

            byte[] content = new byte[32 * 4096];
            Arrays.fill(content, (byte)88);

            OutputStream out = socket.getOutputStream();
            String header =
                "POST /?read=10 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: " + content.length + "\r\n" +
                    "Content-Type: bytes\r\n" +
                    "\r\n";
            byte[] h = header.getBytes(StandardCharsets.ISO_8859_1);
            out.write(h);
            out.write(content, 0, 4096);
            out.flush();
            socket.shutdownOutput();

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(in.readLine(), containsString("HTTP/1.1 200 OK"));
            assertThat(in.readLine(), containsString("Connection: close"));
            assertThat(in.readLine(), containsString("Content-Length:"));
            assertThat(in.readLine(), containsString("Server:"));
            in.readLine();
            assertThat(in.readLine(), containsString("XXXXXXX"));
        }
    }

    @Test
    public void testPartialReadThenClose() throws Exception
    {
        _context.setHandler(new PartialReaderHandler());
        _server.start();

        try (final Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            socket.setSoTimeout(1000);

            byte[] content = new byte[32 * 4096];
            Arrays.fill(content, (byte)88);

            OutputStream out = socket.getOutputStream();
            String header =
                "POST /?read=10 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: " + content.length + "\r\n" +
                    "Content-Type: bytes\r\n" +
                    "\r\n";
            byte[] h = header.getBytes(StandardCharsets.ISO_8859_1);
            out.write(h);
            out.write(content, 0, 4096);
            out.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(in.readLine(), containsString("HTTP/1.1 200 OK"));
            assertThat(in.readLine(), containsString("Connection: close"));
            assertThat(in.readLine(), containsString("Content-Length:"));
            assertThat(in.readLine(), containsString("Server:"));
            in.readLine();
            assertThat(in.readLine(), containsString("XXXXXXX"));

            socket.close();
        }
    }

    private static class PartialReaderHandler extends AbstractHandler
    {
        @Override
        public void handle(String path, final Request request, HttpServletRequest httpRequest, final HttpServletResponse httpResponse) throws IOException, ServletException
        {
            httpResponse.setStatus(200);
            request.setHandled(true);

            BufferedReader in = request.getReader();
            PrintWriter out = httpResponse.getWriter();
            int read = Integer.parseInt(request.getParameter("read"));
            // System.err.println("read="+read);
            for (int i = read; i-- > 0; )
            {
                int c = in.read();
                // System.err.println("in="+c);
                if (c < 0)
                    break;
                out.write(c);
            }
            out.write('\n');
        }
    }
}
