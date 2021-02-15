//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HotSwapHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class HttpServerTestFixture
{
    // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
    
    // Useful constants
    protected static final long PAUSE = 10L;
    protected static final int LOOPS = 50;

    protected QueuedThreadPool _threadPool;
    protected Server _server;
    protected URI _serverURI;
    protected HttpConfiguration _httpConfiguration;
    protected ServerConnector _connector;
    protected String _scheme = "http";

    protected Socket newSocket(String host, int port) throws Exception
    {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    @BeforeEach
    public void before()
    {
        _threadPool = new QueuedThreadPool();
        _server = new Server(_threadPool);
    }

    protected void startServer(ServerConnector connector) throws Exception
    {
        startServer(connector, new HotSwapHandler());
    }

    protected void startServer(ServerConnector connector, Handler handler) throws Exception
    {
        _connector = connector;
        _httpConfiguration = _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();
        _httpConfiguration.setBlockingTimeout(-1);
        _httpConfiguration.setSendDateHeader(false);
        _server.addConnector(_connector);
        _server.setHandler(handler);
        _server.start();
        _serverURI = _server.getURI();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        _server.stop();
        _server.join();
        _server.setConnectors(new Connector[]{});
    }

    protected void configureServer(Handler handler) throws Exception
    {
        HotSwapHandler swapper = (HotSwapHandler)_server.getHandler();
        swapper.setHandler(handler);
        handler.start();
    }

    protected static class EchoHandler extends AbstractHandler
    {
        boolean _musthavecontent = true;

        public EchoHandler()
        {
        }

        public EchoHandler(boolean content)
        {
            _musthavecontent = false;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            Log.getRootLogger().debug("handle " + target);
            baseRequest.setHandled(true);

            if (request.getContentType() != null)
                response.setContentType(request.getContentType());
            if (request.getParameter("charset") != null)
                response.setCharacterEncoding(request.getParameter("charset"));
            else if (request.getCharacterEncoding() != null)
                response.setCharacterEncoding(request.getCharacterEncoding());

            PrintWriter writer = response.getWriter();

            int count = 0;
            BufferedReader reader = request.getReader();

            if (request.getContentLength() != 0)
            {
                String line = reader.readLine();
                while (line != null)
                {
                    writer.print(line);
                    writer.print("\n");
                    count += line.length();
                    line = reader.readLine();
                }
            }

            if (count == 0)
            {
                if (_musthavecontent)
                    throw new IllegalStateException("no input received");

                writer.println("No content");
            }

            // just to be difficult
            reader.close();
            writer.close();

            if (reader.read() >= 0)
                throw new IllegalStateException("Not closed");

            Log.getRootLogger().debug("handled " + target);
        }
    }

    protected static class OptionsHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            if (request.getMethod().equals("OPTIONS"))
                response.setStatus(200);
            else
                response.setStatus(500);

            response.setHeader("Allow", "GET");
        }
    }

    protected static class HelloWorldHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.getOutputStream().print("Hello world\r\n");
        }
    }

    protected static class SendErrorHandler extends AbstractHandler
    {
        private final int code;
        private final String message;

        public SendErrorHandler()
        {
            this(500, null);
        }

        public SendErrorHandler(int code, String message)
        {
            this.code = code;
            this.message = message;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.sendError(code, message);
        }
    }

    protected static class ReadExactHandler extends AbstractHandler
    {
        private int expected;

        public ReadExactHandler()
        {
            this(-1);
        }

        public ReadExactHandler(int expected)
        {
            this.expected = expected;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            int len = expected < 0 ? request.getContentLength() : expected;
            if (len < 0)
                throw new IllegalStateException();
            byte[] content = new byte[len];
            int offset = 0;
            while (offset < len)
            {
                int read = request.getInputStream().read(content, offset, len - offset);
                if (read < 0)
                    break;
                offset += read;
            }
            response.setStatus(200);
            String reply = "Read " + offset + "\r\n";
            response.setContentLength(reply.length());
            response.getOutputStream().write(reply.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    protected static class ReadHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);

            try
            {
                InputStream in = request.getInputStream();
                String input = IO.toString(in);
                response.getWriter().printf("read %d%n", input.length());
            }
            catch (Exception e)
            {
                response.getWriter().printf("caught %s%n", e);
            }
        }
    }

    protected static class DataHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);

            InputStream in = request.getInputStream();
            String input = IO.toString(in);

            String tmp = request.getParameter("writes");
            int writes = Integer.parseInt(tmp == null ? "10" : tmp);
            tmp = request.getParameter("block");
            int block = Integer.parseInt(tmp == null ? "10" : tmp);
            String encoding = request.getParameter("encoding");
            String chars = request.getParameter("chars");

            String data = "\u0a870123456789A\u0a87CDEFGHIJKLMNOPQRSTUVWXYZ\u0250bcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
            while (data.length() < block)
            {
                data += data;
            }

            String chunk = (input + data).substring(0, block);
            response.setContentType("text/plain");
            if (encoding == null)
            {
                byte[] bytes = chunk.getBytes(StandardCharsets.ISO_8859_1);
                OutputStream out = response.getOutputStream();
                for (int i = 0; i < writes; i++)
                {
                    out.write(bytes);
                }
            }
            else if ("true".equals(chars))
            {
                response.setCharacterEncoding(encoding);
                PrintWriter out = response.getWriter();
                char[] c = chunk.toCharArray();
                for (int i = 0; i < writes; i++)
                {
                    out.write(c);
                    if (out.checkError())
                        break;
                }
            }
            else
            {
                response.setCharacterEncoding(encoding);
                PrintWriter out = response.getWriter();
                for (int i = 0; i < writes; i++)
                {
                    out.write(chunk);
                    if (out.checkError())
                        break;
                }
            }
        }
    }
}
