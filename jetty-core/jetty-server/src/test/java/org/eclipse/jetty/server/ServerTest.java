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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.server.internal.HttpConnection;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class ServerTest
{
    private Server _server;
    private ContextHandler _context;
    private LocalConnector _connector;
    private final AtomicReference<Runnable> _afterHandle = new AtomicReference<>();

    @BeforeEach
    public void prepare() throws Exception
    {
        _server = new Server();
        _context = new ContextHandler("/");
        _server.setHandler(_context);
        _connector = new LocalConnector(_server, new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                HttpConnection connection = new HttpConnection(getHttpConfiguration(), connector, endPoint)
                {
                    @Override
                    protected HttpChannel newHttpChannel(Server server, HttpConfiguration configuration)
                    {
                        return new HttpChannelState(this)
                        {
                            @Override
                            public Runnable onRequest(MetaData.Request request)
                            {
                                Runnable onRequest = super.onRequest(request);
                                if (onRequest == null)
                                    return null;

                                return () ->
                                {
                                    try
                                    {
                                        onRequest.run();
                                    }
                                    finally
                                    {
                                        Runnable after = _afterHandle.getAndSet(null);
                                        if (after != null)
                                            getExecutor().execute(after);
                                    }
                                };
                            }
                        };
                    }
                };
                connection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
                connection.setUseOutputDirectByteBuffers(isUseOutputDirectByteBuffers());
                return configure(connection, connector, endPoint);
            }
        });
        _server.addConnector(_connector);
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(_server);
        _connector = null;
    }

    @Test
    public void testContextTempDirectory()
    {
        File tempDirectory = _server.getContext().getTempDirectory();
        assertThat(tempDirectory, not(nullValue()));
    }

    @Test
    public void testSimpleGET() throws Exception
    {
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                Content.Sink.write(response, true, "Hello", callback);
                return true;
            }
        });
        _server.start();

        String request = """
                GET /path HTTP/1.0\r
                Host: hostname\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("Hello"));
    }

    @Test
    public void testDump() throws Exception
    {
        testSimpleGET();
        ((QueuedThreadPool)(_server.getThreadPool())).tryExecute(() -> {});
        String dump = _server.dump();
        assertThat(dump, containsString("oejs.Server@"));
        assertThat(dump, containsString("QueuedThreadPool"));
        assertThat(dump, containsString("+= ReservedThreadExecutor@"));
        assertThat(dump, containsString(".ArrayByteBufferPool@"));
        assertThat(dump, containsString("+- System Properties size="));
        assertThat(dump, containsString("+> java.home: "));
        assertThat(dump, containsString("+> java.runtime.version: "));
        assertThat(dump, containsString("+= oejsh.ContextHandler@"));
        assertThat(dump, containsString("+= LocalConnector@"));
        assertThat(dump, containsString("key: +-"));
        assertThat(dump, containsString("JVM: "));
        assertThat(dump, containsString(Jetty.VERSION));
    }

    public static Stream<Arguments> completionScenarios()
    {
        List<Arguments> arguments = new ArrayList<>();
        for (Boolean succeeded : List.of(true, false))
        {
            for (Boolean handling : List.of(true, false))
            {
                for (Boolean written : List.of(true, false))
                {
                    for (Boolean last : written ? List.of(true, false) : List.of(false))
                    {
                        arguments.add(Arguments.of(succeeded, handling, written, last));
                    }
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("completionScenarios")
    public void testCompletion(boolean succeeded, boolean handling, boolean written, boolean last) throws Exception
    {
        _context.setHandler(new Handler.Abstract(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                if (written)
                {
                    try (Blocker.Callback blocker = Blocker.callback())
                    {
                        Content.Sink.write(response, last, "Hello", blocker);
                        blocker.block();
                    }
                }

                Runnable complete = succeeded ? callback::succeeded : () -> callback.failed(new QuietException.Exception("Test"));
                if (handling)
                    complete.run();
                else
                    _afterHandle.set(complete);

                return true;
            }
        });
        _server.start();

        String request = """
                GET /path HTTP/1.1\r
                Host: hostname\r
                \r
                """;
        String rawResponse = _connector.getResponse(request);

        if (succeeded || written)
            assertThat(rawResponse, containsString("HTTP/1.1 200 OK"));
        else
            assertThat(rawResponse, containsString("HTTP/1.1 500 Server Error"));

        if (written)
            assertThat(rawResponse, containsString("Hello"));
        else
            assertThat(rawResponse, not(containsString("Hello")));

        if (written && !last)
        {
            assertThat(rawResponse, containsString("chunked"));
            if (succeeded)
                assertThat(rawResponse, containsString("\r\n0\r\n"));
            else
                assertThat(rawResponse, not(containsString("\r\n0\r\n")));
        }
        else
        {
            assertThat(rawResponse, containsString("Content-Length:"));
        }
    }
}
