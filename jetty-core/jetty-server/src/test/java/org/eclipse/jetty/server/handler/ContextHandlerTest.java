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
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.MockConnectionMetaData;
import org.eclipse.jetty.server.MockConnector;
import org.eclipse.jetty.server.MockHttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContextHandlerTest
{
    Server _server;
    ClassLoader _loader;
    ContextHandler _contextHandler;
    Context _context;
    AtomicBoolean _inContext;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();
        _loader = new URLClassLoader(new URL[0], this.getClass().getClassLoader());
        _contextHandler = new ContextHandler();
        _contextHandler.setDisplayName("Test Context");
        _contextHandler.setContextPath("/ctx");
        _contextHandler.setClassLoader(_loader);
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
        HttpChannel channel = new HttpChannelState(connectionMetaData);
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

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        // The original fields have been recycled.
        assertThat(stream.getResponse().getFields().size(), equalTo(0));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));
    }

    private void assertInContext(Request request)
    {
        try
        {
            if (request != null)
            {
                assertThat(request.getPathInContext(), equalTo("/path"));
                assertThat(request.getContext(), sameInstance(_context));
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
        ScopeListener scopeListener = new ScopeListener();
        _contextHandler.addEventListener(scopeListener);

        Handler handler = new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                assertInContext(request);
                scopeListener.assertInContext(request.getContext(), request);
                response.setStatus(200);
                callback.succeeded();
            }
        };
        _contextHandler.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
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
        ScopeListener scopeListener = new ScopeListener();
        _contextHandler.addEventListener(scopeListener);

        Handler handler = new Handler.Processor()
        {
            @Override
            public Request.Processor handle(Request request) throws Exception
            {
                assertInContext(request);
                scopeListener.assertInContext(request.getContext(), request);
                return super.handle(request);
            }

            @Override
            public void process(Request request, Response response, Callback callback)
            {
                request.addHttpStreamWrapper(s -> new HttpStream.Wrapper(s)
                {
                    @Override
                    public void succeeded()
                    {
                        assertInContext(request);
                        scopeListener.assertInContext(request.getContext(), request);
                        super.succeeded();
                    }
                });
                request.demandContent(() ->
                {
                    assertInContext(request);
                    scopeListener.assertInContext(request.getContext(), request);
                    Content content = request.readContent();
                    assertTrue(content.hasRemaining());
                    assertTrue(content.isLast());
                    response.setStatus(200);
                    response.write(true, Callback.from(
                        () ->
                        {
                            content.release();
                            assertInContext(request);
                            scopeListener.assertInContext(request.getContext(), request);
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

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        AtomicReference<Callback> sendCB = new AtomicReference<>();
        MockHttpStream stream = new MockHttpStream(channel, false)
        {
            @Override
            public void send(MetaData.Request request, MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
            {
                sendCB.set(callback);
                super.send(request, response, last, Callback.NOOP, content);
            }
        };

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("http://localhost/ctx/path"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable todo = channel.onRequest(request);
        todo.run();

        todo = stream.addContent(BufferUtil.toBuffer("Hello"), true);
        todo.run();

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
        ScopeListener scopeListener = new ScopeListener();
        _contextHandler.addEventListener(scopeListener);

        Handler handler = new Handler.Processor(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                CountDownLatch latch = new CountDownLatch(1);
                request.demandContent(() ->
                {
                    assertInContext(request);
                    scopeListener.assertInContext(request.getContext(), request);
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
                callback.succeeded();
            }
        };
        _contextHandler.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
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
    public void testFunctionalInContext() throws Exception
    {
        CountDownLatch complete = new CountDownLatch(1);
        ScopeListener scopeListener = new ScopeListener();
        _contextHandler.addEventListener(scopeListener);

        Handler handler = new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                assertInContext(request);
                scopeListener.assertInContext(request.getContext(), request);
                response.setStatus(200);

                Context context = request.getContext();
                _server.getThreadPool().execute(() ->
                {
                    context.run(() -> scopeListener.assertInContext(request.getContext(), null));
                    context.execute(() ->
                    {
                        scopeListener.assertInContext(request.getContext(), null);
                        callback.succeeded();
                        complete.countDown();
                    });
                });
            }
        };
        _contextHandler.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx/path"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();
        assertTrue(complete.await(10, TimeUnit.SECONDS));
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
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        HttpFields fields = HttpFields.build().asImmutable();

        MockHttpStream stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://localhost/ctx/"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(404));

        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://nope.example.com/ctx/"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(404));

        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://example.com/ctx/"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(200));

        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://wild.org/ctx/"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(404));

        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://match.wild.org/ctx/"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(200));

        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://acme.com/ctx/"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(404));

        connectorName.set("special");
        stream = new MockHttpStream(channel);
        channel.onRequest(new MetaData.Request("GET", HttpURI.from("http://acme.com/ctx/"), HttpVersion.HTTP_1_1, fields, 0)).run();
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getResponse().getStatus(), equalTo(200));
    }

    @Test
    public void testThrownUsesContextErrorProcessor() throws Exception
    {
        _contextHandler.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                throw new RuntimeException("Testing");
            }
        });
        _contextHandler.setErrorProcessor(new ErrorProcessor()
        {
            @Override
            protected void writeErrorHtmlBody(Request request, Writer writer, int code, String message, Throwable cause, boolean showStacks) throws IOException
            {
                Context context = request.getContext();
                if (context != null)
                    writer.write("<h1>Context: " + context.getContextPath() + "</h1>");
                super.writeErrorHtmlBody(request, writer, code, message, cause, showStacks);
            }
        });
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx/"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            task.run();
        }
        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(500));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_HTML_8859_1.asString()));
        assertThat(stream.getResponse().getFields().size(), equalTo(0));
        assertThat(BufferUtil.toString(stream.getResponseContent()), containsString("<h1>Context: /ctx</h1>"));
        assertThat(BufferUtil.toString(stream.getResponseContent()), containsString("java.lang.RuntimeException: Testing"));
    }

    @Test
    public void testExitScopeAfterCompletion() throws Exception
    {
        AtomicReference<String> result = new AtomicReference<>();
        _contextHandler.addEventListener(new ContextHandler.ContextScopeListener()
        {
            @Override
            public void enterScope(Context context, Request request)
            {
                result.set(null);
                if (request != null)
                    request.setAttribute("test", "entered");
            }

            @Override
            public void exitScope(Context context, Request request)
            {
                if (request != null && "entered".equals(request.getAttribute("test")))
                {
                    request.setAttribute("test", "exited");
                    result.set("OK");
                }
            }
        });

        Handler handler = new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.write(true, callback);
            }
        };
        _contextHandler.setHandler(handler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);

        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx/path"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));

        assertThat(result.get(), equalTo("OK"));
    }

    private static class ScopeListener implements ContextHandler.ContextScopeListener
    {
        private static final Request NULL = new Request.Wrapper(null);
        private final ThreadLocal<Context> _context = new ThreadLocal<>();
        private final ThreadLocal<Request> _request = new ThreadLocal<>();

        @Override
        public void enterScope(Context context, Request request)
        {
            _context.set(context);
            _request.set(request == null ? NULL : request);
        }

        @Override
        public void exitScope(Context context, Request request)
        {
            _context.set(null);
            _request.set(null);
        }

        void assertInContext(Context context, Request request)
        {
            assertThat(_context.get(), sameInstance(context));
            Request r = _request.get();
            if (r == NULL)
                assertNull(request);
            else
                assertThat(r, sameInstance(request));
        }
    }
}
