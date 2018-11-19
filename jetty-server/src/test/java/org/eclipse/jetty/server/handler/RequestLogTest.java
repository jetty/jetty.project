//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;

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
import org.eclipse.jetty.server.AbstractNCSARequestLog;
import org.eclipse.jetty.server.Handler;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class RequestLogTest
{
    Log _log;
    Server _server;
    LocalConnector _connector;
    

    @BeforeEach
    public void before() throws Exception
    {
        _log = new Log();
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
        _connector.getResponse("GET "+requestPath+" HTTP/1.0\r\n\r\n");
    }



    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }
    
    
    @Test
    public void testNotHandled() throws Exception
    {
        testHandlerServerStart();
        
        _connector.getResponse("GET /foo HTTP/1.0\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo HTTP/1.0\" 404 "));
    }

    @Test
    public void testRequestLine() throws Exception
    {
        testHandlerServerStart();
        
        _connector.getResponse("GET /foo?data=1 HTTP/1.0\nhost: host:80\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?data=1 HTTP/1.0\" 200 "));
        
        _connector.getResponse("GET //bad/foo?data=1 HTTP/1.0\n\n");
        log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET //bad/foo?data=1 HTTP/1.0\" 200 "));
                
        _connector.getResponse("GET http://host:80/foo?data=1 HTTP/1.0\n\n");
        log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET http://host:80/foo?data=1 HTTP/1.0\" 200 "));   
    }
    
    @Test
    public void testHTTP10Host() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse(
            "GET /foo?name=value HTTP/1.0\n"+
            "Host: servername\n"+
            "\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?name=value"));
        assertThat(log,containsString(" 200 "));
    }
    
    @Test
    public void testHTTP11() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse(
            "GET /foo?name=value HTTP/1.1\n"+
            "Host: servername\n"+
            "\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?name=value"));
        assertThat(log,containsString(" 200 "));
    }
    
    @Test
    public void testAbsolute() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse(
            "GET http://hostname:8888/foo?name=value HTTP/1.1\n"+
            "Host: servername\n"+
            "\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET http://hostname:8888/foo?name=value"));
        assertThat(log,containsString(" 200 "));
    }
    
    @Test
    public void testQuery() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse("GET /foo?name=value HTTP/1.0\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?name=value"));
        assertThat(log,containsString(" 200 "));
    }
    
    @Test
    public void testSmallData() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse("GET /foo?data=42 HTTP/1.0\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?"));
        assertThat(log,containsString(" 200 42 "));
    }
    
    @Test
    public void testBigData() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse("GET /foo?data=102400 HTTP/1.0\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?"));
        assertThat(log,containsString(" 200 102400 "));
    }
    
    @Test
    public void testStatus() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse("GET /foo?status=206 HTTP/1.0\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?"));
        assertThat(log,containsString(" 206 0 "));
    }
    
    @Test
    public void testStatusData() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse("GET /foo?status=206&data=42 HTTP/1.0\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?"));
        assertThat(log,containsString(" 206 42 "));
    }
    
    @Test
    public void testBadRequest() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse("XXXXXXXXXXXX\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("\"- - -\""));
        assertThat(log,containsString(" 400 "));
    }
    
    @Test
    public void testBadCharacter() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse("METHOD /f\00o HTTP/1.0\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("\"- - -\""));
        assertThat(log,containsString(" 400 "));
    }
    
    @Test
    public void testBadVersion() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse("METHOD /foo HTTP/9\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("\"- - -\""));
        assertThat(log,containsString(" 400 "));
    }
    
    @Test
    public void testLongURI() throws Exception
    {
        testHandlerServerStart();

        char[] chars = new char[10000];
        Arrays.fill(chars,'o');
        String ooo = new String(chars);
        _connector.getResponse("METHOD /f"+ooo+" HTTP/1.0\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("\"- - -\""));
        assertThat(log,containsString(" 414 "));
    }
    
    @Test
    public void testLongHeader() throws Exception
    {
        testHandlerServerStart();

        char[] chars = new char[10000];
        Arrays.fill(chars,'o');
        String ooo = new String(chars);
        _connector.getResponse("METHOD /foo HTTP/1.0\name: f+"+ooo+"\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("\"METHOD /foo HTTP/1.0\""));
        assertThat(log,containsString(" 431 "));
    }
    
    @Test
    public void testBadRequestNoHost() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse("GET /foo HTTP/1.1\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo "));
        assertThat(log,containsString(" 400 "));
    }

    @Test
    public void testUseragentWithout() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse("GET http://[:1]/foo HTTP/1.1\nReferer: http://other.site\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET http://[:1]/foo "));
        assertThat(log,containsString(" 400 50 \"http://other.site\" \"-\" - "));
    }

    @Test
    public void testUseragentWith() throws Exception
    {
        testHandlerServerStart();

        _connector.getResponse("GET http://[:1]/foo HTTP/1.1\nReferer: http://other.site\nUser-Agent: Mozilla/5.0 (test)\n\n");
        String log = _log.entries.poll(5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET http://[:1]/foo "));
        assertThat(log,containsString(" 400 50 \"http://other.site\" \"Mozilla/5.0 (test)\" - "));
    }
    

    // Tests from here use these parameters
    public static Stream<Arguments> data()
    {
        List<Object[]> data = new ArrayList<>();

        data.add(new Object[] { new NoopHandler(), "/noop", "\"GET /noop HTTP/1.0\" 404" });
        data.add(new Object[] { new HelloHandler(), "/hello", "\"GET /hello HTTP/1.0\" 200" });
        data.add(new Object[] { new ResponseSendErrorHandler(), "/sendError", "\"GET /sendError HTTP/1.0\" 599" });
        data.add(new Object[] { new ServletExceptionHandler(), "/sex", "\"GET /sex HTTP/1.0\" 500" });
        data.add(new Object[] { new IOExceptionHandler(), "/ioex", "\"GET /ioex HTTP/1.0\" 500" });
        data.add(new Object[] { new RuntimeExceptionHandler(), "/rtex", "\"GET /rtex HTTP/1.0\" 500" });
        data.add(new Object[] { new BadMessageHandler(), "/bad", "\"GET /bad HTTP/1.0\" 499" });
        data.add(new Object[] { new AbortHandler(), "/bad", "\"GET /bad HTTP/1.0\" 488" });

        return data.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testServerRequestLog(Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        _server.setRequestLog(_log);
        _server.setHandler(testHandler);
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLogHandlerWrapper(Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        RequestLogHandler handler = new RequestLogHandler();
        handler.setRequestLog(_log);
        handler.setHandler(testHandler);
        _server.setHandler(handler);
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLogHandlerCollectionFirst(Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        RequestLogHandler handler = new RequestLogHandler();
        handler.setRequestLog(_log);
        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[] { handler, testHandler });
        _server.setHandler(handlers);
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);
    }


    @ParameterizedTest
    @MethodSource("data")
    public void testLogHandlerCollectionLast(Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        RequestLogHandler handler = new RequestLogHandler();
        handler.setRequestLog(_log);
        // This is the old ordering of request handler and it cannot well handle thrown exception
        Assumptions.assumeTrue(
            testHandler instanceof NoopHandler ||
                testHandler instanceof HelloHandler ||
                testHandler instanceof ResponseSendErrorHandler
        );

        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[] { testHandler, handler });
        _server.setHandler(handlers);
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);
    }


    @ParameterizedTest
    @MethodSource("data")
    public void testErrorHandler(Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        _server.setRequestLog(_log);
        AbstractHandler.ErrorDispatchHandler wrapper = new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                testHandler.handle(target,baseRequest,request,response);
            }
        };

        _server.setHandler(wrapper);

        List<String> errors = new ArrayList<>();
        ErrorHandler errorHandler = new ErrorHandler()
        {
            @Override
            public void doError(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                errors.add(baseRequest.getRequestURI());
                super.doError(target, baseRequest, request, response);
            }
        };
        _server.addBean(errorHandler);
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);

        if (!(testHandler instanceof HelloHandler))
            assertThat(errors,contains(requestPath));
    }


    @ParameterizedTest
    @MethodSource("data")
    public void testOKErrorHandler(Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        _server.setRequestLog(_log);
        AbstractHandler.ErrorDispatchHandler wrapper = new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                testHandler.handle(target,baseRequest,request,response);
            }
        };

        _server.setHandler(wrapper);

        ErrorHandler errorHandler = new OKErrorHandler();
        _server.addBean(errorHandler);
        startServer();
        makeRequest(requestPath);

        expectedLogEntry = "\"GET " + requestPath + " HTTP/1.0\" 200";
        assertRequestLog(expectedLogEntry, _log);
    }


    @ParameterizedTest
    @MethodSource("data")
    public void testAsyncDispatch(Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        _server.setRequestLog(_log);
        _server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                if (Boolean.TRUE.equals(request.getAttribute("ASYNC")))
                    testHandler.handle(target,baseRequest,request,response);
                else
                {
                    request.setAttribute("ASYNC",Boolean.TRUE);
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
    @MethodSource("data")
    public void testAsyncComplete(Handler testHandler, String requestPath, String expectedLogEntry) throws Exception
    {
        _server.setRequestLog(_log);
        _server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                if (Boolean.TRUE.equals(request.getAttribute("ASYNC")))
                    testHandler.handle(target,baseRequest,request,response);
                else
                {
                    request.setAttribute("ASYNC",Boolean.TRUE);
                    AsyncContext ac = request.startAsync();
                    ac.setTimeout(1000);
                    baseRequest.setHandled(true);
                    _server.getThreadPool().execute(()->
                    {
                        try
                        {
                            try
                            {
                                baseRequest.setHandled(false);
                                testHandler.handle(target, baseRequest, request, response);
                                if (!baseRequest.isHandled())
                                    response.sendError(404);
                            }
                            catch (BadMessageException bad)
                            {
                                response.sendError(bad.getCode());
                            }
                            catch (Exception e)
                            {
                                response.sendError(500);
                            }
                        }
                        catch(Throwable th)
                        {
                            throw new RuntimeException(th);
                        }
                        ac.complete();
                    });
                }
            }
        });
        startServer();
        makeRequest(requestPath);
        assertRequestLog(expectedLogEntry, _log);
    }


    private void assertRequestLog(final String expectedLogEntry, Log log) throws Exception
    {
        String line = log.entries.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(line);
        assertThat(line,containsString(expectedLogEntry));
        Assertions.assertTrue(log.entries.isEmpty());
    }

    public static class CaptureLog extends AbstractLifeCycle implements RequestLog
    {
        public BlockingQueue<String> log = new BlockingArrayQueue<>();

        @Override
        public void log(Request request, Response response)
        {
            int status = response.getCommittedMetaData().getStatus();
            log.add(String.format("%s %s %s %03d",request.getMethod(),request.getRequestURI(),request.getProtocol(),status));
        }
    }

    private static abstract class AbstractTestHandler extends AbstractHandler
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
            if (baseRequest!=null)
                baseRequest.setHandled(true);
        }
    }

    private static class ResponseSendErrorHandler extends AbstractTestHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.sendError(599,"expected");
            if (baseRequest!=null)
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
            String reason = (response instanceof Response)?((Response)response).getReason():null;
            int status = response.getStatus();

            // intentionally set response status to OK (this is a test to see what is actually logged)
            response.setStatus(200);
            response.setContentType("text/plain");
            PrintWriter out = response.getWriter();
            out.printf("Error %d: %s%n",status,reason);
            baseRequest.setHandled(true);
        }
    }


    private class Log extends AbstractNCSARequestLog
    {
        public BlockingQueue<String> entries = new BlockingArrayQueue<>();

        Log()
        {
            super.setExtended(true);
            super.setLogLatency(true);
            super.setLogCookies(true);
        }

        @Override
        protected boolean isEnabled()
        {
            return true;
        }

        @Override
        public void write(String requestEntry) throws IOException
        {
            try
            {
                entries.add(requestEntry);
            }
            catch(Exception e)
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
            if (q==null)
                return;

            baseRequest.setHandled(true);
            for (String action : q.split("\\&"))
            {
                String[] param = action.split("=");
                String name=param[0];
                String value=param.length>1?param[1]:null;
                switch(name)
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

                        int w=0;
                        while (w<data)
                        {
                            if ((data-w)>17)
                            {
                                w+=17;
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
                        catch(ServletException | IOException | Error | RuntimeException e)
                        {
                            throw e;
                        }
                        catch(Throwable e)
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
                        while (in.read()>=0);
                        break;
                    }
                }
            }
        }
    }
}
