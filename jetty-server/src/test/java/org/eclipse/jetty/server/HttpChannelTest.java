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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.DumpHandler;
import org.eclipse.jetty.server.handler.EchoHandler;
import org.eclipse.jetty.server.handler.HelloHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpChannelTest
{
    Server _server;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        if (_server != null)
            _server.stop();
    }

    @Test
    public void testSimpleGET() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        _server.setHandler(helloHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));
    }

    @Test
    public void testAsyncGET() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        _server.setHandler(helloHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        AtomicReference<Callback> sendCB = new AtomicReference<>();
        MockHttpStream stream = new MockHttpStream(channel)
        {
            @Override
            public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
            {
                sendCB.set(callback);
                super.send(response, last, Callback.NOOP, content);
            }
        };

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);

        Runnable task = channel.onRequest(request);
        task.run();
        assertThat(stream.isComplete(), is(false));

        sendCB.getAndSet(null).succeeded(); // succeed the original send
        sendCB.getAndSet(null).succeeded(); // succeed the completion send
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));
    }

    @Test
    public void testBlockingPOST() throws Exception
    {
        DumpHandler echoHandler = new DumpHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel, false);

        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/?read=10"), HttpVersion.HTTP_1_1, fields, 0);

        Runnable todo = channel.onRequest(request);
        new Thread(todo).start(); // handling will block for content

        assertNull(stream.addContent("01234567890", true));

        stream.waitForComplete(10, TimeUnit.SECONDS);

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), containsString("text/html"));

        String content = stream.getResponseContentAsString();
        assertThat(content, containsString("<h1>Dump Handler</h1>"));
        assertThat(content, containsString("path=/"));
        assertThat(content, containsString("<pre>0123456789</pre>"));
    }

    @Test
    public void testEchoPOST() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel, false);

        String message = "ECHO Echo echo";
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        stream.addContent(body, true);
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);

        Runnable todo = channel.onRequest(request);
        todo.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));
    }

    @Test
    public void testMultiEchoPOST() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        String[] parts = new String[] {"ECHO ", "Echo ", "echo"};
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            int i = 0;
            @Override
            public Content readContent()
            {
                if (i < parts.length)
                    return Content.from(BufferUtil.toBuffer(parts[i++]), false);
                return Content.EOF;
            }
        };

        String message = String.join("", parts);
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);

        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));
    }

    @Test
    public void testAsyncEchoPOST() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        String[] parts = new String[] {"ECHO ", "Echo ", "echo"};
        AtomicReference<Callback> sendCB = new AtomicReference<>();
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            @Override
            public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
            {
                sendCB.set(callback);
                super.send(response, last, Callback.NOOP, content);
            }
        };

        String message = String.join("", parts);
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);

        Runnable task = channel.onRequest(request);
        assertThat(stream.isComplete(), is(false));
        assertThat(stream.isDemanding(), is(false));
        assertThat(sendCB.get(), nullValue());

        task.run();
        assertThat(stream.isComplete(), is(false));
        assertThat(stream.isDemanding(), is(true));
        assertThat(sendCB.get(), nullValue());

        for (int i = 0; i < parts.length; i++)
        {
            String part = parts[i];
            boolean last = i == (parts.length - 1);
            task = stream.addContent(BufferUtil.toBuffer(part), last);
            assertThat(task, notNullValue());

            assertThat(stream.isComplete(), is(false));
            assertThat(stream.isDemanding(), is(false));

            task.run();
            assertThat(stream.isComplete(), is(false));
            assertThat(stream.isDemanding(), is(false));

            Callback callback = sendCB.getAndSet(null);
            assertThat(callback, notNullValue());

            callback.succeeded();
            assertThat(stream.isComplete(), is(false));
            assertThat(stream.isDemanding(), is(!last));
        }

        Callback callback = sendCB.getAndSet(null);
        assertThat(callback, notNullValue());
        callback.succeeded();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));
    }

    @Test
    public void testNoop() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                return false;
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(404));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), nullValue());
        assertThat(stream.getResponse().getFields().getLongField(HttpHeader.CONTENT_LENGTH), equalTo(0L));
    }

    @Test
    public void testThrow() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                throw new UnsupportedOperationException("testing");
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);

        try (StacklessLogging ignored = new StacklessLogging(Server.class))
        {
            task.run();
        }

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(500));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), nullValue());
        assertThat(stream.getResponse().getFields().getLongField(HttpHeader.CONTENT_LENGTH), equalTo(0L));
    }

    @Test
    public void testThrowCommitted() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                response.setStatus(200);
                response.setContentLength(10);
                response.write(false, Callback.from(() ->
                {
                    throw new Error("testing");
                }));
                return true;
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);

        try (StacklessLogging ignored = new StacklessLogging(Server.class))
        {
            task.run();
        }
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
    }

    @Test
    public void testAutoContentLength() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                response.write(true, request, BufferUtil.toBuffer("12345"));
                return true;
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);

        task.run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().getLongField(HttpHeader.CONTENT_LENGTH), equalTo(5L));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo("12345"));
    }

    @Test
    public void testInsufficientContentWritten1() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                response.setContentLength(10);
                response.write(true, request, BufferUtil.toBuffer("12345"));
                return true;
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("content-length 10 > 5"));
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), is(500));
        assertThat(stream.getResponseContentAsString(), containsString("IOException: content-length 10 > 5"));
    }

    @Test
    public void testInsufficientContentWritten2() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                response.setContentLength(10);
                response.write(false, Callback.from(() -> response.write(true, request)), BufferUtil.toBuffer("12345"));
                return true;
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("content-length 10 > 5"));
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), is(200));
    }

    @Test
    public void testExcessContentWritten1() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                response.setContentLength(5);
                response.write(true, request, BufferUtil.toBuffer("1234567890"));
                return true;
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("content-length 5 < 10"));
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), is(500));
        assertThat(stream.getResponseContentAsString(), containsString("IOException: content-length 5 < 10"));
    }

    @Test
    public void testExcessContentWritten2() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                response.setContentLength(5);
                response.write(false, Callback.from(() -> response.write(true, request, BufferUtil.toBuffer("567890"))), BufferUtil.toBuffer("1234"));
                return true;
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("content-length 5 < 10"));
        assertThat(stream.getResponse(), notNullValue());
    }

    @Test
    public void testUnconsumedContentAvailable() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        _server.setHandler(helloHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel, false);

        String message = "ECHO Echo echo";
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        stream.addContent(body, true);

        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));

        assertThat(stream.getResponse().getFields().get(HttpHeader.CONNECTION), nullValue());
        assertThat(stream.readContent(), sameInstance(Content.EOF));
    }

    @Test
    public void testUnconsumedContentUnavailable() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                response.setStatus(200);
                response.setContentType(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                response.setContentLength(5);
                response.write(false, Callback.from(() -> response.write(true, request, BufferUtil.toBuffer("12345"))));
                return true;
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel, false);

        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, 10)
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);

        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("Content not consumed"));
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo("12345"));

        assertThat(stream.getResponse().getFields().get(HttpHeader.CONNECTION), nullValue());
        assertThat(stream.readContent(), nullValue());
    }

    @Test
    public void testUnconsumedContentUnavailableClosed() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                response.setStatus(200);
                response.addHeader(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
                response.setContentType(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                response.setContentLength(5);
                response.write(false, Callback.from(() -> response.write(true, request, BufferUtil.toBuffer("12345"))));
                return true;
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel, false);

        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, 10)
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);

        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo("12345"));

        assertThat(stream.getResponse().getFields().get(HttpHeader.CONNECTION), equalTo(HttpHeaderValue.CLOSE.asString()));
        assertThat(stream.readContent(), nullValue());
    }

    @Test
    public void testPersistent() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel, false);

        String message = "ECHO Echo echo";
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        assertThat(stream.addContent(body, true), nullValue());

        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));

        // 2nd request
        fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        stream = new MockHttpStream(channel);
        task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), nullValue());
        assertThat(stream.getResponse().getFields().getLongField(HttpHeader.CONTENT_LENGTH), equalTo(0L));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(""));
    }

    @Test
    public void testWhenStreamEvent() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        AtomicReference<Callback> sendCB = new AtomicReference<>();
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            @Override
            public Content readContent()
            {
                return super.readContent();
            }

            @Override
            public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
            {
                sendCB.set(callback);
                super.send(response, last, Callback.NOOP, content);
            }
        };

        String[] parts = new String[] {"ECHO ", "Echo ", "echo"};
        String message = String.join("", parts);
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        assertThat(stream.addContent(BufferUtil.toBuffer(parts[0]), false), nullValue());

        Runnable task = channel.onRequest(request);

        List<String> history = new ArrayList<>();
        channel.addStreamWrapper(s ->
            new HttpStream.Wrapper(s)
            {
                @Override
                public Content readContent()
                {
                    Content content = super.readContent();
                    history.add("readContent: " + content);
                    return content;
                }

                @Override
                public void demandContent()
                {
                    history.add("demandContent");
                    super.demandContent();
                }

                @Override
                public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
                {
                    history.add(String.format("send %d l=%b %d %s",
                        response == null ? 0 : response.getStatus(),
                        last,
                        content.length,
                        content.length == 0 ? null : BufferUtil.toDetailString(content[0])));
                    super.send(response, last, callback, content);
                }

                @Override
                public void push(MetaData.Request request)
                {
                    history.add("push");
                    super.push(request);
                }

                @Override
                public void upgrade(Connection connection)
                {
                    history.add("upgrade");
                    super.upgrade(connection);
                }

                @Override
                public void succeeded()
                {
                    history.add("succeeded");
                    super.succeeded();
                }

                @Override
                public void failed(Throwable x)
                {
                    history.add("failed " + x);
                    super.failed(x);
                }
            });

        task.run();
        Callback callback = sendCB.getAndSet(null);
        callback.succeeded();

        for (int i = 1; i < parts.length; i++)
        {
            String part = parts[i];
            boolean last = i == (parts.length - 1);
            task = stream.addContent(BufferUtil.toBuffer(part), last);
            task.run();
            callback = sendCB.getAndSet(null);
            callback.succeeded();
        }

        callback = sendCB.getAndSet(null);
        callback.succeeded();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));

        Iterator<String> timeline = history.iterator();

        // Read of the first part that has already arrived
        assertThat(timeline.next(), allOf(startsWith("readContent: "), containsString("<<<ECHO >>>")));
        // send the first part with a commit
        assertThat(timeline.next(), allOf(startsWith("send 200 l=false"), containsString("<<<ECHO >>>")));
        // no more content available
        assertThat(timeline.next(), allOf(startsWith("readContent: null")));
        // demand content
        assertThat(timeline.next(), allOf(startsWith("demandContent")));
        // read the next part when it arrives
        assertThat(timeline.next(), allOf(startsWith("readContent: "), containsString("<<<Echo >>>")));
        // send the next part not commit and not last
        assertThat(timeline.next(), allOf(startsWith("send 0 l=false "), containsString("<<<Echo >>>")));
        // no more content available
        assertThat(timeline.next(), allOf(startsWith("readContent: null")));
        // demand content
        assertThat(timeline.next(), allOf(startsWith("demandContent")));
        // read the last part when it arrives
        assertThat(timeline.next(), allOf(startsWith("readContent: "), containsString("<<<echo>>>"), containsString("l=true")));
        // send the last part
        assertThat(timeline.next(), allOf(startsWith("send 0 l=true "), containsString("<<<echo>>>")));
        // ensure all data is consumed
        assertThat(timeline.next(), allOf(startsWith("readContent: EOF")));
        // ensure all data is flushed
        assertThat(timeline.next(), allOf(startsWith("send 0 l=true 0 null")));
        // succeed the stream
        assertThat(timeline.next(), allOf(startsWith("succeeded")));
        // End of history
        assertThat(timeline.hasNext(), is(false));
    }

    @Test
    public void testTrailers() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        String[] parts = new String[] {"ECHO ", "Echo ", "echo"};
        HttpFields trailers = HttpFields.build().add("Some", "value").asImmutable();
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            int i = 0;
            @Override
            public Content readContent()
            {
                if (i < parts.length)
                    return Content.from(BufferUtil.toBuffer(parts[i++]), false);

                if (i++ == parts.length)
                    return new Content.Trailers(trailers);

                return Content.EOF;
            }
        };

        String message = String.join("", parts);
        ByteBuffer body = BufferUtil.toBuffer(message);
        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
            .put(HttpHeader.TRAILER, "Some")
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);

        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));

        HttpFields trailersRcv = stream.getResponse().getTrailerSupplier().get();
        assertThat(trailersRcv, notNullValue());
        assertThat(trailersRcv.get("Echo"), equalTo("Trailers"));
        assertThat(trailersRcv.get("Some"), equalTo("value"));
    }

    @Test
    public void testDemandRecursion() throws Exception
    {
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                LongAdder contentSize = new LongAdder();
                CountDownLatch latch = new CountDownLatch(1);
                Runnable onContentAvailable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        Content content = request.readContent();
                        contentSize.add(content.remaining());
                        content.release();
                        if (content.isLast())
                            latch.countDown();
                        else
                            request.demandContent(this);
                    }
                };
                request.demandContent(onContentAvailable);
                if (latch.await(30, TimeUnit.SECONDS))
                {
                    response.setStatus(200);
                    response.write(true, request, BufferUtil.toBuffer("contentSize=" + contentSize.longValue()));
                }
                else
                {
                    request.failed(new IOException());
                }

                return true;
            }
        });
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        ByteBuffer data = BufferUtil.toBuffer("data");
        final int chunks = 100000;
        AtomicInteger count = new AtomicInteger(chunks);
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            @Override
            public Content readContent()
            {
                int c = count.decrementAndGet();
                if (c >= 0)
                    return Content.from(data.slice(), false);
                return Content.EOF;
            }

            @Override
            public void demandContent()
            {
                Runnable task = channel.onContentAvailable();
                if (task != null)
                    task.run();
            }
        };

        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, -1);

        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo("contentSize=" + (chunks * data.remaining())));
    }

    @Test
    public void testOnError() throws Exception
    {
        AtomicReference<Request> handling = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                handling.set(request);
                request.addErrorListener(t -> {});
                request.addErrorListener(error::set);
                request.addErrorListener(t -> {});
                return true;
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(false));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), nullValue());

        // failure happens
        IOException failure = new IOException("Testing");
        Runnable todo = channel.onError(failure);
        assertThat(todo, notNullValue());

        // onError not yet called
        assertThat(error.get(), nullValue());

        // request still handling
        assertFalse(handling.get().isComplete());
        assertFalse(stream.isComplete());

        // but now we cannot read, demand nor write
        Content read = handling.get().readContent();
        assertTrue(read.isSpecial());
        assertTrue(read.isLast());
        assertInstanceOf(Content.Error.class, read);
        assertThat(((Content.Error)read).getCause(), sameInstance(failure));

        CountDownLatch demand = new CountDownLatch(1);
        // Callback serialized until after onError task
        handling.get().demandContent(demand::countDown);

        FuturePromise<Throwable> callback = new FuturePromise<>();
        // Callback serialized until after onError task
        handling.get().getChannel().getResponse().write(false, Callback.from(() -> {}, callback::succeeded));

        // process error callback
        todo.run();

        // onError was called
        assertThat(error.get(), sameInstance(failure));
        // demand callback was called
        assertTrue(demand.await(5, TimeUnit.SECONDS));
        // write callback was failed
        assertThat(callback.get(5, TimeUnit.SECONDS), sameInstance(failure));

        // request completed handling
        assertTrue(handling.get().isComplete());
        assertTrue(stream.isComplete());
    }

    @Test
    public void testOnCommitAndComplete() throws Exception
    {
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        EchoHandler echoHandler = new EchoHandler()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                request.addCompletionListener(Callback.from(completed::countDown));
                return super.handle(request, response);
            }
        };
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            @Override
            public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
            {
                committing.countDown();
                super.send(response, last, callback, content);
            }
        };

        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, -1);

        Runnable todo = channel.onRequest(request);
        todo.run();
        assertFalse(stream.isComplete());
        assertTrue(stream.isDemanding());
        assertThat(committing.getCount(), is(1L));
        assertThat(completed.getCount(), is(1L));

        stream.addContent(BufferUtil.toBuffer("hello "), false).run();

        assertFalse(stream.isComplete());
        assertTrue(stream.isDemanding());
        assertTrue(committing.await(5, TimeUnit.SECONDS));
        assertThat(completed.getCount(), is(1L));
        stream.addContent(BufferUtil.toBuffer("world!"), true).run();

        assertTrue(committing.await(5, TimeUnit.SECONDS));
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertTrue(stream.isComplete());
        assertFalse(stream.isDemanding());
    }
}
