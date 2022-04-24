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

package org.eclipse.jetty.ee10.servlets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.FileSessionDataStore;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class AbstractDoSFilterTest
{
    private Server _server;
    private ServerConnector _connector;
    protected long _requestMaxTime = 200;

    public void startServer(WorkDir workDir, Class<? extends Filter> filter) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        ServletContextHandler context = new ServletContextHandler(_server, "/ctx", true, false);

        DefaultSessionCache sessionCache = new DefaultSessionCache(context.getSessionHandler());
        FileSessionDataStore fileStore = new FileSessionDataStore();

        Path p = workDir.getPathFile("sessions");
        FS.ensureEmpty(p);
        fileStore.setStoreDir(p.toFile());
        sessionCache.setSessionDataStore(fileStore);

        context.getSessionHandler().setSessionCache(sessionCache);

        context.addServlet(TestServlet.class, "/*");

        FilterHolder dosFilter = context.addFilter(filter, "/dos/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        dosFilter.setInitParameter("maxRequestsPerSec", "4");
        dosFilter.setInitParameter("delayMs", "200");
        dosFilter.setInitParameter("throttledRequests", "1");
        dosFilter.setInitParameter("waitMs", "10");
        dosFilter.setInitParameter("throttleMs", "4000");
        dosFilter.setInitParameter("remotePort", "false");
        dosFilter.setInitParameter("insertHeaders", "true");

        FilterHolder timeoutFilter = context.addFilter(filter, "/timeout/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        timeoutFilter.setInitParameter("maxRequestsPerSec", "4");
        timeoutFilter.setInitParameter("delayMs", "200");
        timeoutFilter.setInitParameter("throttledRequests", "1");
        timeoutFilter.setInitParameter("waitMs", "10");
        timeoutFilter.setInitParameter("throttleMs", "4000");
        timeoutFilter.setInitParameter("remotePort", "false");
        timeoutFilter.setInitParameter("insertHeaders", "true");
        timeoutFilter.setInitParameter("maxRequestMs", _requestMaxTime + "");

        _server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        LifeCycle.stop(_server);
    }

    protected String doRequests(String loopRequests, int loops, long pauseBetweenLoops, long pauseBeforeLast, String lastRequest) throws Exception
    {
        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            socket.setSoTimeout(30000);

            OutputStream out = socket.getOutputStream();

            for (int i = loops; i-- > 0; )
            {
                out.write(loopRequests.getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (i > 0 && pauseBetweenLoops > 0)
                {
                    Thread.sleep(pauseBetweenLoops);
                }
            }
            if (pauseBeforeLast > 0)
            {
                Thread.sleep(pauseBeforeLast);
            }
            out.write(lastRequest.getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            if (loopRequests.contains("/unresponsive"))
            {
                // don't read in anything, forcing the request to time out
                Thread.sleep(_requestMaxTime * 2);
            }
            return IO.toString(in, StandardCharsets.UTF_8);
        }
    }

    private int count(String responses, String substring)
    {
        int count = 0;
        int i = responses.indexOf(substring);
        while (i >= 0)
        {
            count++;
            i = responses.indexOf(substring, i + substring.length());
        }

        return count;
    }

    @Test
    public void testEvenLowRateIP() throws Exception
    {
        String request = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String last = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests(request, 11, 300, 300, last);
        assertEquals(12, count(responses, "HTTP/1.1 200 OK"));
        assertEquals(0, count(responses, "DoSFilter:"));
    }

    @Test
    public void testBurstLowRateIP() throws Exception
    {
        String request = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String last = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests(request + request + request + request, 2, 1100, 1100, last);

        assertEquals(9, count(responses, "HTTP/1.1 200 OK"));
        assertEquals(0, count(responses, "DoSFilter:"));
    }

    @Test
    public void testDelayedIP() throws Exception
    {
        String request = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String last = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests(request + request + request + request + request, 2, 1100, 1100, last);

        assertThat(count(responses, "DoSFilter: delayed"), greaterThanOrEqualTo(2));
        assertThat(count(responses, "HTTP/1.1 200 OK"), is(11));
    }

    @Test
    public void testThrottledIP() throws Exception
    {
        Thread other = new Thread(() ->
        {
            try
            {
                // Cause a delay, then sleep while holding pass
                String request = "GET /ctx/dos/sleeper HTTP/1.1\r\nHost: localhost\r\n\r\n";
                String last = "GET /ctx/dos/sleeper?sleep=2000 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
                doRequests(request + request + request + request, 1, 0, 0, last);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });
        other.start();
        Thread.sleep(1500);

        String request = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String last = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests(request + request + request + request, 1, 0, 0, last);
        // System.out.println("responses are " + responses);
        assertEquals(5, count(responses, "HTTP/1.1 200 OK"), "200 OK responses");
        assertEquals(1, count(responses, "DoSFilter: delayed"), "delayed responses");
        assertEquals(1, count(responses, "DoSFilter: throttled"), "throttled responses");
        assertEquals(0, count(responses, "DoSFilter: unavailable"), "unavailable responses");

        other.join();
    }

    @Test
    @Disabled // TODO
    public void testUnavailableIP() throws Exception
    {
        Thread other = new Thread(() ->
        {
            try
            {
                // Cause a delay, then sleep while holding pass
                String request = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
                String last = "GET /ctx/dos/test?sleep=5000 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
                doRequests(request + request + request + request, 1, 0, 0, last);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });
        other.start();
        Thread.sleep(500);

        String request = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String last = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests(request + request + request + request, 1, 0, 0, last);

        // System.err.println("RESPONSES: \n"+responses);

        assertEquals(4, count(responses, "HTTP/1.1 200 OK"));
        assertEquals(1, count(responses, "HTTP/1.1 429"));
        assertEquals(1, count(responses, "DoSFilter: delayed"));
        assertEquals(1, count(responses, "DoSFilter: throttled"));
        assertEquals(1, count(responses, "DoSFilter: unavailable"));

        other.join();
    }

    @Test
    public void testSessionTracking() throws Exception
    {
        // get a session, first
        String requestSession = "GET /ctx/dos/test?session=true HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String response = doRequests("", 1, 0, 0, requestSession);
        String sessionId = response.substring(response.indexOf("Set-Cookie: ") + 12, response.indexOf(";"));

        // all other requests use this session
        String request = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId + "\r\n\r\n";
        String last = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nCookie: " + sessionId + "\r\n\r\n";
        String responses = doRequests(request + request + request + request + request, 2, 1100, 1100, last);

        assertEquals(11, count(responses, "HTTP/1.1 200 OK"));
        assertEquals(2, count(responses, "DoSFilter: delayed"));
    }

    @Test
    @Disabled // TODO
    public void testMultipleSessionTracking() throws Exception
    {
        // get some session ids, first
        String requestSession = "GET /ctx/dos/test?session=true HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String closeRequest = "GET /ctx/dos/test?session=true HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String response = doRequests(requestSession + requestSession, 1, 0, 0, closeRequest);

        String[] sessions = response.split("\r\n\r\n");

        String sessionId1 = sessions[0].substring(sessions[0].indexOf("Set-Cookie: ") + 12, sessions[0].indexOf(";"));
        String sessionId2 = sessions[1].substring(sessions[1].indexOf("Set-Cookie: ") + 12, sessions[1].indexOf(";"));

        // alternate between sessions
        String request1 = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId1 + "\r\n\r\n";
        String request2 = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId2 + "\r\n\r\n";
        String last = "GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nCookie: " + sessionId2 + "\r\n\r\n";

        // ensure the sessions are new
        doRequests(request1 + request2, 1, 1100, 1100, last);
        Thread.sleep(1000);

        String responses = doRequests(request1 + request2 + request1 + request2 + request1, 2, 1100, 1100, last);

        assertEquals(11, count(responses, "HTTP/1.1 200 OK"));
        // This test is system speed dependent, so allow some (20%-ish) requests to be delayed, but not more.
        assertThat("delayed count", count(responses, "DoSFilter: delayed"), lessThan(2));

        // alternate between sessions
        responses = doRequests(request1 + request2 + request1 + request2 + request1, 2, 250, 250, last);

        // System.err.println(responses);
        assertEquals(11, count(responses, "HTTP/1.1 200 OK"));
        int delayedRequests = count(responses, "DoSFilter: delayed");
        assertTrue(delayedRequests >= 2 && delayedRequests <= 5, "delayedRequests: " + delayedRequests + " is not between 2 and 5");
    }

    @Test
    @Disabled // TODO
    public void testUnresponsiveClient() throws Exception
    {
        int numRequests = 1000;

        String last = "GET /ctx/timeout/unresponsive?lines=" + numRequests + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests("", 0, 0, 0, last);
        // was expired, and stopped before reaching the end of the requests
        int responseLines = count(responses, "Line:");
        assertThat(responseLines, Matchers.greaterThan(0));
        assertThat(responseLines, Matchers.lessThan(numRequests));
    }

    public static class TestServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getParameter("session") != null)
                request.getSession(true);
            if (request.getParameter("sleep") != null)
            {
                try
                {
                    Thread.sleep(Long.parseLong(request.getParameter("sleep")));
                }
                catch (InterruptedException e)
                {
                    // no op
                }
            }

            if (request.getParameter("lines") != null)
            {
                int count = Integer.parseInt(request.getParameter("lines"));
                for (int i = 0; i < count; ++i)
                {
                    response.getWriter().append("Line: ").append(String.valueOf(i)).append("\n");
                    response.flushBuffer();

                    try
                    {
                        Thread.sleep(10);
                    }
                    catch (InterruptedException e)
                    {
                        // no op
                    }
                }
            }

            response.setContentType("text/plain");
        }
    }
}
