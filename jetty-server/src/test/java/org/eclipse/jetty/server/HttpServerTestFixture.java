//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BlockingCallback;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class HttpServerTestFixture
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpServerTestFixture.class);

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
        startServer(connector, new Handler.HotSwap());
    }

    protected void startServer(ServerConnector connector, Handler handler) throws Exception
    {
        _connector = connector;
        _httpConfiguration = _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();
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
        Handler.HotSwap swapper = (Handler.HotSwap)_server.getHandler();
        swapper.setHandler(handler);
        handler.start();
    }

    protected static class OptionsHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response) throws Exception
        {
            if (request.getMethod().equals("OPTIONS"))
                response.setStatus(200);
            else
                response.setStatus(500);
            response.setHeader("Allow", "GET");
            request.succeeded();
            return true;
        }
    }

    protected static class SendErrorHandler extends Handler.Abstract
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
        public boolean handle(Request request, Response response) throws Exception
        {
            response.sendError(code, message, request);
            return true;
        }
    }

    protected static class ReadExactHandler extends Handler.Abstract
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
        public boolean handle(Request request, Response response) throws Exception
        {
            long len = expected < 0 ? request.getContentLength() : expected;
            if (len < 0)
                throw new IllegalStateException();
            byte[] content = new byte[(int)len];
            int offset = 0;
            while (offset < len)
            {
                Content c = request.readContent();
                if (c == null)
                {
                    try (Blocker blocker = new SharedBlockingCallback().acquire())
                    {
                        request.demandContent(blocker);
                        blocker.block();
                    }
                    continue;
                }

                if (c.hasRemaining())
                {
                    int r = c.remaining();
                    c.fill(content, offset, r);
                    offset += r;
                    c.release();
                }

                if (c.isLast())
                    break;
            }
            response.setStatus(200);
            String reply = "Read " + offset + "\r\n";
            response.setContentLength(reply.length());
            response.write(true, request, BufferUtil.toBuffer(reply, StandardCharsets.ISO_8859_1));

            return true;
        }
    }

    protected static class ReadHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response) throws Exception
        {
            response.setStatus(200);
            Content.readUtf8String(request, Promise.from(
                s -> response.write(true, request, "read %d%n" + s.length()),
                t -> response.write(true, request, String.format("caught %s%n", t))
            ));
            return true;
        }
    }

    protected static class DataHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response) throws Exception
        {
            response.setStatus(200);

            String input = Content.readUtf8String(request);
            MultiMap<String> params = request.extractQueryParameters();

            String tmp = params.getValue("writes");
            int writes = Integer.parseInt(tmp == null ? "10" : tmp);
            tmp = params.getValue("block");
            int block = Integer.parseInt(tmp == null ? "10" : tmp);
            String encoding = params.getValue("encoding");
            String chars = params.getValue("chars");
            if (chars != null)
                throw new IllegalStateException("chars no longer supported"); // TODO remove

            String data = "\u0a870123456789A\u0a87CDEFGHIJKLMNOPQRSTUVWXYZ\u0250bcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
            while (data.length() < block)
            {
                data += data;
            }

            String chunk = (input + data).substring(0, block);
            if (encoding == null)
            {
                response.setContentType("text/plain");
                ByteBuffer bytes = BufferUtil.toBuffer(chunk, StandardCharsets.ISO_8859_1);
                for (int i = writes; i-- > 0;)
                {
                    try (BlockingCallback blocker = new BlockingCallback())
                    {
                        response.write(i == 0, blocker, bytes.slice());
                    }
                }
            }
            else
            {
                response.setContentType("text/plain;charset=" + encoding);
                ByteBuffer bytes = BufferUtil.toBuffer(chunk, Charset.forName(encoding));
                for (int i = writes; i-- > 0;)
                {
                    try (BlockingCallback blocker = new BlockingCallback())
                    {
                        response.write(i == 0, blocker, bytes.slice());
                    }
                }
            }
            request.succeeded();
            return true;
        }
    }
}
