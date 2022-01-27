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
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class NcsaRequestLogTest
{
    private static final Logger LOG = LoggerFactory.getLogger(NcsaRequestLogTest.class);

    RequestLog _log;
    Server _server;
    LocalConnector _connector;
    BlockingQueue<String> _entries = new BlockingArrayQueue<>();
    StacklessLogging stacklessLogging;

    private void setup(String logType) throws Exception
    {
        TestRequestLogWriter writer = new TestRequestLogWriter();

        switch (logType)
        {
            case "customNCSA":
                _log = new CustomRequestLog(writer, CustomRequestLog.EXTENDED_NCSA_FORMAT);
                break;
            default:
                throw new IllegalStateException("invalid logType");
        }

        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
    }

    void testHandlerServerStart() throws Exception
    {
        _server.setRequestLog(_log);
        _server.setHandler(new TestHandler());
        _server.start();
    }

    private void startServer() throws Exception
    {
        _server.start();
    }

    private void makeRequest(String requestPath) throws Exception
    {
        _connector.getResponse("GET " + requestPath + " HTTP/1.0\r\n\r\n");
    }

    @BeforeEach
    public void before() throws Exception
    {
        stacklessLogging = new StacklessLogging(HttpChannel.class);
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
        stacklessLogging.close();
    }

    // TODO include logback?
    public static Stream<Arguments> ncsaImplementations()
    {
        return Stream.of(Arguments.of("customNCSA"));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testNotHandled(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("GET /foo HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET /foo HTTP/1.0\" 404 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testRequestLine(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("GET /foo?data=1 HTTP/1.0\nhost: host:80\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET /foo?data=1 HTTP/1.0\" 200 "));

        _connector.getResponse("GET //bad/foo?data=1 HTTP/1.0\n\n");
        log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET //bad/foo?data=1 HTTP/1.0\" 400 "));

        _connector.getResponse("GET http://host:80/foo?data=1 HTTP/1.0\n\n");
        log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET http://host:80/foo?data=1 HTTP/1.0\" 200 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testHTTP10Host(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse(
            "GET /foo?name=value HTTP/1.0\n" +
                "Host: servername\n" +
                "\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET /foo?name=value"));
        assertThat(log, containsString(" 200 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testHTTP11(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse(
            "GET /foo?name=value HTTP/1.1\n" +
                "Host: servername\n" +
                "\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET /foo?name=value"));
        assertThat(log, containsString(" 200 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testAbsolute(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse(
            "GET http://hostname:8888/foo?name=value HTTP/1.1\n" +
                "Host: servername\n" +
                "\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET http://hostname:8888/foo?name=value"));
        assertThat(log, containsString(" 200 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testQuery(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("GET /foo?name=value HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET /foo?name=value"));
        assertThat(log, containsString(" 200 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testSmallData(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("GET /foo?data=42 HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET /foo?"));
        assertThat(log, containsString(" 200 42 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testBigData(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("GET /foo?data=102400 HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET /foo?"));
        assertThat(log, containsString(" 200 102400 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testStatus(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("GET /foo?status=206 HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET /foo?"));
        assertThat(log, containsString(" 206 0 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testStatusData(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("GET /foo?status=206&data=42 HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET /foo?"));
        assertThat(log, containsString(" 206 42 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testBadRequest(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("XXXXXXXXXXXX\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("\"- - -\""));
        assertThat(log, containsString(" 400 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testBadCharacter(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("METHOD /f\00o HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("\"- - -\""));
        assertThat(log, containsString(" 400 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testBadVersion(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("METHOD /foo HTTP/9\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("\"- - -\""));
        assertThat(log, containsString(" 505 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testLongURI(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        char[] chars = new char[10000];
        Arrays.fill(chars, 'o');
        String ooo = new String(chars);
        _connector.getResponse("METHOD /f" + ooo + " HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("\"- - -\""));
        assertThat(log, containsString(" 414 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testLongHeader(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        char[] chars = new char[10000];
        Arrays.fill(chars, 'o');
        String ooo = new String(chars);
        _connector.getResponse("METHOD /foo HTTP/1.0\name: f+" + ooo + "\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("\"METHOD /foo HTTP/1.0\""));
        assertThat(log, containsString(" 431 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testBadRequestNoHost(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("GET /foo HTTP/1.1\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET /foo "));
        assertThat(log, containsString(" 400 "));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testUseragentWithout(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("GET http://[:1]/foo HTTP/1.1\nReferer: http://other.site\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET http://[:1]/foo "));
        assertThat(log, containsString(" 400 50 \"http://other.site\" \"-\""));
    }

    @ParameterizedTest()
    @MethodSource("ncsaImplementations")
    public void testUseragentWith(String logType) throws Exception
    {
        setup(logType);
        testHandlerServerStart();

        _connector.getResponse("GET http://[:1]/foo HTTP/1.1\nReferer: http://other.site\nUser-Agent: Mozilla/5.0 (test)\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, containsString("GET http://[:1]/foo "));
        assertThat(log, containsString(" 400 50 \"http://other.site\" \"Mozilla/5.0 (test)\""));
    }

    // Tests from here use these parameters
    public static Stream<Arguments> scenarios()
    {
        List<Object[]> data = new ArrayList<>();
        ncsaImplementations().forEach(arg ->
        {
            String logType = String.valueOf(arg.get()[0]);
            data.add(new Object[]{logType, new NoopHandler(), "/noop", "\"GET /noop HTTP/1.0\" 404"});
            data.add(new Object[]{logType, new HelloHandler(), "/hello", "\"GET /hello HTTP/1.0\" 200"});
            data.add(new Object[]{logType, new ResponseSendErrorHandler(), "/sendError", "\"GET /sendError HTTP/1.0\" 599"});
            data.add(new Object[]{logType, new ServletExceptionHandler(), "/sex", "\"GET /sex HTTP/1.0\" 500"});
            data.add(new Object[]{logType, new IOExceptionHandler(), "/ioex", "\"GET /ioex HTTP/1.0\" 500"});
            data.add(new Object[]{logType, new IOExceptionPartialHandler(), "/ioex", "\"GET /ioex HTTP/1.0\" 200"});
            data.add(new Object[]{logType, new RuntimeExceptionHandler(), "/rtex", "\"GET /rtex HTTP/1.0\" 500"});
            data.add(new Object[]{logType, new BadMessageHandler(), "/bad", "\"GET /bad HTTP/1.0\" 499"});
            data.add(new Object[]{logType, new AbortHandler(), "/bad", "\"GET /bad HTTP/1.0\" 500"});
            data.add(new Object[]{logType, new AbortPartialHandler(), "/bad", "\"GET /bad HTTP/1.0\" 200"});
        });

        return data.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testServerRequestLog(String logType, Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        setup(logType);
        _server.setRequestLog(_log);
        _server.setHandler(testHandler);
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLogHandlerWrapper(String logType, Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        setup(logType);
        RequestLogHandler handler = new RequestLogHandler();
        handler.setRequestLog(_log);
        handler.setHandler(testHandler);
        _server.setHandler(handler);
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLogHandlerCollectionFirst(String logType, Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        setup(logType);
        RequestLogHandler handler = new RequestLogHandler();
        handler.setRequestLog(_log);
        HandlerList handlers = new HandlerList(handler, testHandler);
        _server.setHandler(handlers);
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLogHandlerCollectionLast(String logType, Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        setup(logType);
        RequestLogHandler handler = new RequestLogHandler();
        handler.setRequestLog(_log);
        // This is the old ordering of request handler and it cannot well handle thrown exception
        Assumptions.assumeTrue(
            testHandler instanceof NoopHandler ||
                testHandler instanceof HelloHandler ||
                testHandler instanceof ResponseSendErrorHandler
        );

        HandlerCollection handlers = new HandlerCollection(testHandler, handler);
        _server.setHandler(handlers);
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testErrorHandler(String logType, Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        setup(logType);
        _server.setRequestLog(_log);
        AbstractHandler wrapper = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                testHandler.handle(target, baseRequest, request, response);
            }
        };

        _server.setHandler(wrapper);

        List<String> errors = new ArrayList<>();
        ErrorHandler errorHandler = new ErrorHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                errors.add(baseRequest.getRequestURI());
                super.handle(target, baseRequest, request, response);
            }
        };
        _server.addBean(errorHandler);
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testOKErrorHandler(String logType, Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        setup(logType);
        _server.setRequestLog(_log);
        AbstractHandler wrapper = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                testHandler.handle(target, baseRequest, request, response);
            }
        };

        _server.setHandler(wrapper);

        ErrorHandler errorHandler = new OKErrorHandler();
        _server.addBean(errorHandler);
        startServer();
        makeRequest(requestPath);

        // If we abort, we can't write a 200 error page
        if (!(testHandler instanceof AbortHandler))
            expectedLogEntry = expectedLogEntry.replaceFirst(" [1-9][0-9][0-9]", " 200");
        assertRequestLog(expectedLogEntry, _log);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testAsyncDispatch(String logType, Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        setup(logType);
        _server.setRequestLog(_log);
        _server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                if (Boolean.TRUE.equals(request.getAttribute("ASYNC")))
                    testHandler.handle(target, baseRequest, request, response);
                else
                {
                    request.setAttribute("ASYNC", Boolean.TRUE);
                    AsyncContext ac = request.startAsync();
                    ac.setTimeout(1000);
                    ac.dispatch();
                    baseRequest.setHandled(true);
                }
            }
        });
        startServer();
        makeRequest(requestPath);

        assertRequestLog(expectedLogEntry, _log);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testAsyncComplete(String logType, Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        setup(logType);
        _server.setRequestLog(_log);
        _server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                if (Boolean.TRUE.equals(request.getAttribute("ASYNC")))
                    testHandler.handle(target, baseRequest, request, response);
                else
                {
                    request.setAttribute("ASYNC", Boolean.TRUE);
                    AsyncContext ac = request.startAsync();
                    ac.setTimeout(1000);
                    baseRequest.setHandled(true);
                    _server.getThreadPool().execute(() ->
                    {
                        try
                        {
                            try
                            {
                                while (baseRequest.getHttpChannel().getState().getState() != HttpChannelState.State.WAITING)
                                {
                                    Thread.sleep(10);
                                }
                                baseRequest.setHandled(false);
                                testHandler.handle(target, baseRequest, request, response);
                                if (!baseRequest.isHandled())
                                    response.sendError(404);
                            }
                            catch (BadMessageException bad)
                            {
                                response.sendError(bad.getCode(), bad.getReason());
                            }
                            catch (Exception e)
                            {
                                response.sendError(500, e.toString());
                            }
                        }
                        catch (IOException | IllegalStateException th)
                        {
                            LOG.trace("IGNORED", th);
                        }
                        finally
                        {
                            ac.complete();
                        }
                    });
                }
            }
        });
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);
    }

    private void assertRequestLog(final String expectedLogEntry, RequestLog log) throws Exception
    {
        String line = _entries.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(line);
        assertThat(line, containsString(expectedLogEntry));
        Assertions.assertTrue(_entries.isEmpty());
    }

    public static class CaptureLog extends AbstractLifeCycle implements RequestLog
    {
        public BlockingQueue<String> log = new BlockingArrayQueue<>();

        @Override
        public void log(Request request, Response response)
        {
            int status = response.getCommittedMetaData().getStatus();
            log.add(String.format("%s %s %s %03d", request.getMethod(), request.getRequestURI(), request.getProtocol(), status));
        }
    }

    private abstract static class AbstractTestHandler extends AbstractHandler
    {
        @Override
        public String toString()
        {
            return this.getClass().getSimpleName();
        }
    }

    private static class NoopHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
        }
    }

    private static class HelloHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.setContentType("text/plain");
            response.getWriter().print("Hello World");
            if (baseRequest != null)
                baseRequest.setHandled(true);
        }
    }

    private static class ResponseSendErrorHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.sendError(599, "expected");
            if (baseRequest != null)
                baseRequest.setHandled(true);
        }
    }

    private static class ServletExceptionHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            throw new ServletException("expected");
        }
    }

    private static class IOExceptionHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            throw new IOException("expected");
        }
    }

    private static class IOExceptionPartialHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentType("text/plain");
            response.setContentLength(100);
            response.getOutputStream().println("You were expecting maybe a ");
            response.flushBuffer();
            throw new IOException("expected");
        }
    }

    private static class RuntimeExceptionHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            throw new RuntimeException("expected");
        }
    }

    private static class BadMessageHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            throw new BadMessageException(499);
        }
    }

    private static class AbortHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            BadMessageException bad = new BadMessageException(488);
            baseRequest.getHttpChannel().abort(bad);
            throw bad;
        }
    }

    private static class AbortPartialHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentType("text/plain");
            response.setContentLength(100);
            response.getOutputStream().println("You were expecting maybe a ");
            response.flushBuffer();
            baseRequest.getHttpChannel().abort(new Throwable("bomb"));
        }
    }

    public static class OKErrorHandler extends ErrorHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            if (baseRequest.isHandled() || response.isCommitted())
            {
                return;
            }

            // collect error details
            String reason = (response instanceof Response) ? ((Response)response).getReason() : null;
            int status = response.getStatus();

            // intentionally set response status to OK (this is a test to see what is actually logged)
            response.setStatus(200);
            response.setContentType("text/plain");
            PrintWriter out = response.getWriter();
            out.printf("Error %d: %s%n", status, reason);
            baseRequest.setHandled(true);
        }
    }

    class TestRequestLogWriter implements RequestLog.Writer
    {
        @Override
        public void write(String requestEntry)
        {
            try
            {
                _entries.add(requestEntry);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private class TestHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            String q = request.getQueryString();
            if (q == null)
                return;

            baseRequest.setHandled(true);
            for (String action : q.split("\\&"))
            {
                String[] param = action.split("=");
                String name = param[0];
                String value = param.length > 1 ? param[1] : null;
                switch (name)
                {
                    case "status":
                    {
                        response.setStatus(Integer.parseInt(value));
                        break;
                    }

                    case "data":
                    {
                        int data = Integer.parseInt(value);
                        PrintWriter out = response.getWriter();

                        int w = 0;
                        while (w < data)
                        {
                            if ((data - w) > 17)
                            {
                                w += 17;
                                out.print("0123456789ABCDEF\n");
                            }
                            else
                            {
                                w++;
                                out.print("\n");
                            }
                        }
                        break;
                    }

                    case "throw":
                    {
                        try
                        {
                            throw (Throwable)(Class.forName(value).getDeclaredConstructor().newInstance());
                        }
                        catch (ServletException | IOException | Error | RuntimeException e)
                        {
                            throw e;
                        }
                        catch (Throwable e)
                        {
                            throw new ServletException(e);
                        }
                    }
                    case "flush":
                    {
                        response.flushBuffer();
                        break;
                    }

                    case "read":
                    {
                        InputStream in = request.getInputStream();
                        while (in.read() >= 0)
                        {
                            ;
                        }
                        break;
                    }
                }
            }
        }
    }
}
