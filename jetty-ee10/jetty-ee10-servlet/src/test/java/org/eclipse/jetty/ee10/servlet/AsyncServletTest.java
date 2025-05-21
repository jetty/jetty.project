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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.EventsHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncServletTest
{
    private final PathServlet _servlet = new PathServlet();
    private int _port;
    private final Server _server = new Server();
    private ServletHandler _servletHandler;
    private ErrorPageErrorHandler _errorHandler;
    private List<String> _log;
    private int _expectedLogs;
    private String _expectedCode;
    private final List<String> _history = new CopyOnWriteArrayList<>();
    private CountDownLatch _latch;

    private void historyAdd(String item)
    {
        // System.err.printf("%s history: %s%n", Thread.currentThread(), item);
        _history.add(item);
    }

    @BeforeEach
    public void setUp() throws Exception
    {
        ServerConnector connector = new ServerConnector(_server);
        _server.addConnector(connector);

        _log = new ArrayList<>();
        RequestLog log = new Log();
        _server.setRequestLog(log);
        _expectedLogs = 1;
        _expectedCode = "200 ";

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/ctx");
        _server.setHandler(new EventsHandler(context)
        {
            @Override
            protected void onComplete(Request request, int status, HttpFields headers, Throwable failure)
            {
                _latch.countDown();
            }
        });
        context.addEventListener(new DebugListener());

        _errorHandler = new ErrorPageErrorHandler();
        context.setErrorHandler(_errorHandler);
        _errorHandler.addErrorPage(300, 599, "/error/custom");

        _servletHandler = context.getServletHandler();
        ServletHolder holder = new ServletHolder(_servlet);
        holder.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder, "/error/*");
        _servletHandler.addServletWithMapping(holder, "/path/*");
        _servletHandler.addServletWithMapping(holder, "/path1/*");
        _servletHandler.addServletWithMapping(holder, "/path2/*");
        _servletHandler.addServletWithMapping(holder, "/p th3/*");
        _servletHandler.addServletWithMapping(new ServletHolder(new FwdServlet()), "/fwd/*");
        ServletHolder holder2 = new ServletHolder("NoAsync", _servlet);
        holder2.setAsyncSupported(false);
        _servletHandler.addServletWithMapping(holder2, "/noasync/*");
        _server.start();
        _port = connector.getLocalPort();
        _history.clear();
        _latch = new CountDownLatch(1);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
        assertEquals(_expectedLogs, _log.size());
        assertThat(_log.get(0), Matchers.containsString(_expectedCode));
    }

    @Test
    public void testNormal() throws Exception
    {
        String response = process(null, null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info",
            "initial",
            "REQUEST <<< PathServlet /ctx/path/info"));
        assertContains("NORMAL", response);
        assertFalse(_history.contains("onTimeout"));
        assertFalse(_history.contains("onComplete"));
    }

    @Test
    public void testSleep() throws Exception
    {
        String response = process("sleep=200", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?sleep=200",
            "initial",
            "REQUEST <<< PathServlet /ctx/path/info?sleep=200"));
        assertContains("SLEPT", response);
        assertFalse(_history.contains("onTimeout"));
        assertFalse(_history.contains("onComplete"));
    }

    @Test
    public void testAsyncNotSupportedNoAsync() throws Exception
    {
        _expectedCode = "200 ";
        String response = process("noasync", null, null);
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/noasync/info",
            "initial",
            "REQUEST <<< PathServlet /ctx/noasync/info"
        ));

        assertContains("NORMAL", response);
    }

    @Test
    public void testAsyncNotSupportedAsync() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(ServletChannel.class))
        {
            _expectedCode = "500 ";
            String response = process("noasync", "start=1000", null);
            assertThat(response, Matchers.startsWith("HTTP/1.1 500 "));
            assertThat(_history, contains(
                "REQUEST >>> PathServlet /ctx/noasync/info?start=1000",
                "initial",
                "PathServlet!!! java.lang.IllegalStateException: Async Not Supported",
                "REQUEST <<< PathServlet /ctx/noasync/info?start=1000",
                "ERROR >>> PathServlet /ctx/error/custom?start=1000",
                "wrapped REQ",
                "!initial",
                "ERROR <<< PathServlet /ctx/error/custom?start=1000"
            ));

            assertContains("500", response);
            assertContains("Async Not Supported", response);
        }
    }

    @Test
    public void testStart() throws Exception
    {
        _expectedCode = "500 ";
        String response = process("start=1000", null);
        assertThat(response, Matchers.startsWith("HTTP/1.1 500 Server Error"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=1000",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=1000",
            "onTimeout",
            "ERROR >>> PathServlet /ctx/error/custom?start=1000",
            "wrapped REQ",
            "!initial",
            "ERROR <<< PathServlet /ctx/error/custom?start=1000",
            "onComplete"));

        assertContains("ERROR DISPATCH: /ctx/error/custom", response);
    }

    @Test
    public void testStartOnTimeoutDispatch() throws Exception
    {
        String response = process("start=200&timeout=dispatch", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=200&timeout=dispatch",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=200&timeout=dispatch",
            "onTimeout",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/path/info?start=200&timeout=dispatch",
            "wrapped REQ",
            "!initial",
            "ASYNC <<< PathServlet /ctx/path/info?start=200&timeout=dispatch",
            "onComplete"));

        assertContains("DISPATCHED", response);
    }

    @Test
    public void testStartOnTimeoutError() throws Exception
    {
        _expectedCode = "500 ";
        String response = process("start=200&timeout=error", null);
        assertThat(response, startsWith("HTTP/1.1 500 Server Error"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=200&timeout=error",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=200&timeout=error",
            "onTimeout",
            "error",
            "ERROR >>> PathServlet /ctx/error/custom?start=200&timeout=error",
            "wrapped REQ",
            "!initial",
            "ERROR <<< PathServlet /ctx/error/custom?start=200&timeout=error",
            "onComplete"));

        assertContains("ERROR DISPATCH", response);
    }

    @Test
    public void testStartOnTimeoutComplete() throws Exception
    {
        String response = process("start=200&timeout=complete", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=200&timeout=complete",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=200&timeout=complete",
            "onTimeout",
            "complete",
            "onComplete"));

        assertContains("COMPLETED", response);
    }

    @Test
    public void testStartWaitDispatch() throws Exception
    {
        String response = process("start=1000&dispatch=100", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=1000&dispatch=100",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=1000&dispatch=100",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/path/info?start=1000&dispatch=100",
            "wrapped REQ",
            "!initial",
            "ASYNC <<< PathServlet /ctx/path/info?start=1000&dispatch=100",
            "onComplete"));
        assertFalse(_history.contains("onTimeout"));
    }

    @Test
    public void testStartDispatch() throws Exception
    {
        String response = process("start=1000&dispatch=0", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=1000&dispatch=0",
            "initial",
            "startAsync",
            "dispatch",
            "REQUEST <<< PathServlet /ctx/path/info?start=1000&dispatch=0",
            "ASYNC >>> PathServlet /ctx/path/info?start=1000&dispatch=0",
            "wrapped REQ",
            "!initial",
            "ASYNC <<< PathServlet /ctx/path/info?start=1000&dispatch=0",
            "onComplete"));
    }

    @Test
    public void testStartError() throws Exception
    {
        _expectedCode = "500 ";
        String response = process("start=1000&throw=true", null);
        assertThat(response, startsWith("HTTP/1.1 500 Server Error"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=1000&throw=true",
            "initial",
            "startAsync",
            "PathServlet!!! org.eclipse.jetty.ee10.servlet.QuietServletException: java.lang.Exception: test throw in async",
            "REQUEST <<< PathServlet /ctx/path/info?start=1000&throw=true",
            "onError",
            "ERROR >>> PathServlet /ctx/error/custom?start=1000&throw=true",
            "wrapped REQ",
            "!initial",
            "ERROR <<< PathServlet /ctx/error/custom?start=1000&throw=true",
            "onComplete"));
        assertContains("ERROR DISPATCH: /ctx/error/custom", response);
    }

    @Test
    public void testStartWaitComplete() throws Exception
    {
        String response = process("start=1000&complete=100", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=1000&complete=100",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=1000&complete=100",
            "complete",
            "onComplete"));
        assertContains("COMPLETED", response);
        assertFalse(_history.contains("onTimeout"));
        assertFalse(_history.contains("!initial"));
    }

    @Test
    public void testStartComplete() throws Exception
    {
        String response = process("start=1000&complete=0", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=1000&complete=0",
            "initial",
            "startAsync",
            "complete",
            "REQUEST <<< PathServlet /ctx/path/info?start=1000&complete=0",
            "onComplete"));
        assertContains("COMPLETED", response);
        assertFalse(_history.contains("onTimeout"));
        assertFalse(_history.contains("!initial"));
    }

    @Test
    public void testStartWaitDispatchStartWaitDispatch() throws Exception
    {
        String response = process("start=1000&dispatch=100&start2=1000&dispatch2=100", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=1000&dispatch=100&start2=1000&dispatch2=100",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=1000&dispatch=100&start2=1000&dispatch2=100",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/path/info?start=1000&dispatch=100&start2=1000&dispatch2=100",
            "wrapped REQ",
            "!initial",
            "onStartAsync",
            "startAsync",
            "ASYNC <<< PathServlet /ctx/path/info?start=1000&dispatch=100&start2=1000&dispatch2=100",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/path/info?start=1000&dispatch=100&start2=1000&dispatch2=100",
            "wrapped REQ",
            "!initial",
            "ASYNC <<< PathServlet /ctx/path/info?start=1000&dispatch=100&start2=1000&dispatch2=100",
            "onComplete"));
        assertContains("DISPATCHED", response);
    }

    @Test
    public void testStartWaitDispatchStartComplete() throws Exception
    {
        String response = process("start=1000&dispatch=100&start2=1000&complete2=100", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=1000&dispatch=100&start2=1000&complete2=100",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=1000&dispatch=100&start2=1000&complete2=100",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/path/info?start=1000&dispatch=100&start2=1000&complete2=100",
            "wrapped REQ",
            "!initial",
            "onStartAsync",
            "startAsync",
            "ASYNC <<< PathServlet /ctx/path/info?start=1000&dispatch=100&start2=1000&complete2=100",
            "complete",
            "onComplete"));
        assertContains("COMPLETED", response);
    }

    @Test
    public void testStartWaitDispatchStart() throws Exception
    {
        _expectedCode = "500 ";
        String response = process("start=1000&dispatch=100&start2=100", null);
        assertThat(response, startsWith("HTTP/1.1 500 Server Error"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=1000&dispatch=100&start2=100",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=1000&dispatch=100&start2=100",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/path/info?start=1000&dispatch=100&start2=100",
            "wrapped REQ",
            "!initial",
            "onStartAsync",
            "startAsync",
            "ASYNC <<< PathServlet /ctx/path/info?start=1000&dispatch=100&start2=100",
            "onTimeout",
            "ERROR >>> PathServlet /ctx/error/custom?start=1000&dispatch=100&start2=100",
            "wrapped REQ",
            "!initial",
            "ERROR <<< PathServlet /ctx/error/custom?start=1000&dispatch=100&start2=100",
            "onComplete"));
        assertContains("ERROR DISPATCH: /ctx/error/custom", response);
    }

    @Test
    public void testStartTimeoutStartDispatch() throws Exception
    {
        String response = process("start=100&start2=1000&dispatch2=100", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=100&start2=1000&dispatch2=100",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=100&start2=1000&dispatch2=100",
            "onTimeout",
            "ERROR >>> PathServlet /ctx/error/custom?start=100&start2=1000&dispatch2=100",
            "wrapped REQ",
            "!initial",
            "onStartAsync",
            "startAsync",
            "ERROR <<< PathServlet /ctx/error/custom?start=100&start2=1000&dispatch2=100",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/path/info?start=100&start2=1000&dispatch2=100",
            "wrapped REQ",
            "!initial",
            "ASYNC <<< PathServlet /ctx/path/info?start=100&start2=1000&dispatch2=100",
            "onComplete"));
        assertContains("DISPATCHED", response);
    }

    @Test
    public void testStartTimeoutStartComplete() throws Exception
    {
        String response = process("start=100&start2=1000&complete2=100", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=100&start2=1000&complete2=100",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=100&start2=1000&complete2=100",
            "onTimeout",
            "ERROR >>> PathServlet /ctx/error/custom?start=100&start2=1000&complete2=100",
            "wrapped REQ",
            "!initial",
            "onStartAsync",
            "startAsync",
            "ERROR <<< PathServlet /ctx/error/custom?start=100&start2=1000&complete2=100",
            "complete",
            "onComplete"));
        assertContains("COMPLETED", response);
    }

    @Test
    public void testStartTimeoutStart() throws Exception
    {
        _expectedCode = "500 ";
        _errorHandler.addErrorPage(500, "/path/error");

        String response = process("start=100&start2=100", null);
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=100&start2=100",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=100&start2=100",
            "onTimeout",
            "ERROR >>> PathServlet /ctx/path/error?start=100&start2=100",
            "wrapped REQ",
            "!initial",
            "onStartAsync",
            "startAsync",
            "ERROR <<< PathServlet /ctx/path/error?start=100&start2=100",
            "onTimeout",
            "ERROR >>> PathServlet /ctx/path/error?start=100&start2=100",
            "wrapped REQ",
            "!initial",
            "ERROR <<< PathServlet /ctx/path/error?start=100&start2=100",
            "onComplete")); // Error Page Loop!
        assertContains("AsyncContext timeout", response);
    }

    @Test
    public void testWrapStartDispatch() throws Exception
    {
        String response = process("wrap=true&start=1000&dispatch=100", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?wrap=true&start=1000&dispatch=100",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?wrap=true&start=1000&dispatch=100",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/path/info?wrap=true&start=1000&dispatch=100",
            "wrapped REQ RSP",
            "!initial",
            "ASYNC <<< PathServlet /ctx/path/info?wrap=true&start=1000&dispatch=100",
            "onComplete"));
        assertContains("DISPATCHED", response);
    }

    @Test
    public void testStartDispatchEncodedPath() throws Exception
    {
        String response = process("start=1000&dispatch=100&path=/p%20th3", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> PathServlet /ctx/path/info?start=1000&dispatch=100&path=/p%20th3",
            "initial",
            "startAsync",
            "REQUEST <<< PathServlet /ctx/path/info?start=1000&dispatch=100&path=/p%20th3",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/p%20th3?start=1000&dispatch=100&path=/p%20th3",
            "wrapped REQ",
            "!initial",
            "ASYNC <<< PathServlet /ctx/p%20th3?start=1000&dispatch=100&path=/p%20th3",
            "onComplete"));
        assertContains("DISPATCHED", response);
    }

    @Test
    public void testFwdStartDispatch() throws Exception
    {
        String response = process("fwd", "start=10000&dispatch=100", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        _history.forEach(System.out::println);
        assertThat(_history, contains(
            "REQUEST >>> FwdServlet /ctx/fwd/info?start=10000&dispatch=100",
            "FORWARD >>> PathServlet /ctx/path1?forward=true",
            "wrapped REQ",
            "initial",
            "startAsync",
            "FORWARD <<< PathServlet /ctx/path1?forward=true",
            "REQUEST <<< FwdServlet /ctx/fwd/info?start=10000&dispatch=100",
            "dispatch",
            "ASYNC >>> FwdServlet /ctx/fwd/info?start=10000&dispatch=100",
            "wrapped REQ",
            "FORWARD >>> PathServlet /ctx/path1?forward=true",
            "wrapped REQ",
            "!initial",
            "FORWARD <<< PathServlet /ctx/path1?forward=true",
            "ASYNC <<< FwdServlet /ctx/fwd/info?start=10000&dispatch=100",
            "onComplete"));
        assertContains("DISPATCHED", response);
    }

    @Test
    public void testFwdStartDispatchPath() throws Exception
    {
        String response = process("fwd", "start=10000&dispatch=100&path=/path2", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> FwdServlet /ctx/fwd/info?start=10000&dispatch=100&path=/path2",
            "FORWARD >>> PathServlet /ctx/path1?forward=true",
            "wrapped REQ",
            "initial",
            "startAsync",
            "FORWARD <<< PathServlet /ctx/path1?forward=true",
            "REQUEST <<< FwdServlet /ctx/fwd/info?start=10000&dispatch=100&path=/path2",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/path2?start=10000&dispatch=100&path=/path2",
            "wrapped REQ",
            "!initial",
            "ASYNC <<< PathServlet /ctx/path2?start=10000&dispatch=100&path=/path2",
            "onComplete"));
        assertContains("DISPATCHED", response);
    }

    @Test
    public void testFwdWrapStartDispatch() throws Exception
    {
        String response = process("fwd", "wrap=true&start=1000&dispatch=100", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        _history.forEach(System.out::println);
        assertThat(_history, contains(
            "REQUEST >>> FwdServlet /ctx/fwd/info?wrap=true&start=1000&dispatch=100",
            "FORWARD >>> PathServlet /ctx/path1?forward=true",
            "wrapped REQ",
            "initial",
            "startAsync",
            "FORWARD <<< PathServlet /ctx/path1?forward=true",
            "REQUEST <<< FwdServlet /ctx/fwd/info?wrap=true&start=1000&dispatch=100",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/path1?forward=true",
            "wrapped REQ RSP",
            "!initial",
            "ASYNC <<< PathServlet /ctx/path1?forward=true",
            "onComplete"));
        assertContains("DISPATCHED", response);
    }

    @Test
    public void testFwdWrapStartDispatchPath() throws Exception
    {
        String response = process("fwd", "wrap=true&start=1000&dispatch=100&path=/path2", null);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(_history, contains(
            "REQUEST >>> FwdServlet /ctx/fwd/info?wrap=true&start=1000&dispatch=100&path=/path2",
            "FORWARD >>> PathServlet /ctx/path1?forward=true",
            "wrapped REQ",
            "initial",
            "startAsync",
            "FORWARD <<< PathServlet /ctx/path1?forward=true",
            "REQUEST <<< FwdServlet /ctx/fwd/info?wrap=true&start=1000&dispatch=100&path=/path2",
            "dispatch",
            "ASYNC >>> PathServlet /ctx/path2?forward=true",
            "wrapped REQ RSP",
            "!initial",
            "ASYNC <<< PathServlet /ctx/path2?forward=true",
            "onComplete"));
        assertContains("DISPATCHED", response);
    }

    @Test
    public void testAsyncRead() throws Exception
    {
        String header = """
            GET /ctx/path/info?start=10000&dispatch=1500 HTTP/1.1\r
            Host: localhost\r
            Content-Length: 10\r
            Connection: close\r
            \r
            """;
        String body = "12345678\r\n";

        try (Socket socket = new Socket("localhost", _port))
        {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(header.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().write(body.getBytes(StandardCharsets.ISO_8859_1), 0, 2);
            Thread.sleep(500);
            socket.getOutputStream().write(body.getBytes(StandardCharsets.ISO_8859_1), 2, 8);

            String response = IO.toString(socket.getInputStream());
            assertTrue(_latch.await(1, TimeUnit.SECONDS));
            assertThat(response, startsWith("HTTP/1.1 200 OK"));
            assertThat(_history, contains(
                "REQUEST >>> PathServlet /ctx/path/info?start=10000&dispatch=1500",
                "initial",
                "startAsync",
                "REQUEST <<< PathServlet /ctx/path/info?start=10000&dispatch=1500",
                "async-read=10",
                "dispatch",
                "ASYNC >>> PathServlet /ctx/path/info?start=10000&dispatch=1500",
                "wrapped REQ",
                "!initial",
                "ASYNC <<< PathServlet /ctx/path/info?start=10000&dispatch=1500",
                "onComplete"));
        }
    }

    public static Stream<Arguments> forwardAsyncDispatchArgs()
    {
        return Stream.of(
            Arguments.of(false, false, "name=orig&one=1", "orig"),
            Arguments.of(false, true, "name=async&three=3", "async, orig"),
            Arguments.of(true, false, "name=forward&two=2", "forward, orig"),
            Arguments.of(true, true, "name=async&three=3", "async, forward, orig")
        );
    }

    @ParameterizedTest
    @MethodSource("forwardAsyncDispatchArgs")
    public void testForwardAsyncDispatch(boolean startWithRequest, boolean dispatchTarget, String expectedQuery, String expectedName) throws Exception
    {
        _servletHandler.addServletWithMapping(
            new ServletHolder(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
                {
                    historyAdd(request.getDispatcherType() + " >>> TestServletA " + URIUtil.addPathQuery(request.getRequestURI(), request.getQueryString()));
                    historyAdd("name=" + Arrays.asList(request.getParameterValues("name")));

                    if (request.getAttribute("FWD") == null)
                    {
                        request.setAttribute("FWD", "OK");
                        request.getServletContext().getRequestDispatcher("/target?name=forward&two=2").forward(request, response);
                    }
                    historyAdd(request.getDispatcherType() + " <<< TestServletA " + URIUtil.addPathQuery(request.getRequestURI(), request.getQueryString()));
                }
            }),
            "/forwarder/*"
        );

        _servletHandler.addServletWithMapping(
            new ServletHolder(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response)
                {
                    historyAdd(request.getDispatcherType() + " >>> TestServletB " + URIUtil.addPathQuery(request.getRequestURI(), request.getQueryString()));
                    historyAdd("name=" + Arrays.asList(request.getParameterValues("name")));

                    if (request.getAttribute("TEST") == null)
                    {
                        request.setAttribute("TEST", "OK");
                        AsyncContext asyncContext = startWithRequest
                            ? request.startAsync(request, response)
                            : request.startAsync();
                        if (dispatchTarget)
                            asyncContext.dispatch("/target?name=async&three=3");
                        else
                            asyncContext.dispatch();
                    }
                    historyAdd(request.getDispatcherType() + " <<< TestServletB " + URIUtil.addPathQuery(request.getRequestURI(), request.getQueryString()));
                }
            }),
            "/target/*"
        );

        String response = process("forwarder", "name=orig&one=1", null);
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));

        _history.forEach(System.err::println);

        assertThat(_history, contains(
            "REQUEST >>> TestServletA /ctx/forwarder/info?name=orig&one=1",
            "name=[orig]",
            "FORWARD >>> TestServletB /ctx/target?name=forward&two=2",
            "name=[forward, orig]",
            "FORWARD <<< TestServletB /ctx/target?name=forward&two=2",
            "REQUEST <<< TestServletA /ctx/forwarder/info?name=orig&one=1",
            (!startWithRequest && !dispatchTarget ? "ASYNC >>> TestServletA /ctx/forwarder/info?" : "ASYNC >>> TestServletB /ctx/target?") + expectedQuery,
            "name=[" + expectedName + "]",
            (!startWithRequest && !dispatchTarget ? "ASYNC <<< TestServletA /ctx/forwarder/info?" : "ASYNC <<< TestServletB /ctx/target?") + expectedQuery
            ));
    }

    public synchronized String process(String query, String content) throws Exception
    {
        return process("path", query, content);
    }

    public synchronized String process(String path, String query, String content) throws Exception
    {
        String request = "GET /ctx/" + path + "/info";

        if (query != null)
            request += "?" + query;
        request += """
             HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            """;
        if (content == null)
            request += "\r\n";
        else
        {
            request += "Content-Length: " + content.length() + "\r\n";
            request += "\r\n" + content;
        }

        int port = _port;
        try (Socket socket = new Socket("localhost", port))
        {
            socket.setSoTimeout(1000000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String response = IO.toString(socket.getInputStream());
            assertTrue(_latch.await(5, TimeUnit.SECONDS));
            return response;
        }
        catch (Exception e)
        {
            System.err.println("failed on port " + port);
            e.printStackTrace();
            throw e;
        }
    }

    protected void assertContains(String content, String response)
    {
        assertThat(response, Matchers.containsString(content));
    }

    private class FwdServlet extends HttpServlet
    {
        @Override
        public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getDispatcherType() + " >>> FwdServlet " + URIUtil.addPathQuery(request.getRequestURI(), request.getQueryString());
            try
            {
                historyAdd(action);

                if (request instanceof ServletRequestWrapper || response instanceof ServletResponseWrapper)
                    historyAdd("wrapped" + ((request instanceof ServletRequestWrapper) ? " REQ" : "") + ((response instanceof ServletResponseWrapper) ? " RSP" : ""));
                request.getServletContext().getRequestDispatcher("/path1?forward=true").forward(request, response);
            }
            catch (Throwable t)
            {
                historyAdd("FwdServlet!!! " + t);
                throw t;
            }
            finally
            {
                historyAdd(action.replace(" >>> ", " <<< "));
            }
        }
    }

    private class PathServlet extends HttpServlet
    {
        private final ScheduledExecutorService _scheduler = Executors.newSingleThreadScheduledExecutor();

        @Override
        public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getDispatcherType() + " >>> PathServlet " + URIUtil.addPathQuery(request.getRequestURI(), request.getQueryString());
            try
            {
                historyAdd(action);

                // this should always fail at this point
                try
                {
                    request.getAsyncContext();
                    throw new IllegalStateException();
                }
                catch (IllegalStateException e)
                {
                    // ignored
                }

                if (request instanceof ServletRequestWrapper || response instanceof ServletResponseWrapper)
                    historyAdd("wrapped" + ((request instanceof ServletRequestWrapper) ? " REQ" : "") + ((response instanceof ServletResponseWrapper) ? " RSP" : ""));

                boolean wrap = "true".equals(request.getParameter("wrap"));
                int readBefore = 0;
                long sleepFor = -1;
                long startFor = -1;
                long start2For = -1;
                long dispatchAfter = -1;
                long dispatch2After = -1;
                long completeAfter = -1;
                long complete2After = -1;

                if (request.getParameter("read") != null)
                    readBefore = Integer.parseInt(request.getParameter("read"));
                if (request.getParameter("sleep") != null)
                    sleepFor = Integer.parseInt(request.getParameter("sleep"));
                if (request.getParameter("start") != null)
                    startFor = Integer.parseInt(request.getParameter("start"));
                if (request.getParameter("start2") != null)
                    start2For = Integer.parseInt(request.getParameter("start2"));
                if (request.getParameter("dispatch") != null)
                    dispatchAfter = Integer.parseInt(request.getParameter("dispatch"));
                final String path = request.getParameter("path");
                if (request.getParameter("dispatch2") != null)
                    dispatch2After = Integer.parseInt(request.getParameter("dispatch2"));
                if (request.getParameter("complete") != null)
                    completeAfter = Integer.parseInt(request.getParameter("complete"));
                if (request.getParameter("complete2") != null)
                    complete2After = Integer.parseInt(request.getParameter("complete2"));

                if (request.getAttribute("State") == null)
                {
                    request.setAttribute("State", 1);
                    historyAdd("initial");
                    if (readBefore > 0)
                    {
                        byte[] buf = new byte[readBefore];
                        request.getInputStream().read(buf);
                    }
                    else if (readBefore < 0)
                    {
                        InputStream in = request.getInputStream();
                        int b = in.read();
                        while (b != -1)
                        {
                            b = in.read();
                        }
                    }
                    else if (request.getContentLength() > 0)
                    {
                        new Thread(() ->
                        {
                            int c = 0;
                            try
                            {
                                InputStream in = request.getInputStream();
                                int b = 0;
                                while (b != -1)
                                {
                                    if ((b = in.read()) >= 0)
                                        c++;
                                }
                                historyAdd("async-read=" + c);
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }).start();
                    }

                    if (startFor >= 0)
                    {
                        final AsyncContext async = wrap ? request.startAsync(new HttpServletRequestWrapper(request), new HttpServletResponseWrapper(response)) : request.startAsync();
                        historyAdd("startAsync");
                        if (startFor > 0)
                            async.setTimeout(startFor);
                        async.addListener(_listener);

                        if ("true".equals(request.getParameter("throw")))
                            throw new QuietServletException(new Exception("test throw in async"));

                        if (completeAfter > 0)
                        {
                            _scheduler.schedule(() ->
                            {
                                try
                                {
                                    response.setStatus(200);
                                    response.getOutputStream().println("COMPLETED\n");
                                    historyAdd("complete");
                                    async.complete();
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }, completeAfter, TimeUnit.MILLISECONDS);
                        }
                        else if (completeAfter == 0)
                        {
                            response.setStatus(200);
                            response.getOutputStream().println("COMPLETED\n");
                            historyAdd("complete");
                            async.complete();
                        }
                        else if (dispatchAfter > 0)
                        {
                            _scheduler.schedule(() ->
                            {
                                historyAdd("dispatch");
                                if (path != null)
                                {
                                    int q = path.indexOf('?');
                                    String uriInContext = (q >= 0)
                                        ? URIUtil.encodePath(path.substring(0, q)) + path.substring(q)
                                        : URIUtil.encodePath(path);
                                    async.dispatch(uriInContext);
                                }
                                else
                                    async.dispatch();
                            }, dispatchAfter, TimeUnit.MILLISECONDS);
                        }
                        else if (dispatchAfter == 0)
                        {
                            historyAdd("dispatch");
                            if (path != null)
                                async.dispatch(path);
                            else
                                async.dispatch();
                        }
                    }
                    else if (sleepFor >= 0)
                    {
                        try
                        {
                            Thread.sleep(sleepFor);
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                        response.setStatus(200);
                        response.getOutputStream().println("SLEPT\n");
                    }
                    else
                    {
                        response.setStatus(200);
                        response.getOutputStream().println("NORMAL\n");
                    }
                }
                else
                {
                    historyAdd("!initial");

                    if (start2For >= 0 && request.getAttribute("2nd") == null)
                    {
                        final AsyncContext async = wrap ? request.startAsync(new HttpServletRequestWrapper(request), new HttpServletResponseWrapper(response)) : request.startAsync();
                        historyAdd("startAsync");
                        async.addListener(_listener);
                        request.setAttribute("2nd", "cycle");

                        if (start2For > 0)
                        {
                            async.setTimeout(start2For);
                        }

                        if (complete2After > 0)
                        {
                            _scheduler.schedule(() ->
                            {
                                try
                                {
                                    response.setStatus(200);
                                    response.getOutputStream().println("COMPLETED\n");
                                    historyAdd("complete");
                                    async.complete();
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }, complete2After, TimeUnit.MILLISECONDS);
                        }
                        else if (complete2After == 0)
                        {
                            response.setStatus(200);
                            response.getOutputStream().println("COMPLETED\n");
                            historyAdd("complete");
                            async.complete();
                        }
                        else if (dispatch2After > 0)
                        {
                            _scheduler.schedule(() ->
                            {
                                historyAdd("dispatch");
                                async.dispatch();
                            }, dispatch2After, TimeUnit.MILLISECONDS);
                        }
                        else if (dispatch2After == 0)
                        {
                            historyAdd("dispatch");
                            async.dispatch();
                        }
                    }
                    else if (request.getDispatcherType() == DispatcherType.ERROR)
                    {
                        response.getOutputStream().println("ERROR DISPATCH: " + request.getContextPath() + request.getServletPath() + request.getPathInfo());
                        response.getOutputStream().println("" + request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
                        response.getOutputStream().println("" + request.getAttribute(RequestDispatcher.ERROR_MESSAGE));
                    }
                    else
                    {
                        response.setStatus(200);
                        response.getOutputStream().println("DISPATCHED");
                    }
                }
            }
            catch (Throwable t)
            {
                historyAdd("PathServlet!!! " + t);
                throw t;
            }
            finally
            {
                historyAdd(action.replace(">>>", "<<<"));
            }
        }
    }

    private final AsyncListener _listener = new AsyncListener()
    {
        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            historyAdd("onTimeout");
            String action = event.getSuppliedRequest().getParameter("timeout");
            if (action != null)
            {
                historyAdd(action);

                switch (action)
                {
                    case "dispatch" -> event.getAsyncContext().dispatch();
                    case "complete" ->
                    {
                        event.getSuppliedResponse().getOutputStream().println("COMPLETED\n");
                        event.getAsyncContext().complete();
                    }
                    case "error" -> throw new RuntimeException("error in onTimeout");
                }
            }
        }

        @Override
        public void onStartAsync(AsyncEvent event)
        {
            historyAdd("onStartAsync");
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            historyAdd("onError");
            String action = event.getSuppliedRequest().getParameter("error");
            if (action != null)
            {
                historyAdd(action);

                switch (action)
                {
                    case "dispatch" -> event.getAsyncContext().dispatch();
                    case "complete" ->
                    {
                        event.getSuppliedResponse().getOutputStream().println("COMPLETED\n");
                        event.getAsyncContext().complete();
                    }
                }
            }
        }

        @Override
        public void onComplete(AsyncEvent event)
        {
            historyAdd("onComplete");
        }
    };

    class Log extends AbstractLifeCycle implements RequestLog
    {
        @Override
        public void log(Request request, Response response)
        {
            int status = response.getStatus();
            long written = Response.getContentBytesWritten(response);
            _log.add(status + " " + written + " " + request.getHttpURI());
        }
    }
}
