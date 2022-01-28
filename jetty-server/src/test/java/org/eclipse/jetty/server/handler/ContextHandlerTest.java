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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.MockConnectionMetaData;
import org.eclipse.jetty.server.MockConnector;
import org.eclipse.jetty.server.MockHttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContextHandlerTest
{
    Server _server;
    ClassLoader _loader;
    ContextHandler _contextHandler;
    ContextHandler.Context _context;
    AtomicBoolean _inContext;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();
        _loader = new URLClassLoader(new URL[0], this.getClass().getClassLoader());
        _contextHandler = new ContextHandler();
        _contextHandler.setDisplayName("Test Context");
        _contextHandler.setContextPath("/ctx");
        _contextHandler.setContextLoader(_loader);
        _context = _contextHandler.getContext();
        _inContext = new AtomicBoolean(true);
        _server.setHandler(_contextHandler);
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        assertTrue(_inContext.get());
        if (_server != null)
            _server.stop();
    }

    @Test
    public void testMiss() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        _contextHandler.setHandler(helloHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/other"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(404));
    }

    @Test
    public void testSimpleGET() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        _contextHandler.setHandler(helloHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));
    }

    private void assertInContext(Request request)
    {
        try
        {
            if (request != null)
            {
                assertThat(request.getPath(), equalTo("/path"));
                assertThat(request.get(ContextRequest.class, ContextRequest::getContext), sameInstance(_context));
            }
            assertThat(ContextHandler.getCurrentContext(), sameInstance(_context));
            assertThat(Thread.currentThread().getContextClassLoader(), sameInstance(_loader));
        }
        catch (Throwable t)
        {
            _inContext.set(false);
            throw t;
        }
    }

    @Test
    public void testSimpleInContext() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public void handle(Request request) throws Exception
            {
                assertInContext(request);
                Response response = request.accept();
                response.setStatus(200);
                response.getCallback().succeeded();
            }
        };
        _contextHandler.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx/path"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
    }

    @Test
    public void testCallbackInContext() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public void handle(Request request)
            {
                request.addCompletionListener(Callback.from(() -> assertInContext(request)));
                Response response = request.accept();
                Callback callback = response.getCallback();
                request.demandContent(() ->
                {
                    assertInContext(request);
                    Content content = request.readContent();
                    assertTrue(content.hasRemaining());
                    assertTrue(content.isLast());
                    response.setStatus(200);
                    response.write(true, Callback.from(
                        () ->
                        {
                            content.release();
                            assertInContext(request);
                            callback.succeeded();
                        },
                        t ->
                        {
                            throw new IllegalStateException();
                        }), content.getByteBuffer());
                });
            }
        };
        _contextHandler.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
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

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/ctx/path"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable todo = channel.onRequest(request);
        todo.run();

        todo = stream.addContent(BufferUtil.toBuffer("Hello"), true);
        todo.run();

        sendCB.getAndSet(null).succeeded();
        sendCB.getAndSet(null).succeeded();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseContentAsString(), equalTo("Hello"));
    }

    @Test
    public void testBlockingInContext() throws Exception
    {
        CountDownLatch blocking = new CountDownLatch(1);

        Handler handler = new Handler.Abstract()
        {
            @Override
            public void handle(Request request) throws Exception
            {
                request.addCompletionListener(Callback.from(() -> assertInContext(request)));

                Response response = request.accept();

                CountDownLatch latch = new CountDownLatch(1);
                request.demandContent(() ->
                {
                    assertInContext(request);
                    latch.countDown();
                });

                blocking.countDown();
                assertTrue(latch.await(10, TimeUnit.SECONDS));
                Content content = request.readContent();
                assertNotNull(content);
                assertTrue(content.hasRemaining());
                assertTrue(content.isLast());
                content.release();
                response.setStatus(200);
                response.getCallback().succeeded();
            }
        };
        _contextHandler.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel, false);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/ctx/path"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable todo = channel.onRequest(request);
        new Thread(todo).start();
        assertTrue(blocking.await(5, TimeUnit.SECONDS));

        stream.addContent(BufferUtil.toBuffer("Hello"), true).run();

        assertTrue(stream.waitForComplete(5, TimeUnit.SECONDS));
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
    }

    @Test
    public void testVirtualHost() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        _contextHandler.setHandler(helloHandler);

        _contextHandler.setVirtualHosts(Arrays.asList(
            "example.com",
            "*.wild.org",
            "acme.com@special"
        ));

        _server.start();

        AtomicReference<String> connectorName = new AtomicReference<>();

        Connector connector = new MockConnector(_server)
        {
            @Override
            public String getName()
            {
                return connectorName.get();
            }
        };

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(connector);
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        HttpFields fields = HttpFields.build().asImmutable();

        MockHttpStream stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://localhost/ctx"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(404));

        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://nope.example.com/ctx"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(404));

        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://example.com/ctx"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(200));

        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://wild.org/ctx"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(404));

        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://match.wild.org/ctx"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(200));

        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://acme.com/ctx"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(404));

        connectorName.set("special");
        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://acme.com/ctx"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(200));
    }

    @Test
    public void testThrownUsesContextErrorHandler() throws Exception
    {
        _contextHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public void handle(Request request) throws Exception
            {
                request.accept();
                throw new RuntimeException("Testing");
            }
        });
        _contextHandler.setErrorHandler(new ErrorHandler()
        {
            @Override
            protected void writeErrorHtmlBody(Request request, Writer writer, int code, String message, Throwable cause, boolean showStacks) throws IOException
            {
                ContextHandler.Context context = request.get(ContextRequest.class, ContextRequest::getContext);
                if (context != null)
                    writer.write("<h1>Context: " + context.getContextPath() + "</h1>");
                super.writeErrorHtmlBody(request, writer, code, message, cause, showStacks);
            }
        });
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, new HttpConfiguration());
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        try (StacklessLogging ignored = new StacklessLogging(ContextRequest.class))
        {
            task.run();
        }
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(500));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_HTML_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), containsString("<h1>Context: /ctx</h1>"));
        assertThat(BufferUtil.toString(stream.getResponseContent()), containsString("java.lang.RuntimeException: Testing"));
    }
}
