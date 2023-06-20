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

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.MockConnectionMetaData;
import org.eclipse.jetty.server.MockConnector;
import org.eclipse.jetty.server.MockHttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.Graceful;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContextHandlerTest
{
    public static final File TEST_BAD = MavenTestingUtils.getTargetTestingPath("testBad").toFile();
    public static final File TEST_OK = MavenTestingUtils.getTargetTestingPath("testOK").toFile();
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

    @AfterAll
    public static void afterAll()
    {
        ensureWritable(TEST_OK);
        FS.ensureDeleted(TEST_OK.toPath());
        ensureWritable(TEST_BAD);
        FS.ensureDeleted(TEST_BAD.toPath());
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
        assertThat(stream.getResponse().getHttpFields().size(), equalTo(0));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));
    }

    @Test
    public void testNullPath() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        _contextHandler.setHandler(helloHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);
        MockHttpStream stream = new MockHttpStream(channel);
        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx"), HttpVersion.HTTP_1_1, fields, 0);
        Runnable task = channel.onRequest(request);
        task.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(301));
        assertThat(stream.getResponseHeaders().get(HttpHeader.LOCATION), equalTo("/ctx/"));

        _contextHandler.stop();
        _contextHandler.setAllowNullPathInContext(true);
        _contextHandler.start();

        stream = new MockHttpStream(channel);
        fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx"), HttpVersion.HTTP_1_1, fields, 0);
        task = channel.onRequest(request);
        task.run();

        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        // The original fields have been recycled.
        assertThat(stream.getResponse().getHttpFields().size(), equalTo(0));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));
    }

    @Test
    public void testSetAvailable() throws Exception
    {
        HelloHandler helloHandler = new HelloHandler();
        _contextHandler.setHandler(helloHandler);
        _server.start();

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(new MockConnector(_server));
        HttpChannel channel = new HttpChannelState(connectionMetaData);

        MockHttpStream stream = new MockHttpStream(channel);
        HttpFields fields = HttpFields.build().add(HttpHeader.HOST, "localhost").asImmutable();
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx/"), HttpVersion.HTTP_1_1, fields, 0);
        channel.onRequest(request).run();

        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));

        _contextHandler.setAvailable(false);

        stream = new MockHttpStream(channel);
        request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx/"), HttpVersion.HTTP_1_1, fields, 0);
        channel.onRequest(request).run();

        assertThat(stream.getResponse().getStatus(), equalTo(503));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_HTML_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), containsString("Service Unavailable"));

        _contextHandler.setAvailable(true);

        stream = new MockHttpStream(channel);
        request = new MetaData.Request("GET", HttpURI.from("http://localhost/ctx/"), HttpVersion.HTTP_1_1, fields, 0);
        channel.onRequest(request).run();

        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponseHeaders().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(helloHandler.getMessage()));
    }

    private void assertInContext(Request request)
    {
        try
        {
            if (request != null)
            {
                assertThat(Request.getPathInContext(request), equalTo("/path"));
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

        Handler handler = new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertInContext(request);
                scopeListener.assertInContext(request.getContext(), request);
                response.setStatus(200);
                callback.succeeded();
                return true;
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

        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertInContext(request);
                scopeListener.assertInContext(request.getContext(), request);

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
                request.demand(() ->
                {
                    assertInContext(request);
                    scopeListener.assertInContext(request.getContext(), request);
                    Content.Chunk chunk = request.read();
                    assertTrue(chunk.hasRemaining());
                    assertTrue(chunk.isLast());
                    response.setStatus(200);
                    response.write(true, chunk.getByteBuffer(), Callback.from(
                        () ->
                        {
                            chunk.release();
                            assertInContext(request);
                            scopeListener.assertInContext(request.getContext(), request);
                            callback.succeeded();
                        },
                        t ->
                        {
                            chunk.release();
                            throw new IllegalStateException(t);
                        }));
                });
                return true;
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
            public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
            {
                sendCB.set(callback);
                super.send(request, response, last, content, Callback.NOOP);
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

        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                CountDownLatch latch = new CountDownLatch(1);
                request.demand(() ->
                {
                    assertInContext(request);
                    scopeListener.assertInContext(request.getContext(), request);
                    latch.countDown();
                });

                blocking.countDown();
                assertTrue(latch.await(10, TimeUnit.SECONDS));
                Content.Chunk chunk = request.read();
                assertNotNull(chunk);
                assertTrue(chunk.hasRemaining());
                assertTrue(chunk.isLast());
                chunk.release();
                response.setStatus(200);
                callback.succeeded();
                return true;
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

        Handler handler = new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
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
                return true;
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
    public void testThrownUsesContextErrorHandler() throws Exception
    {
        _contextHandler.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                throw new RuntimeException("Testing");
            }
        });
        _contextHandler.setErrorHandler(new ErrorHandler()
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
        assertThat(stream.getResponse().getHttpFields().size(), equalTo(0));
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

        Handler handler = new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.write(true, null, callback);
                return true;
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

    @Test
    public void testSetHandlerLoopSelf()
    {
        ContextHandler contextHandlerA = new ContextHandler();
        assertThrows(IllegalStateException.class, () -> contextHandlerA.setHandler(contextHandlerA));
    }

    @Test
    public void testSetHandlerLoopDeepWrapper()
    {
        ContextHandler contextHandlerA = new ContextHandler();
        Handler.Singleton handlerWrapper = new Handler.Wrapper();
        contextHandlerA.setHandler(handlerWrapper);
        assertThrows(IllegalStateException.class, () -> handlerWrapper.setHandler(contextHandlerA));
    }

    @Test
    public void testAddHandlerLoopDeep()
    {
        ContextHandler contextHandlerA = new ContextHandler();
        Handler.Sequence handlerCollection = new Handler.Sequence();
        contextHandlerA.setHandler(handlerCollection);
        assertThrows(IllegalStateException.class, () -> handlerCollection.addHandler(contextHandlerA));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSetTempDirectoryNotExists(boolean persistTempDir) throws Exception
    {
        Server server = new Server();
        ContextHandler context = new ContextHandler();
        server.setHandler(context);
        context.setTempDirectoryPersistent(persistTempDir);

        // The temp directory is defined but has not been created.
        File tempDir = MavenTestingUtils.getTargetTestingPath("tempDir").toFile();
        IO.delete(tempDir);
        context.setTempDirectory(tempDir);
        assertThat(context.getTempDirectory(), is(tempDir));
        assertFalse(context.getTempDirectory().exists());

        // Once server is started the WebApp temp directory exists and is valid directory.
        server.start();
        File tempDirectory = context.getTempDirectory();
        assertNotNull(tempDirectory);
        assertTrue(tempDirectory.exists());
        assertTrue(tempDirectory.isDirectory());

        // Once server is stopped the WebApp temp should be deleted if persistTempDir is false.
        server.stop();
        tempDirectory = context.getTempDirectory();
        assertThat(tempDirectory != null && tempDirectory.exists(), is(persistTempDir));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testSetTempDirectoryExists(boolean persistTempDir) throws Exception
    {
        Server server = new Server();
        ContextHandler context = new ContextHandler();
        server.setHandler(context);
        context.setTempDirectoryPersistent(persistTempDir);

        // The temp directory is defined and has already been created.
        File tempDir = MavenTestingUtils.getTargetTestingPath("tempDir").toFile();
        IO.delete(tempDir);
        assertFalse(tempDir.exists());
        assertTrue(tempDir.mkdir());
        context.setTempDirectory(tempDir);
        assertThat(context.getTempDirectory(), is(tempDir));
        assertTrue(tempDir.exists());

        // create some content
        File someFile = new File(tempDir, "somefile.txt");
        assertTrue(someFile.createNewFile());
        assertTrue(someFile.exists());

        // Once server is started the WebApp temp directory exists and is valid directory.
        server.start();
        File tempDirectory = context.getTempDirectory();
        assertNotNull(tempDirectory);
        assertTrue(tempDirectory.exists());
        assertTrue(tempDirectory.isDirectory());

        // Contents exists if persistent else it was deleted
        if (persistTempDir)
            assertTrue(someFile.exists());
        else
            assertFalse(someFile.exists());

        // Once server is stopped the WebApp temp should be deleted if persistTempDir is false.
        server.stop();
        tempDirectory = context.getTempDirectory();
        assertThat(tempDirectory != null && tempDirectory.exists(), is(persistTempDir));
    }

    private static void ensureWritable(File file)
    {
        if (file.exists())
        {
            assertTrue(file.setWritable(true));
            if (file.isDirectory())
            {
                File[] files = file.listFiles();
                if (files != null)
                   for (File child : files)
                        ensureWritable(child);
            }
        }
    }

    public static Stream<Arguments> okTempDirs() throws Exception
    {
        ensureWritable(TEST_OK);
        FS.ensureDeleted(TEST_OK.toPath());
        assertFalse(TEST_OK.exists());
        assertTrue(TEST_OK.mkdir());
        TEST_OK.deleteOnExit();

        File notDirectory = new File(TEST_OK, "notDirectory.txt");
        assertTrue(notDirectory.createNewFile());

        File notWritable = new File(TEST_OK, "notWritable");
        assertTrue(notWritable.mkdir());
        assertTrue(notWritable.setWritable(false));

        File notWriteableParent = new File(TEST_OK, "notWritableParent");
        assertTrue(notWriteableParent.mkdir());
        File cantDelete = new File(notWriteableParent, "cantDelete");
        assertTrue(cantDelete.mkdirs());
        assertTrue(notWriteableParent.setWritable(false));

        return Stream.of(
            Arguments.of(false, notDirectory),
            Arguments.of(false, notWritable),
            Arguments.of(true, cantDelete)
        );
    }

    @ParameterizedTest
    @MethodSource("okTempDirs")
    public void testSetTempDirectoryOK(boolean persistent, File okTempDir) throws Exception
    {
        Server server = new Server();
        ContextHandler context = new ContextHandler();
        server.setHandler(context);
        context.setTempDirectory(okTempDir);
        context.setTempDirectoryPersistent(persistent);

        server.start();

        assertTrue(context.getTempDirectory().exists());
        assertTrue(context.getTempDirectory().isDirectory());
        assertThat(context.getTempDirectory().getAbsolutePath(), equalTo(okTempDir.getAbsolutePath()));

        server.stop();

        if (persistent)
        {
            assertTrue(context.getTempDirectory().exists());
            assertTrue(context.getTempDirectory().isDirectory());
            assertThat(context.getTempDirectory().getAbsolutePath(), equalTo(okTempDir.getAbsolutePath()));
        }
        else
        {
            assertFalse(context.getTempDirectory().exists());
        }
    }

    public static Stream<Arguments> badTempDirs() throws Exception
    {
        ensureWritable(TEST_BAD);
        FS.ensureDeleted(TEST_BAD.toPath());
        assertFalse(TEST_BAD.exists());
        assertTrue(TEST_BAD.mkdir());
        TEST_BAD.deleteOnExit();

        File notDirectory = new File(TEST_BAD, "notDirectory.txt");
        assertTrue(notDirectory.createNewFile());

        File notWritable = new File(TEST_BAD, "notWritable");
        assertTrue(notWritable.mkdir());
        assertTrue(notWritable.setWritable(false));

        File notWriteableParent = new File(TEST_BAD, "notWritableParent");
        assertTrue(notWriteableParent.mkdir());
        File cantCreate = new File(notWriteableParent, "temp");
        File cantDelete = new File(notWriteableParent, "cantDelete");
        assertTrue(cantDelete.mkdirs());
        assertTrue(notWriteableParent.setWritable(false));

        return Stream.of(
            Arguments.of(true, notDirectory),
            Arguments.of(true, notWritable),
            Arguments.of(true, cantCreate),
            Arguments.of(false, cantCreate),
            Arguments.of(false, cantDelete)
        );
    }

    @Disabled // TODO doesn't work on jenkins?
    @ParameterizedTest
    @MethodSource("badTempDirs")
    public void testSetTempDirectoryBad(boolean persistent, File badTempDir)
    {
        Server server = new Server();
        ContextHandler context = new ContextHandler();
        server.setHandler(context);
        context.setTempDirectory(badTempDir);
        context.setTempDirectoryPersistent(persistent);

        assertThrows(IllegalArgumentException.class, server::start);
    }

    private static class ScopeListener implements ContextHandler.ContextScopeListener
    {
        private static final Request NULL = new Request.Wrapper(new TestableRequest());
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

    private static class TestableRequest implements Request
    {
        @Override
        public Object removeAttribute(String name)
        {
            return null;
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            return null;
        }

        @Override
        public Object getAttribute(String name)
        {
            return null;
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return null;
        }

        @Override
        public void clearAttributes()
        {
        }

        @Override
        public String getId()
        {
            return null;
        }

        @Override
        public Components getComponents()
        {
            return null;
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return null;
        }

        @Override
        public String getMethod()
        {
            return null;
        }

        @Override
        public HttpURI getHttpURI()
        {
            return null;
        }

        @Override
        public Context getContext()
        {
            return null;
        }

        @Override
        public HttpFields getHeaders()
        {
            return null;
        }

        @Override
        public HttpFields getTrailers()
        {
            return null;
        }

        public List<HttpCookie> getCookies()
        {
            return null;
        }

        @Override
        public long getBeginNanoTime()
        {
            return 0;
        }

        @Override
        public long getHeadersNanoTime()
        {
            return 0;
        }

        @Override
        public boolean isSecure()
        {
            return false;
        }

        @Override
        public long getLength()
        {
            return 0;
        }

        @Override
        public Content.Chunk read()
        {
            return null;
        }

        @Override
        public boolean consumeAvailable()
        {
            return false;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
        }

        @Override
        public void fail(Throwable failure)
        {
        }

        @Override
        public void addIdleTimeoutListener(Predicate<TimeoutException> onIdleTimeout)
        {
        }

        @Override
        public void addFailureListener(Consumer<Throwable> onFailure)
        {
        }

        @Override
        public TunnelSupport getTunnelSupport()
        {
            return null;
        }

        @Override
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper)
        {
        }

        @Override
        public Session getSession(boolean create)
        {
            return null;
        }
    }

    @Test
    public void testGraceful() throws Exception
    {
        // This is really just another test of GracefulHandler, but good to check it works inside of ContextHandler

        CountDownLatch latch0 = new CountDownLatch(1);
        CountDownLatch latch1 = new CountDownLatch(1);

        CountDownLatch requests = new CountDownLatch(7);

        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                requests.countDown();
                switch (request.getContext().getPathInContext(request.getHttpURI().getCanonicalPath()))
                {
                    case "/ignore0" ->
                    {
                        try
                        {
                            latch0.await();
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                        return false;
                    }

                    case "/ignore1" ->
                    {
                        try
                        {
                            latch1.await();
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                        return false;
                    }

                    case "/ok0" ->
                    {
                        try
                        {
                            latch0.await();
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                    }

                    case "/ok1" ->
                    {
                        try
                        {
                            latch1.await();
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                    }

                    case "/fail0" ->
                    {
                        try
                        {
                            latch0.await();
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                        throw new QuietException.Exception("expected0");
                    }

                    case "/fail1" ->
                    {
                        try
                        {
                            latch1.await();
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                        callback.failed(new QuietException.Exception("expected1"));
                    }

                    default ->
                    {
                    }
                }

                response.setStatus(HttpStatus.OK_200);
                callback.succeeded();
                return true;
            }
        };
        _contextHandler.setHandler(handler);
        GracefulHandler gracefulHandler = new GracefulHandler();
        _contextHandler.insertHandler(gracefulHandler);
        LocalConnector connector = new LocalConnector(_server);
        _server.addConnector(connector);
        _server.start();

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("GET /ctx/ HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        List<LocalConnector.LocalEndPoint> endPoints = new ArrayList<>();
        for (String target : new String[] {"/ignore", "/ok", "/fail"})
        {
            for (int batch = 0; batch <= 1; batch++)
            {
                LocalConnector.LocalEndPoint endPoint = connector.executeRequest("GET /ctx%s%d HTTP/1.0\r\n\r\n".formatted(target, batch));
                endPoints.add(endPoint);
            }
        }

        assertTrue(requests.await(10, TimeUnit.SECONDS));
        assertThat(gracefulHandler.getCurrentRequestCount(), is(6L));

        CompletableFuture<Void> shutdown = Graceful.shutdown(_contextHandler);
        assertFalse(shutdown.isDone());
        assertThat(gracefulHandler.getCurrentRequestCount(), is(6L));

        response = HttpTester.parseResponse(connector.getResponse("GET /ctx/ HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), is(HttpStatus.SERVICE_UNAVAILABLE_503));

        latch0.countDown();

        response = HttpTester.parseResponse(endPoints.get(0).getResponse());
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        response = HttpTester.parseResponse(endPoints.get(2).getResponse());
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        response = HttpTester.parseResponse(endPoints.get(4).getResponse());
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));

        assertFalse(shutdown.isDone());
        Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> gracefulHandler.getCurrentRequestCount() == 3L);
        assertThat(gracefulHandler.getCurrentRequestCount(), is(3L));

        latch1.countDown();

        response = HttpTester.parseResponse(endPoints.get(1).getResponse());
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        response = HttpTester.parseResponse(endPoints.get(3).getResponse());
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        response = HttpTester.parseResponse(endPoints.get(5).getResponse());
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));

        shutdown.get(10, TimeUnit.SECONDS);
        assertTrue(shutdown.isDone());
        assertThat(gracefulHandler.getCurrentRequestCount(), is(0L));
    }

    @Test
    public void testContextDump() throws Exception
    {
        Server server = new Server();
        ContextHandler contextHandler = new ContextHandler("/ctx");
        server.setHandler(contextHandler);
        contextHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                callback.succeeded();
                return true;
            }

            @Override
            public String toString()
            {
                return "TestHandler";
            }
        });

        contextHandler.setAttribute("name", "hidden");
        contextHandler.setAttribute("persistent1", "value1");
        contextHandler.setAttribute("persistent2", Dumpable.named("named", "value2"));

        server.start();

        contextHandler.getContext().setAttribute("name", "override");
        contextHandler.getContext().setAttribute("transient1", "value1");
        contextHandler.getContext().setAttribute("transient2", Dumpable.named("named", "value2"));

        String dump = contextHandler.dump().replaceAll("\\r?\\n", "\n");
        assertThat(dump, containsString("oejsh.ContextHandler@"));
        String expected = """
            +> No ClassLoader
            +> handler attributes size=3
            |  +> name: hidden
            |  +> persistent1: value1
            |  +> persistent2: named: value2
            +> attributes size=5
               +> name: override
               +> persistent1: value1
               +> persistent2: named: value2
               +> transient1: value1
               +> transient2: named: value2
            """;
        assertThat(dump, containsString(expected));
    }
}
