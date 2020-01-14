//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Disabled
@Tag("stress")
public class AsyncStressTest
{
    private static final Logger LOG = Log.getLogger(AsyncStressTest.class);

    protected QueuedThreadPool _threads = new QueuedThreadPool();
    protected Server _server = new Server(_threads);
    protected SuspendHandler _handler = new SuspendHandler();
    protected ServerConnector _connector;
    protected InetAddress _addr;
    protected int _port;
    protected Random _random = new Random();
    private static final String[][] __paths =
        {
            {"/path", "NORMAL"},
            {"/path/info", "NORMAL"},
            {"/path?sleep=<PERIOD>", "SLEPT"},
            {"/path?suspend=<PERIOD>", "TIMEOUT"},
            {"/path?suspend=60000&resume=<PERIOD>", "RESUMED"},
            {"/path?suspend=60000&complete=<PERIOD>", "COMPLETED"},
        };

    @BeforeEach
    public void init() throws Exception
    {
        _server.manage(_threads);
        _threads.setMaxThreads(50);
        _connector = new ServerConnector(_server);
        _connector.setIdleTimeout(120000);
        _server.setConnectors(new Connector[]{_connector});
        _server.setHandler(_handler);
        _server.start();
        _port = _connector.getLocalPort();
        _addr = InetAddress.getLocalHost();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testAsync() throws Throwable
    {
        doConnections(1600, 240);
    }

    private void doConnections(int connections, final int loops) throws Throwable
    {
        Socket[] socket = new Socket[connections];
        int[][] path = new int[connections][loops];
        for (int i = 0; i < connections; i++)
        {
            socket[i] = new Socket(_addr, _port);
            socket[i].setSoTimeout(30000);
            if (i % 10 == 0)
                Thread.sleep(50);
            if (i % 80 == 0)
                System.err.println();
            System.err.print('+');
        }
        System.err.println();
        LOG.info("Bound " + connections);

        for (int l = 0; l < loops; l++)
        {
            for (int i = 0; i < connections; i++)
            {
                int p = path[i][l] = _random.nextInt(__paths.length);

                int period = _random.nextInt(290) + 10;
                String uri = StringUtil.replace(__paths[p][0], "<PERIOD>", Integer.toString(period));

                long start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                String request =
                    "GET " + uri + " HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "start: " + start + "\r\n" +
                        "result: " + __paths[p][1] + "\r\n" +
                        ((l + 1 < loops) ? "" : "Connection: close\r\n") +
                        "\r\n";
                socket[i].getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                socket[i].getOutputStream().flush();
            }
            if (l % 80 == 0)
                System.err.println();
            System.err.print('.');
            Thread.sleep(_random.nextInt(290) + 10);
        }

        System.err.println();
        LOG.info("Sent " + (loops * __paths.length) + " requests");

        String[] results = new String[connections];
        for (int i = 0; i < connections; i++)
        {
            results[i] = IO.toString(socket[i].getInputStream(), StandardCharsets.UTF_8);
            if (i % 80 == 0)
                System.err.println();
            System.err.print('-');
        }
        System.err.println();

        LOG.info("Read " + connections + " connections");

        for (int i = 0; i < connections; i++)
        {
            int offset = 0;
            String result = results[i];
            for (int l = 0; l < loops; l++)
            {
                String expect = __paths[path[i][l]][1];
                expect = expect + " " + expect;

                offset = result.indexOf("200 OK", offset) + 6;
                offset = result.indexOf("\r\n\r\n", offset) + 4;
                int end = result.indexOf("\n", offset);
                String r = result.substring(offset, end).trim();
                assertEquals(i + "," + l, expect, r);
                offset = end;
            }
        }
    }

    private static class SuspendHandler extends HandlerWrapper
    {
        private final Timer _timer;

        private SuspendHandler()
        {
            _timer = new Timer();
        }

        @Override
        public void handle(String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException
        {
            int readBefore = 0;
            long sleepFor = -1;
            long suspendFor = -1;
            long resumeAfter = -1;
            long completeAfter = -1;

            final String uri = baseRequest.getHttpURI().toString();

            if (request.getParameter("read") != null)
                readBefore = Integer.parseInt(request.getParameter("read"));
            if (request.getParameter("sleep") != null)
                sleepFor = Integer.parseInt(request.getParameter("sleep"));
            if (request.getParameter("suspend") != null)
                suspendFor = Integer.parseInt(request.getParameter("suspend"));
            if (request.getParameter("resume") != null)
                resumeAfter = Integer.parseInt(request.getParameter("resume"));
            if (request.getParameter("complete") != null)
                completeAfter = Integer.parseInt(request.getParameter("complete"));

            if (DispatcherType.REQUEST.equals(baseRequest.getDispatcherType()))
            {
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

                if (suspendFor >= 0)
                {
                    final AsyncContext asyncContext = baseRequest.startAsync();
                    asyncContext.addListener(__asyncListener);
                    if (suspendFor > 0)
                        asyncContext.setTimeout(suspendFor);
                    if (completeAfter > 0)
                    {
                        TimerTask complete = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    response.setStatus(200);
                                    response.getOutputStream().println("COMPLETED " + request.getHeader("result"));
                                    baseRequest.setHandled(true);
                                    asyncContext.complete();
                                }
                                catch (Exception e)
                                {
                                    Request br = (Request)asyncContext.getRequest();
                                    System.err.println("\n" + e.toString());
                                    System.err.println(baseRequest + "==" + br);
                                    System.err.println(uri + "==" + br.getHttpURI());
                                    System.err.println(asyncContext + "==" + br.getHttpChannelState());

                                    LOG.warn(e);
                                    System.exit(1);
                                }
                            }
                        };
                        synchronized (_timer)
                        {
                            _timer.schedule(complete, completeAfter);
                        }
                    }
                    else if (completeAfter == 0)
                    {
                        response.setStatus(200);
                        response.getOutputStream().println("COMPLETED " + request.getHeader("result"));
                        baseRequest.setHandled(true);
                        asyncContext.complete();
                    }
                    else if (resumeAfter > 0)
                    {
                        TimerTask resume = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                asyncContext.dispatch();
                            }
                        };
                        synchronized (_timer)
                        {
                            _timer.schedule(resume, resumeAfter);
                        }
                    }
                    else if (resumeAfter == 0)
                    {
                        asyncContext.dispatch();
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
                    response.getOutputStream().println("SLEPT " + request.getHeader("result"));
                    baseRequest.setHandled(true);
                }
                else
                {
                    response.setStatus(200);
                    response.getOutputStream().println("NORMAL " + request.getHeader("result"));
                    baseRequest.setHandled(true);
                }
            }
            else if (request.getAttribute("TIMEOUT") != null)
            {
                response.setStatus(200);
                response.getOutputStream().println("TIMEOUT " + request.getHeader("result"));
                baseRequest.setHandled(true);
            }
            else
            {
                response.setStatus(200);
                response.getOutputStream().println("RESUMED " + request.getHeader("result"));
                baseRequest.setHandled(true);
            }
        }
    }

    private static AsyncListener __asyncListener = new AsyncListener()
    {
        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            event.getSuppliedRequest().setAttribute("TIMEOUT", Boolean.TRUE);
            event.getSuppliedRequest().getAsyncContext().dispatch();
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {

        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
        }
    };
}
