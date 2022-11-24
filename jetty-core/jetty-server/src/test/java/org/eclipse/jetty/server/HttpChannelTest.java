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
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.DumpHandler;
import org.eclipse.jetty.server.handler.EchoHandler;
import org.eclipse.jetty.server.handler.HelloHandler;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.thread.SerializedInvoker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));
    }

    @Test
    public void testAsyncGET() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        _server.setHandler(helloHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        AtomicReference<Callback> sendCB = new AtomicReference<>();
        MockHttpStream stream = new MockHttpStream(channel)
        {
            @Override
            public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
            {
                sendCB.set(callback);
                super.send(request, response, last, content, NOOP);
            }
        };

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);

        Runnable task = channel.onRequest(request);
        task.run();
        assertThat(stream.isComplete(), is(false));

        sendCB.getAndSet(null).succeeded();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));
    }

    @Test
    public void testRecursiveGET() throws Exception
    {
        _server.setHandler(new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                AtomicInteger count = new AtomicInteger(10000);
                Callback writer = new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        if (count.decrementAndGet() == 0)
                            Content.Sink.write(response, true, "X", callback);
                        else
                            Content.Sink.write(response, false, "X", this);
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        callback.failed(x);
                    }
                };
                writer.succeeded();
            }
        });
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        CountDownLatch complete = new CountDownLatch(1);
        MockHttpStream stream = new MockHttpStream(channel)
        {
            @Override
            public void succeeded()
            {
                super.succeeded();
                complete.countDown();
            }

            @Override
            public void failed(Throwable x)
            {
                super.failed(x);
                complete.countDown();
            }
        };

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);

        Runnable task = channel.onRequest(request);
        task.run();

        assertTrue(complete.await(5, TimeUnit.SECONDS));
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(stream.getResponseContent().remaining(), equalTo(10000));
    }

    @Test
    public void testBlockingPOST() throws Exception
    {
        DumpHandler echoHandler = new DumpHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
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
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), containsString("text/html"));

        String content = stream.getResponseContentAsString();
        assertThat(content, containsString("<h1>Dump Handler</h1>"));
        assertThat(content, containsString("pathInContext=/"));
        assertThat(content, containsString("<pre>0123456789</pre>"));
    }

    @Test
    public void testEchoPOST() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
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
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));
    }

    @Test
    public void testMultiEchoPOST() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        String[] parts = new String[]{"ECHO ", "Echo ", "echo"};
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            int i = 0;

            @Override
            public Content.Chunk read()
            {
                if (i < parts.length)
                    return Content.Chunk.from(BufferUtil.toBuffer(parts[i++]), false);
                return Content.Chunk.EOF;
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
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));
    }

    @Test
    public void testAsyncEchoPOST() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        String[] parts = new String[]{"ECHO ", "Echo ", "echo"};
        AtomicReference<Callback> sendCB = new AtomicReference<>();
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            @Override
            public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
            {
                sendCB.set(callback);
                super.send(request, response, last, content, NOOP);
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
            assertThat(stream.isComplete(), is(last));
            assertThat(stream.isDemanding(), is(!last));
        }

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));
    }

    @Test
    public void testNoop() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                return null;
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(404));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), notNullValue());
        assertThat(stream.getResponseHeaders().getLongField(HttpHeader.CONTENT_LENGTH), greaterThan(0L));
    }

    @Test
    public void testThrow() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                throw new UnsupportedOperationException("testing");
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);

        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            task.run();
        }

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(500));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), containsString("text/html"));
    }

    @Test
    public void testCompleteThenThrow() throws Exception
    {
        Handler handler = new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                Content.Sink.write(response, true, "Before throw", Callback.from(callback, () ->
                {
                    throw new Error("testing");
                }));
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);

        try (StacklessLogging ignored = new StacklessLogging(SerializedInvoker.class))
        {
            task.run();
        }
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseContentAsString(), equalTo("Before throw"));
    }

    @Test
    public void testCommitThenThrowFromCallback() throws Exception
    {
        Handler handler = new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 10);
                response.write(false, null, Callback.from(() ->
                {
                    throw new Error("testing");
                }));
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);

        try (StacklessLogging ignored = new StacklessLogging(SerializedInvoker.class))
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
        Handler handler = new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.write(true, BufferUtil.toBuffer("12345"), callback);
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);

        task.run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseHeaders().getLongField(HttpHeader.CONTENT_LENGTH), equalTo(5L));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo("12345"));
    }

    @Test
    public void testInsufficientContentWritten1() throws Exception
    {
        Handler handler = new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 10);
                response.write(true, BufferUtil.toBuffer("12345"), callback);
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable onRequest = channel.onRequest(request);
        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            onRequest.run();
        }

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("5 < 10"));
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), is(500));
        assertThat(stream.getResponseContentAsString(), containsString("5 &lt; 10"));
    }

    @Test
    public void testInsufficientContentWritten2() throws Exception
    {
        Handler handler = new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 10);
                response.write(false,
                    BufferUtil.toBuffer("12345"), Callback.from(() ->
                        response.write(true, null, callback)));
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("5 < 10"));
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), is(200));
    }

    @Test
    public void testExcessContentWritten1() throws Exception
    {
        Handler handler = new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 5);
                response.write(true, BufferUtil.toBuffer("1234567890"), callback);
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            task.run();
        }

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("10 > 5"));
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), is(500));
        assertThat(stream.getResponseContentAsString(), containsString("10 &gt; 5"));
    }

    @Test
    public void testExcessContentWritten2() throws Exception
    {
        Handler handler = new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 5);
                response.write(false, BufferUtil.toBuffer("1234"), Callback.from(() -> response.write(true, BufferUtil.toBuffer("567890"), callback)));
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), notNullValue());
        assertThat(stream.getFailure().getMessage(), containsString("10 > 5"));
        assertThat(stream.getResponse(), notNullValue());
    }

    @Test
    public void testUnconsumedContentAvailable() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        _server.setHandler(helloHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
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
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));

        assertThat(stream.getResponseHeaders().get(HttpHeader.CONNECTION), nullValue());
        assertThat(stream.read(), sameInstance(Content.Chunk.EOF));
    }

    @Test
    public void testUnconsumedContentUnavailable() throws Exception
    {
        Handler handler = new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 5);
                response.write(false, null, Callback.from(() -> response.write(true, BufferUtil.toBuffer("12345"), callback)));
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
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
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo("12345"));

        assertThat(stream.getResponseHeaders().get(HttpHeader.CONNECTION), nullValue());
        assertThat(stream.read(), nullValue());
    }

    @Test
    public void testUnconsumedContentUnavailableClosed() throws Exception
    {
        Handler handler = new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().add(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 5);
                response.write(false, null, Callback.from(() -> response.write(true, BufferUtil.toBuffer("12345"), callback)));
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
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
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo("12345"));

        assertThat(stream.getResponseHeaders().get(HttpHeader.CONNECTION), equalTo(HttpHeaderValue.CLOSE.asString()));
        assertThat(stream.read(), nullValue());
    }

    @Test
    public void testPersistent() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
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
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
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
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), nullValue());
        assertThat(stream.getResponseHeaders().getLongField(HttpHeader.CONTENT_LENGTH), equalTo(0L));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(""));
    }

    @Test
    public void testStreamWrapper() throws Exception
    {
        EchoHandler echoHandler = new EchoHandler();
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        AtomicReference<Callback> sendCB = new AtomicReference<>();
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            @Override
            public Content.Chunk read()
            {
                return super.read();
            }

            @Override
            public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
            {
                sendCB.set(callback);
                super.send(request, response, last, content, NOOP);
            }
        };

        String[] parts = new String[]{"ECHO ", "Echo ", "echo"};
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
        channel.getRequest().addHttpStreamWrapper(s ->
            new HttpStream.Wrapper(s)
            {
                @Override
                public Content.Chunk read()
                {
                    Content.Chunk chunk = super.read();
                    history.add("readContent: " + chunk);
                    return chunk;
                }

                @Override
                public void demand()
                {
                    history.add("demandContent");
                    super.demand();
                }

                @Override
                public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
                {
                    history.add(String.format("send %d l=%b %d %s",
                        response == null ? 0 : response.getStatus(),
                        last,
                        BufferUtil.length(content),
                        BufferUtil.toDetailString(content)));
                    super.send(request, response, last, content, callback);
                }

                @Override
                public void push(MetaData.Request resource)
                {
                    history.add("push");
                    super.push(resource);
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

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
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

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        String[] parts = new String[]{"ECHO ", "Echo ", "echo"};
        HttpFields trailers = HttpFields.build().add("Some", "value").asImmutable();
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            int i = 0;

            @Override
            public Content.Chunk read()
            {
                if (i < parts.length)
                    return Content.Chunk.from(BufferUtil.toBuffer(parts[i++]), false);

                if (i++ == parts.length)
                    return new Trailers(trailers);

                return Content.Chunk.EOF;
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

        Runnable onRequest = channel.onRequest(request);
        onRequest.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));

        HttpFields trailersRcv = stream.getResponseTrailers();
        assertThat(trailersRcv, notNullValue());
        assertThat(trailersRcv.get("Some"), equalTo("value"));
    }

    @Test
    public void testDemandRecursion() throws Exception
    {
        _server.setHandler(new Handler.Processor.Blocking()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback) throws Exception
            {
                LongAdder contentSize = new LongAdder();
                CountDownLatch latch = new CountDownLatch(1);
                Runnable onContentAvailable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        Content.Chunk chunk = request.read();
                        contentSize.add(chunk.remaining());
                        chunk.release();
                        if (chunk.isLast())
                            latch.countDown();
                        else
                            request.demand(this);
                    }
                };
                request.demand(onContentAvailable);
                if (latch.await(30, TimeUnit.SECONDS))
                {
                    response.setStatus(200);
                    response.write(true, BufferUtil.toBuffer("contentSize=" + contentSize.longValue()), callback);
                }
                else
                {
                    callback.failed(new IOException());
                }
            }
        });
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        ByteBuffer data = BufferUtil.toBuffer("data");
        final int chunks = 100000;
        AtomicInteger count = new AtomicInteger(chunks);
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            @Override
            public Content.Chunk read()
            {
                int c = count.decrementAndGet();
                if (c >= 0)
                    return Content.Chunk.from(data.slice(), false);
                return Content.Chunk.EOF;
            }

            @Override
            public void demand()
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
        AtomicReference<Response> handling = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Handler handler = new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                handling.set(response);
                request.addErrorListener(t -> false);
                request.addErrorListener(t -> !error.compareAndSet(null, t));
                request.addErrorListener(t ->
                {
                    callback.failed(t);
                    return true;
                });
            }
        };
        _server.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable onRequest = channel.onRequest(request);
        onRequest.run();

        // check we are handling
        assertNotNull(handling.get());
        assertThat(stream.isComplete(), is(false));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), nullValue());

        // failure happens
        IOException failure = new IOException("Testing");
        Runnable onError = channel.onFailure(failure);
        assertNotNull(onError);

        // onError not yet called
        assertThat(error.get(), nullValue());

        // request still handling
        assertFalse(stream.isComplete());

        // but now we cannot read, demand nor write
        Request rq = handling.get().getRequest();
        Content.Chunk chunk = rq.read();
        assertTrue(chunk.isLast());
        assertInstanceOf(Content.Chunk.Error.class, chunk);
        assertThat(((Content.Chunk.Error)chunk).getCause(), sameInstance(failure));

        CountDownLatch demand = new CountDownLatch(1);
        // Callback serialized until after onError task
        rq.demand(demand::countDown);
        assertThat(demand.getCount(), is(1L));

        FuturePromise<Throwable> callback = new FuturePromise<>();
        // Callback serialized until after onError task
        handling.get().write(false, null, Callback.from(() ->
        {}, callback::succeeded));
        assertFalse(callback.isDone());

        // process error callback
        try (StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            onError.run();
        }

        // onError was called
        assertThat(error.get(), sameInstance(failure));
        // demand callback was called
        assertTrue(demand.await(5, TimeUnit.SECONDS));
        // write callback was failed
        assertThat(callback.get(5, TimeUnit.SECONDS), sameInstance(failure));

        // request completed handling
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
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                request.addHttpStreamWrapper(s -> new HttpStream.Wrapper(s)
                {
                    @Override
                    public void succeeded()
                    {
                        completed.countDown();
                        super.succeeded();
                    }
                });
                return super.process(request, response, callback);
            }
        };
        _server.setHandler(echoHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            @Override
            public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
            {
                committing.countDown();
                super.send(request, response, last, content, callback);
            }
        };

        HttpFields fields = HttpFields.build()
            .add(HttpHeader.HOST, "localhost")
            .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
            .add(HttpHeader.CONTENT_LENGTH, "12")
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

    enum CompletionTestEvent
    {
        PROCESSED,
        WRITE,
        SUCCEED,
        FAIL,
        STREAM_COMPLETE
    }

    public static Stream<List<CompletionTestEvent>> completionEvents()
    {
        return Stream.of(
            List.of(CompletionTestEvent.WRITE, CompletionTestEvent.STREAM_COMPLETE, CompletionTestEvent.SUCCEED, CompletionTestEvent.PROCESSED),
            List.of(CompletionTestEvent.WRITE, CompletionTestEvent.STREAM_COMPLETE, CompletionTestEvent.PROCESSED, CompletionTestEvent.SUCCEED),
            List.of(CompletionTestEvent.WRITE, CompletionTestEvent.PROCESSED, CompletionTestEvent.STREAM_COMPLETE, CompletionTestEvent.SUCCEED),
            List.of(CompletionTestEvent.PROCESSED, CompletionTestEvent.WRITE, CompletionTestEvent.STREAM_COMPLETE, CompletionTestEvent.SUCCEED),

            List.of(CompletionTestEvent.SUCCEED, CompletionTestEvent.STREAM_COMPLETE, CompletionTestEvent.PROCESSED),
            List.of(CompletionTestEvent.SUCCEED, CompletionTestEvent.PROCESSED, CompletionTestEvent.STREAM_COMPLETE),
            List.of(CompletionTestEvent.PROCESSED, CompletionTestEvent.SUCCEED, CompletionTestEvent.STREAM_COMPLETE),

            List.of(CompletionTestEvent.FAIL, CompletionTestEvent.STREAM_COMPLETE, CompletionTestEvent.PROCESSED),
            List.of(CompletionTestEvent.FAIL, CompletionTestEvent.PROCESSED, CompletionTestEvent.STREAM_COMPLETE),
            List.of(CompletionTestEvent.PROCESSED, CompletionTestEvent.FAIL, CompletionTestEvent.STREAM_COMPLETE)
        );
    }

    @ParameterizedTest
    @MethodSource("completionEvents")
    public void testCompletion(List<CompletionTestEvent> events) throws Exception
    {
        testCompletion(events, null, true);
    }

    @ParameterizedTest
    @MethodSource("completionEvents")
    public void testCompletionNoWriteErrorProcessor(List<CompletionTestEvent> events) throws Exception
    {
        Request.Processor errorProcessor = (request, response, callback) -> callback.succeeded();
        testCompletion(events, errorProcessor, true);
    }

    @ParameterizedTest
    @MethodSource("completionEvents")
    public void testCompletionFailedErrorProcessor(List<CompletionTestEvent> events) throws Exception
    {
        Request.Processor errorProcessor = (request, response, callback) -> callback.failed(new QuietException.Exception("Error processor failed"));
        testCompletion(events, errorProcessor, false);
    }

    private void testCompletion(List<CompletionTestEvent> events, Request.Processor errorProcessor, boolean expectErrorResponse) throws Exception
    {
        CountDownLatch processing = new CountDownLatch(1);
        CountDownLatch processed = new CountDownLatch(1);
        AtomicReference<Response> responseRef = new AtomicReference<>();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();

        Handler handler = new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                response.getHeaders().put("Test", "Value");
                responseRef.set(response);
                callbackRef.set(callback);
                processing.countDown();
                processed.await();
            }
        };

        _server.setHandler(handler);
        if (errorProcessor != null)
            _server.setErrorProcessor(errorProcessor);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);

        AtomicReference<Callback> sendCallback = new AtomicReference<>();
        MockHttpStream stream = new MockHttpStream(channel)
        {
            @Override
            public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
            {
                sendCallback.set(callback);
                super.send(request, response, last, content, NOOP);
            }
        };

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);

        // Process the request
        Thread processor = new Thread(task);
        processor.start();
        assertTrue(processing.await(5, TimeUnit.SECONDS));

        Response response = responseRef.get();
        Callback callback = callbackRef.get();
        FutureCallback written = null;

        for (CompletionTestEvent event : events)
        {
            switch (event)
            {
                case WRITE ->
                {
                    if (written != null)
                        throw new IllegalStateException();
                    written = new FutureCallback();
                    response.write(true, null, written);
                }

                case SUCCEED -> callback.succeeded();

                case FAIL -> callback.failed(new QuietException.Exception("FAILED"));

                case PROCESSED ->
                {
                    processed.countDown();
                    processor.join(10000);
                    assertFalse(processor.isAlive());
                }

                case STREAM_COMPLETE ->
                {
                    if (sendCallback.get() != null)
                        sendCallback.get().succeeded();
                    if (written != null)
                    {
                        written.get(5, TimeUnit.SECONDS);
                        assertTrue(written.isDone());
                    }
                }
            }
        }

        boolean failed = events.contains(CompletionTestEvent.FAIL);

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), failed ? notNullValue() : nullValue());
        if (!failed || expectErrorResponse)
        {
            assertThat(stream.getResponse(), notNullValue());
            assertThat(stream.getResponse().getStatus(), equalTo(failed ? 500 : 200));
            assertThat(stream.getResponseHeaders().get("Test"), failed ? nullValue() : equalTo("Value"));
        }
        else
        {
            assertThat(stream.getResponse(), nullValue());
        }
    }
}
