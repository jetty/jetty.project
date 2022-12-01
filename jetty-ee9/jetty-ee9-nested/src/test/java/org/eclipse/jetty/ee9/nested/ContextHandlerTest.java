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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

public class ContextHandlerTest
{
    Server _server;
    ContextHandler _contextHandler;
    LocalConnector _connector;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        Handler.Collection handlers = new Handler.Collection();
        _server.setHandler(handlers);

        _contextHandler = new ContextHandler();
        handlers.setHandlers(_contextHandler.getCoreContextHandler());
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }
    
    @Test
    public void testSimple() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().print("Hello\n");
            }
        });
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET / HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("Hello"));
    }

    @Test
    public void testDump() throws Exception
    {
        _contextHandler.setContextPath("/context");
        _contextHandler.setHandler(new DumpHandler());
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET /context/path/info HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("contextPath=/context"));
        assertThat(response.getContent(), containsString("pathInfo=/path/info"));
    }

    @Test
    public void testDumpHeadersAndParameters() throws Exception
    {
        _contextHandler.setContextPath("/context");
        _contextHandler.setHandler(new DumpHandler());
        _server.start();

        String rawResponse = _connector.getResponse("""
            POST /context/path/info?A=1&B=2 HTTP/1.0
            Host: localhost
            HeaderName: headerValue
            Content-Type: %s
            Content-Length: 7
            
            C=3&D=4
            """.formatted(MimeTypes.Type.FORM_ENCODED.asString()));

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("HeaderName: headerValue"));
        assertThat(response.getContent(), containsString("contextPath=/context"));
        assertThat(response.getContent(), containsString("pathInfo=/path/info"));
        assertThat(response.getContent(), containsString("contentType=application/x-www-form-urlencoded"));
        assertThat(response.getContent(), containsString("""
            A=1
            B=2
            C=3
            D=4
            """));
    }

    @Test
    public void testPersistentConnection() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                org.eclipse.jetty.server.Request coreRequest = baseRequest.getHttpChannel().getCoreRequest();

                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().print("""
                    pathInContext=%s
                    baseRequest.hashCode=%x
                    coreRequest.id=%s
                    coreRequest.connectionMetaData.id=%s
                    coreRequest.connectionMetaData.persistent=%b
                    
                    """.formatted(
                        org.eclipse.jetty.server.Request.getPathInContext(coreRequest),
                        baseRequest.hashCode(),
                        coreRequest.getId(),
                        coreRequest.getConnectionMetaData().getId(),
                        coreRequest.getConnectionMetaData().isPersistent()
                ));
            }
        });
        _server.start();

        try (LocalConnector.LocalEndPoint endPoint = _connector.connect())
        {
            endPoint.addInput("""
                GET /one HTTP/1.1
                Host: localhost
                            
                GET /two HTTP/1.1
                Host: localhost
                            
                """);

            String rawResponse = endPoint.getResponse();
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(200));
            assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
            Properties one = new Properties();
            one.load(new StringReader(response.getContent()));

            rawResponse = endPoint.getResponse();
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(200));
            assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
            Properties two = new Properties();
            two.load(new StringReader(response.getContent()));

            assertThat(one.getProperty("baseRequest.hashCode"), notNullValue());
            assertThat(one.getProperty("baseRequest.hashCode"), equalTo(two.getProperty("baseRequest.hashCode")));

            assertThat(one.getProperty("coreRequest.connectionMetaData.id"), notNullValue());
            assertThat(one.getProperty("coreRequest.connectionMetaData.id"), equalTo(two.getProperty("coreRequest.connectionMetaData.id")));

            assertThat(one.getProperty("coreRequest.id"), notNullValue());
            assertThat(one.getProperty("coreRequest.id"), not(equalTo(two.getProperty("coreRequest.id"))));
        }
    }

    @Test
    public void testAsyncDispatch() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);

                switch (request.getDispatcherType())
                {
                    case REQUEST ->
                    {
                        AsyncContext async = request.startAsync();
                        async.dispatch();
                    }
                    case ASYNC ->
                    {
                        response.setStatus(200);
                        response.setContentType("text/plain");
                        response.getOutputStream().print("Async\n");
                    }

                    default -> throw new IllegalStateException();
                }
            }
        });
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET / HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("Async"));
    }

    @Test
    public void testAsyncDelayedDispatch() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);

                switch (request.getDispatcherType())
                {
                    case REQUEST ->
                    {
                        AsyncContext async = request.startAsync();
                        async.start(() ->
                        {
                            try
                            {
                                Thread.sleep(100);
                                async.dispatch();
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        });
                    }
                    case ASYNC ->
                    {
                        response.setStatus(200);
                        response.setContentType("text/plain");
                        response.getOutputStream().print("Async\n");
                    }

                    default -> throw new IllegalStateException();
                }
            }
        });
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET / HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("Async"));
    }

    @Test
    public void testAsyncDispatchPath() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);

                switch (request.getDispatcherType())
                {
                    case REQUEST ->
                    {
                        AsyncContext async = request.startAsync();
                        async.dispatch("/async");
                    }
                    case ASYNC ->
                    {
                        response.setStatus(200);
                        response.setContentType("text/plain");
                        response.getOutputStream().print("Async %s\n".formatted(baseRequest.getPathInContext()));
                    }

                    default -> throw new IllegalStateException();
                }
            }
        });
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET / HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("Async /async"));
    }

    @Test
    public void testAsyncDispatchWrapped() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);

                switch (request.getDispatcherType())
                {
                    case REQUEST ->
                    {
                        AsyncContext async = request.startAsync(
                            new HttpServletRequestWrapper(request)
                            {
                                @Override
                                public String getRemoteUser()
                                {
                                    return "RemoteUser";
                                }
                            },
                            new HttpServletResponseWrapper(response)
                            {
                                @Override
                                public void setStatus(int sc)
                                {
                                    super.setStatus(sc == Integer.MAX_VALUE ? 200 : sc);
                                }
                            }
                        );
                        async.dispatch();
                    }
                    case ASYNC ->
                    {
                        response.setStatus(Integer.MAX_VALUE);
                        response.setContentType("text/plain");
                        response.getOutputStream().print("Async %s\n".formatted(request.getRemoteUser()));
                    }

                    default -> throw new IllegalStateException();
                }
            }
        });
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET / HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("Async RemoteUser"));
    }

    @Test
    public void testAsyncComplete() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);

                if (request.getDispatcherType() == DispatcherType.REQUEST)
                {
                    AsyncContext async = request.startAsync();
                    response.setStatus(200);
                    response.setContentType("text/plain");
                    response.getOutputStream().print("Async\n");
                    async.complete();
                }
                else
                {
                    throw new IllegalStateException();
                }
            }
        });
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET / HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("Async"));
    }

    @Test
    public void testAsyncDelayedComplete() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);

                if (request.getDispatcherType() == DispatcherType.REQUEST)
                {
                    AsyncContext async = request.startAsync();
                    async.start(() ->
                    {
                        try
                        {
                            Thread.sleep(100);
                            response.setStatus(200);
                            response.setContentType("text/plain");
                            response.getOutputStream().print("Async\n");
                            async.complete();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    });
                }
                else
                {
                    throw new IllegalStateException();
                }
            }
        });
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET / HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("Async"));
    }

    @Test
    public void testException() throws Exception
    {
        _contextHandler.setErrorHandler(new TestErrorHandler());
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                if (request.getDispatcherType() == DispatcherType.ERROR)
                {
                    response.setContentType("text/plain");
                    response.getOutputStream().print("ERROR %s\n".formatted(baseRequest.getPathInContext()));
                    return;
                }

                throw new RuntimeException("testing");
            }
        });
        _server.start();

        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
        {
            String rawResponse = _connector.getResponse("""
                GET / HTTP/1.0
                            
                """);

            HttpTester.Response response = HttpTester.parseResponse(rawResponse);

            assertThat(response.getStatus(), is(500));
            assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
            assertThat(response.getContent(), containsString("ERROR /errorPage"));
        }
    }

    @Test
    public void testSendError() throws Exception
    {
        _contextHandler.setErrorHandler(new TestErrorHandler());
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                if (request.getDispatcherType() == DispatcherType.ERROR)
                {
                    response.setContentType("text/plain");
                    response.getOutputStream().print("ERROR %s\n".formatted(baseRequest.getPathInContext()));
                    return;
                }

                response.sendError(503);
            }
        });
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET / HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(503));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("ERROR /errorPage"));
    }

    @Test
    public void testTwoContexts() throws Exception
    {
        _contextHandler.setContextPath("/ctxA");
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().print("Hello %s\n".formatted(baseRequest.getContext().getContextPath()));
            }
        });

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/ctxB");
        contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().print("Buongiorno %s\n".formatted(baseRequest.getContext().getContextPath()));
            }
        });

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.addHandler(_contextHandler);
        contexts.addHandler(contextHandler);
        _server.setHandler(contexts);
        _server.start();

        try (LocalConnector.LocalEndPoint endp = _connector.connect())
        {
            endp.addInput("""
                GET /ctxA/ HTTP/1.1
                Host: localhost
                            
                """);
            String raw = endp.getResponse();
            HttpTester.Response response = HttpTester.parseResponse(raw);
            assertThat(response.getStatus(), is(200));
            assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
            assertThat(response.getContent(), containsString("Hello /ctxA"));

            endp.addInput("""
                GET /ctxB/ HTTP/1.1
                Host: localhost
                            
                """);
            raw = endp.getResponse();
            response = HttpTester.parseResponse(raw);
            assertThat(response.getStatus(), is(200));
            assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
            assertThat(response.getContent(), containsString("Buongiorno /ctxB"));
        }
    }

    @Test
    public void testContextListeners() throws Exception
    {
        Queue<String> history = new ConcurrentLinkedQueue<>();
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                history.add("Handling");
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().print("Hello\n");
            }
        });

        _contextHandler.getCoreContextHandler().addEventListener(new org.eclipse.jetty.server.handler.ContextHandler.ContextScopeListener()
        {
            @Override
            public void enterScope(Context context, org.eclipse.jetty.server.Request request)
            {
                if (request != null)
                    history.add("Core enter " + request.getHttpURI());
            }

            @Override
            public void exitScope(Context context, org.eclipse.jetty.server.Request request)
            {
                if (request != null)
                    history.add("Core exit " + request.getHttpURI());
            }
        });
        _contextHandler.addEventListener(new ContextHandler.ContextScopeListener()
        {
            @Override
            public void enterScope(ContextHandler.APIContext context, Request request, Object reason)
            {
                if (request != null)
                    history.add("EE9 enter " + request.getRequestURI());
            }

            @Override
            public void exitScope(ContextHandler.APIContext context, Request request)
            {
                if (request != null)
                    history.add("EE9 exit " + request.getRequestURI());
            }
        });
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET / HTTP/1.0
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField(HttpHeader.CONTENT_LENGTH).getIntValue(), greaterThan(0));
        assertThat(response.getContent(), containsString("Hello"));

        history.forEach(System.err::println);

        assertThat(history, contains(
            // Enter for process(request, response, callback)
            "Core enter http://0.0.0.0/",
            "EE9 enter /",
            "Handling",
            "EE9 exit /",
            "Core exit http://0.0.0.0/"));
    }

    private static class TestErrorHandler extends ErrorHandler implements ErrorHandler.ErrorPageMapper
    {
        @Override
        public String getErrorPage(HttpServletRequest request)
        {
            return "/errorPage";
        }
    }
}
