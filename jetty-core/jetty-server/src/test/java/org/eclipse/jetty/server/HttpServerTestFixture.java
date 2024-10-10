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

package org.eclipse.jetty.server;

import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class HttpServerTestFixture
{
    protected static final long PAUSE = 10L;
    protected static final int LOOPS = 50;

    protected QueuedThreadPool _threadPool;
    protected Server _server;
    protected ArrayByteBufferPool.Tracking _bufferPool;
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
        _bufferPool = new ArrayByteBufferPool.Tracking();
        _server = new Server(_threadPool, new ScheduledExecutorScheduler(), _bufferPool);
    }

    protected void initServer(ServerConnector connector) throws Exception
    {
        _connector = connector;
        _httpConfiguration = _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();
        _httpConfiguration.setSendDateHeader(false);
        _httpConfiguration.setSendServerVersion(false);
        _server.addConnector(_connector);
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        try
        {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Server leaks: " + _bufferPool.dumpLeaks(), _bufferPool.getLeaks().size(), is(0)));
        }
        finally
        {
            _server.stop();
        }
    }

    protected void startServer(Handler handler) throws Exception
    {
        _server.setHandler(handler);
        _server.start();
        _serverURI = _server.getURI();
    }

    protected static class OptionsHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback)
        {
            if (request.getMethod().equals("OPTIONS"))
                response.setStatus(200);
            else
                response.setStatus(500);
            response.getHeaders().put("Allow", "GET");
            callback.succeeded();
            return true;
        }
    }

    protected static class HelloWorldHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);
            Content.Sink.write(response, true, "Hello world\r\n", callback);
            return true;
        }
    }

    protected static class SendErrorHandler extends Handler.Abstract
    {
        private final int code;
        private final String message;

        public SendErrorHandler(int code, String message)
        {
            this.code = code;
            this.message = message;
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback)
        {
            Response.writeError(request, response, callback, code, message);
            return true;
        }
    }

    protected static class ReadExactHandler extends Handler.Abstract
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
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            long len = expected < 0 ? request.getLength() : expected;
            if (len < 0)
                throw new IllegalStateException();
            byte[] content = new byte[(int)len];
            int offset = 0;
            while (offset < len)
            {
                Content.Chunk c = request.read();
                if (c == null)
                {
                    try (Blocker.Runnable blocker = Blocker.runnable())
                    {
                        request.demand(blocker);
                        blocker.block();
                    }
                    continue;
                }

                if (c.hasRemaining())
                {
                    int r = c.remaining();
                    c.get(content, offset, r);
                    offset += r;
                }

                c.release();

                if (c.isLast())
                    break;
            }
            response.setStatus(200);
            String reply = "Read " + offset + "\r\n";
            response.getHeaders().put(HttpHeader.CONTENT_LENGTH, reply.length());
            response.write(true, BufferUtil.toBuffer(reply, StandardCharsets.ISO_8859_1), callback);
            return true;
        }
    }

    protected static class ReadHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback)
        {
            response.setStatus(200);
            Content.Source.asString(request, StandardCharsets.UTF_8, Promise.from(
                s -> Content.Sink.write(response, true, "read %d%n" + s.length(), callback),
                callback::failed
            ));
            return true;
        }
    }

    protected static class DataHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);

            String input = Content.Source.asString(request);
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
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                ByteBuffer bytes = BufferUtil.toBuffer(chunk, StandardCharsets.ISO_8859_1);
                for (int i = writes; i-- > 0;)
                {
                    try (Blocker.Callback blocker = Blocker.callback())
                    {
                        response.write(i == 0, bytes.slice(), blocker);
                        blocker.block();
                    }
                }
            }
            else
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=" + encoding);
                ByteBuffer bytes = BufferUtil.toBuffer(chunk, Charset.forName(encoding));
                for (int i = writes; i-- > 0;)
                {
                    try (Blocker.Callback blocker = Blocker.callback())
                    {
                        response.write(i == 0, bytes.slice(), blocker);
                        blocker.block();
                    }
                }
            }
            callback.succeeded();
            return true;
        }
    }
}
