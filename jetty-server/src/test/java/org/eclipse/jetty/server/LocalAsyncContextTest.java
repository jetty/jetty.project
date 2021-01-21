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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.session.SessionHandler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LocalAsyncContextTest
{
    public static final Logger LOG = LoggerFactory.getLogger(LocalAsyncContextTest.class);
    protected Server _server;
    protected SuspendHandler _handler;
    protected Connector _connector;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = initConnector();
        _server.addConnector(_connector);

        SessionHandler session = new SessionHandler();
        _handler = new SuspendHandler();
        session.setHandler(_handler);

        _server.setHandler(session);
        _server.start();

        reset();
    }

    public void reset()
    {
    }

    protected Connector initConnector()
    {
        return new LocalConnector(_server);
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testSuspendTimeout() throws Exception
    {
        String response;
        _handler.setRead(0);
        _handler.setSuspendFor(1000);
        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(-1);
        response = process(null);
        check(response, "TIMEOUT");
    }

    @Test
    public void testSuspendResume0() throws Exception
    {
        String response;
        _handler.setRead(0);
        _handler.setSuspendFor(10000);
        _handler.setResumeAfter(0);
        _handler.setCompleteAfter(-1);
        response = process(null);
        check(response, "STARTASYNC", "DISPATCHED");
    }

    @Test
    public void testSuspendResume100() throws Exception
    {
        String response;
        _handler.setRead(0);
        _handler.setSuspendFor(10000);
        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        response = process(null);
        check(response, "STARTASYNC", "DISPATCHED");
    }

    @Test
    public void testSuspendComplete0() throws Exception
    {
        String response;
        _handler.setRead(0);
        _handler.setSuspendFor(10000);
        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(0);
        response = process(null);
        check(response, "STARTASYNC", "COMPLETED");
    }

    @Test
    public void testSuspendComplete200() throws Exception
    {
        String response;
        _handler.setRead(0);
        _handler.setSuspendFor(10000);
        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(200);
        response = process(null);
        check(response, "STARTASYNC", "COMPLETED");
    }

    @Test
    public void testSuspendReadResume0() throws Exception
    {
        String response;
        _handler.setSuspendFor(10000);
        _handler.setRead(-1);
        _handler.setResumeAfter(0);
        _handler.setCompleteAfter(-1);
        response = process("wibble");
        check(response, "STARTASYNC", "DISPATCHED");
    }

    @Test
    public void testSuspendReadResume100() throws Exception
    {
        String response;
        _handler.setSuspendFor(10000);
        _handler.setRead(-1);
        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        response = process("wibble");
        check(response, "DISPATCHED");
    }

    @Test
    public void testSuspendOther() throws Exception
    {
        String response;
        _handler.setSuspendFor(10000);
        _handler.setRead(-1);
        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(0);
        response = process("wibble");
        check(response, "COMPLETED");

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(100);
        response = process("wibble");
        check(response, "COMPLETED");

        _handler.setRead(6);
        _handler.setResumeAfter(0);
        _handler.setCompleteAfter(-1);
        response = process("wibble");
        check(response, "DISPATCHED");

        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        response = process("wibble");

        check(response, "DISPATCHED");

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(0);
        response = process("wibble");
        check(response, "COMPLETED");

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(100);
        response = process("wibble");
        check(response, "COMPLETED");
    }

    @Test
    public void testTwoCycles() throws Exception
    {
        String response;

        _handler.setRead(0);
        _handler.setSuspendFor(1000);
        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        _handler.setSuspendFor2(1000);
        _handler.setResumeAfter2(200);
        _handler.setCompleteAfter2(-1);
        response = process(null);
        check(response, "STARTASYNC", "DISPATCHED", "startasync", "STARTASYNC2", "DISPATCHED");
    }

    protected void check(String response, String... content)
    {
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));
        int i = 0;
        for (String m : content)
        {
            assertThat(response, Matchers.containsString(m));
            i = response.indexOf(m, i);
            i += m.length();
        }
    }

    private synchronized String process(String content) throws Exception
    {
        LOG.debug("TEST process: {}", content);
        reset();
        String request = "GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n";

        if (content == null)
            request += "\r\n";
        else
            request += "Content-Length: " + content.length() + "\r\n" + "\r\n" + content;

        return getResponse(request);
    }

    protected String getResponse(String request) throws Exception
    {
        LocalConnector connector = (LocalConnector)_connector;
        LocalConnector.LocalEndPoint endp = connector.executeRequest(request);
        endp.waitUntilClosed();
        return endp.takeOutputString();
    }

    private class SuspendHandler extends HandlerWrapper
    {
        private int _read;
        private long _suspendFor = -1;
        private long _resumeAfter = -1;
        private long _completeAfter = -1;
        private long _suspendFor2 = -1;
        private long _resumeAfter2 = -1;
        private long _completeAfter2 = -1;

        public SuspendHandler()
        {
        }

        public void setRead(int read)
        {
            _read = read;
        }

        public void setSuspendFor(long suspendFor)
        {
            _suspendFor = suspendFor;
        }

        public void setResumeAfter(long resumeAfter)
        {
            _resumeAfter = resumeAfter;
        }

        public void setCompleteAfter(long completeAfter)
        {
            _completeAfter = completeAfter;
        }

        public void setSuspendFor2(long suspendFor2)
        {
            _suspendFor2 = suspendFor2;
        }

        public void setResumeAfter2(long resumeAfter2)
        {
            _resumeAfter2 = resumeAfter2;
        }

        public void setCompleteAfter2(long completeAfter2)
        {
            _completeAfter2 = completeAfter2;
        }

        @Override
        public void handle(String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException
        {
            LOG.debug("handle {} {}", baseRequest.getDispatcherType(), baseRequest);
            if (DispatcherType.REQUEST.equals(baseRequest.getDispatcherType()))
            {
                if (_read > 0)
                {
                    int read = _read;
                    byte[] buf = new byte[read];
                    while (read > 0)
                    {
                        read -= request.getInputStream().read(buf);
                    }
                }
                else if (_read < 0)
                {
                    InputStream in = request.getInputStream();
                    int b = in.read();
                    while (b != -1)
                    {
                        b = in.read();
                    }
                }

                final AsyncContext asyncContext = baseRequest.startAsync();
                response.getOutputStream().println("STARTASYNC");
                asyncContext.addListener(_asyncListener);
                if (_suspendFor > 0)
                    asyncContext.setTimeout(_suspendFor);

                if (_completeAfter > 0)
                {
                    new Thread()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                Thread.sleep(_completeAfter);
                                response.getOutputStream().println("COMPLETED");
                                response.setStatus(200);
                                baseRequest.setHandled(true);
                                asyncContext.complete();
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    }.start();
                }
                else if (_completeAfter == 0)
                {
                    response.getOutputStream().println("COMPLETED");
                    response.setStatus(200);
                    baseRequest.setHandled(true);
                    asyncContext.complete();
                }

                if (_resumeAfter > 0)
                {
                    new Thread()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                Thread.sleep(_resumeAfter);
                                if (((HttpServletRequest)asyncContext.getRequest()).getSession(true).getId() != null)
                                    asyncContext.dispatch();
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    }.start();
                }
                else if (_resumeAfter == 0)
                {
                    asyncContext.dispatch();
                }
            }
            else
            {
                if (request.getAttribute("TIMEOUT") != null)
                    response.getOutputStream().println("TIMEOUT");
                else
                    response.getOutputStream().println("DISPATCHED");

                if (_suspendFor2 >= 0)
                {
                    final AsyncContext asyncContext = baseRequest.startAsync();
                    response.getOutputStream().println("STARTASYNC2");
                    if (_suspendFor2 > 0)
                        asyncContext.setTimeout(_suspendFor2);
                    _suspendFor2 = -1;

                    if (_completeAfter2 > 0)
                    {
                        new Thread()
                        {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    Thread.sleep(_completeAfter2);
                                    response.getOutputStream().println("COMPLETED2");
                                    response.setStatus(200);
                                    baseRequest.setHandled(true);
                                    asyncContext.complete();
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                    }
                    else if (_completeAfter2 == 0)
                    {
                        response.getOutputStream().println("COMPLETED2");
                        response.setStatus(200);
                        baseRequest.setHandled(true);
                        asyncContext.complete();
                    }

                    if (_resumeAfter2 > 0)
                    {
                        new Thread()
                        {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    Thread.sleep(_resumeAfter2);
                                    asyncContext.dispatch();
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                    }
                    else if (_resumeAfter2 == 0)
                    {
                        asyncContext.dispatch();
                    }
                }
                else
                {
                    response.setStatus(200);
                    baseRequest.setHandled(true);
                }
            }
        }
    }

    private AsyncListener _asyncListener = new AsyncListener()
    {
        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
            event.getSuppliedResponse().getOutputStream().println("startasync");
            event.getAsyncContext().addListener(this);
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            event.getSuppliedRequest().setAttribute("TIMEOUT", Boolean.TRUE);
            event.getAsyncContext().dispatch();
        }
    };

    static <T> void spinAssertEquals(T expected, Supplier<T> actualSupplier)
    {
        spinAssertEquals(expected, actualSupplier, 10, TimeUnit.SECONDS);
    }

    static <T> void spinAssertEquals(T expected, Supplier<T> actualSupplier, long waitFor, TimeUnit units)
    {
        long now = System.nanoTime();
        long end = now + units.toNanos(waitFor);
        T actual = null;
        while (now < end)
        {
            actual = actualSupplier.get();
            if (actual == null && expected == null ||
                actual != null && actual.equals(expected))
                break;
            try
            {
                Thread.sleep(10);
            }
            catch (InterruptedException e)
            {
                // Ignored
            }
            now = System.nanoTime();
        }

        assertEquals(expected, actual);
    }
}
