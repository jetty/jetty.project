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
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.NanoTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ThreadLimitHandlerTest
{
    private Server _server;
    private NetworkConnector _connector;
    private LocalConnector _local;

    @BeforeEach
    public void before()
        throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _local = new LocalConnector(_server);
        _server.setConnectors(new Connector[]{_local, _connector});
    }

    @AfterEach
    public void after()
        throws Exception
    {
        _server.stop();
    }

    @Test
    public void testNoForwardHeaders() throws Exception
    {
        AtomicReference<String> last = new AtomicReference<>();
        ThreadLimitHandler handler = new ThreadLimitHandler(null, false)
        {
            @Override
            protected int getThreadLimit(String ip)
            {
                last.set(ip);
                return super.getThreadLimit(ip);
            }
        };
        handler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(HttpStatus.OK_200);
            }
        });
        _server.setHandler(handler);
        _server.start();

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(last.get(), Matchers.is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.2.3.4\r\n\r\n");
        assertThat(last.get(), Matchers.is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n");
        assertThat(last.get(), Matchers.is("0.0.0.0"));
    }

    @Test
    public void testXForwardForHeaders() throws Exception
    {
        AtomicReference<String> last = new AtomicReference<>();
        ThreadLimitHandler handler = new ThreadLimitHandler("X-Forwarded-For")
        {
            @Override
            protected int getThreadLimit(String ip)
            {
                last.set(ip);
                return super.getThreadLimit(ip);
            }
        };
        _server.setHandler(handler);
        _server.start();

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(last.get(), Matchers.is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.2.3.4\r\n\r\n");
        assertThat(last.get(), Matchers.is("1.2.3.4"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n");
        assertThat(last.get(), Matchers.is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.1.1.1\r\nX-Forwarded-For: 6.6.6.6,1.2.3.4\r\nForwarded: for=1.2.3.4\r\n\r\n");
        assertThat(last.get(), Matchers.is("1.2.3.4"));
    }

    @Test
    public void testForwardHeaders() throws Exception
    {
        AtomicReference<String> last = new AtomicReference<>();
        ThreadLimitHandler handler = new ThreadLimitHandler("Forwarded")
        {
            @Override
            protected int getThreadLimit(String ip)
            {
                last.set(ip);
                return super.getThreadLimit(ip);
            }
        };
        _server.setHandler(handler);
        _server.start();

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(last.get(), Matchers.is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.2.3.4\r\n\r\n");
        assertThat(last.get(), Matchers.is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n");
        assertThat(last.get(), Matchers.is("1.2.3.4"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.1.1.1\r\nForwarded: for=6.6.6.6; for=1.2.3.4\r\nX-Forwarded-For: 6.6.6.6\r\nForwarded: proto=https\r\n\r\n");
        assertThat(last.get(), Matchers.is("1.2.3.4"));
    }

    @Test
    public void testLimit() throws Exception
    {
        ThreadLimitHandler handler = new ThreadLimitHandler("Forwarded");

        handler.setThreadLimit(4);

        AtomicInteger count = new AtomicInteger(0);
        AtomicInteger total = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        handler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(HttpStatus.OK_200);
                if ("/other".equals(target))
                    return;

                try
                {
                    count.incrementAndGet();
                    total.incrementAndGet();
                    latch.await();
                }
                catch (InterruptedException e)
                {
                    throw new ServletException(e);
                }
                finally
                {
                    count.decrementAndGet();
                }
            }
        });
        _server.setHandler(handler);
        _server.start();

        Socket[] client = new Socket[10];
        for (int i = 0; i < client.length; i++)
        {
            client[i] = new Socket("127.0.0.1", _connector.getLocalPort());
            client[i].getOutputStream().write(("GET /" + i + " HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n").getBytes());
            client[i].getOutputStream().flush();
        }

        long wait = 10;
        long start = NanoTime.now();
        while (count.get() < 4 && NanoTime.secondsSince(start) < wait)
        {
            Thread.sleep(1);
        }
        assertThat(count.get(), is(4));

        // check that other requests are not blocked
        assertThat(_local.getResponse("GET /other HTTP/1.0\r\nForwarded: for=6.6.6.6\r\n\r\n"), Matchers.containsString(" 200 OK"));

        // let the other requests go
        latch.countDown();

        while (total.get() < 10 && NanoTime.secondsSince(start) < wait)
        {
            Thread.sleep(10);
        }
        assertThat(total.get(), is(10));

        while (count.get() > 0 && NanoTime.secondsSince(start) < wait)
        {
            Thread.sleep(10);
        }
        assertThat(count.get(), is(0));
    }
}
