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

package org.eclipse.jetty.server;

import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class HttpServerTestFixture
{
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

    protected void initServer(ServerConnector connector) throws Exception
    {
        _connector = connector;
        _httpConfiguration = _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();
        _httpConfiguration.setSendDateHeader(false);
        _server.addConnector(_connector);
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        _server.stop();
        _server.join();
        _server.setConnectors(new Connector[]{});
    }

    protected void startServer(Handler handler) throws Exception
    {
        _server.setHandler(handler);
        _server.start();
        _serverURI = _server.getURI();
    }

    protected static class OptionsHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback)
        {
            if (request.getMethod().equals("OPTIONS"))
                response.setStatus(200);
            else
                response.setStatus(500);
            response.getHeaders().put("Allow", "GET");
            callback.succeeded();
        }
    }

    protected static class HelloWorldHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);
            response.write(true, callback, "Hello world\r\n");
        }
    }

    protected static class SendErrorHandler extends Handler.Processor
    {
        private final int code;
        private final String message;

        public SendErrorHandler(int code, String message)
        {
            this.code = code;
            this.message = message;
        }

        @Override
        public void process(Request request, Response response, Callback callback)
        {
            Response.writeError(request, response, callback, code, message);
        }
    }

    protected static class ReadExactHandler extends Handler.Processor
    {
        private final int expected;

        public ReadExactHandler()
        {
            this(-1);
        }

        public ReadExactHandler(int expected)
        {
            this.expected = expected;
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
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
                    try (Blocking.Runnable blocker = Blocking.runnable())
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
            response.write(true, callback, BufferUtil.toBuffer(reply, StandardCharsets.ISO_8859_1));
        }
    }

    protected static class ReadHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback)
        {
            response.setStatus(200);
            Content.readUtf8String(request, Promise.from(
                s -> response.write(true, callback, "read %d%n" + s.length()),
                t -> response.write(true, callback, String.format("caught %s%n", t))
            ));
        }
    }

    protected static class DataHandler extends Handler.Processor
    {
        public DataHandler()
        {
            super(InvocationType.BLOCKING);
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);

            String input = Content.readUtf8String(request);
            Fields fields = Request.extractQueryParameters(request);

            String tmp = fields.getValue("writes");
            int writes = Integer.parseInt(tmp == null ? "10" : tmp);
            tmp = fields.getValue("block");
            int block = Integer.parseInt(tmp == null ? "10" : tmp);
            String encoding = fields.getValue("encoding");
            String chars = fields.getValue("chars");
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
                    try (Blocking.Callback blocker = Blocking.callback())
                    {
                        response.write(i == 0, blocker, bytes.slice());
                        blocker.block();
                    }
                }
            }
            else
            {
                response.setContentType("text/plain;charset=" + encoding);
                ByteBuffer bytes = BufferUtil.toBuffer(chunk, Charset.forName(encoding));
                for (int i = writes; i-- > 0;)
                {
                    try (Blocking.Callback blocker = Blocking.callback())
                    {
                        response.write(i == 0, blocker, bytes.slice());
                        blocker.block();
                    }
                }
            }
            callback.succeeded();
        }
    }
}
