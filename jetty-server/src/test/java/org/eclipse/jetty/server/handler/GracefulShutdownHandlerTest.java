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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GracefulShutdownHandlerTest
{
    private Server _server;
    private ConnectionStatistics _statistics;
    private LocalConnector _connector;
    private LatchHandler _latchHandler;
    private GracefulShutdownHandler _gracefulShutdownHandler;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();

        _connector = new LocalConnector(_server);
        _statistics = new ConnectionStatistics();
        _connector.addBean(_statistics);
        _server.addConnector(_connector);

        _latchHandler = new LatchHandler();
        _gracefulShutdownHandler = new GracefulShutdownHandler();

        _server.setHandler(_latchHandler);
        _latchHandler.setHandler(_gracefulShutdownHandler);
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testShutdownServerWithCorrectTokenAndIP() throws Exception
    {
        long delay = 500;
        CountDownLatch serverLatch = new CountDownLatch(1);
        _gracefulShutdownHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest,
                    HttpServletRequest request, HttpServletResponse response)
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                new Thread(() ->
                {
                    try
                    {
                        Thread.sleep(delay);
                        asyncContext.complete();
                    }
                    catch (InterruptedException e)
                    {
                        response.setStatus(
                                HttpStatus.INTERNAL_SERVER_ERROR_500);
                        asyncContext.complete();
                    }
                }).start();
                serverLatch.countDown();
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" + "Host: localhost\r\n" + "\r\n";
        _connector.executeRequest(request);

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));

        Future<Void> shutdown = _gracefulShutdownHandler.shutdown();
        assertFalse(shutdown.isDone());

        Thread.sleep(delay / 2);
        assertFalse(shutdown.isDone());

        Thread.sleep(delay);
        assertTrue(shutdown.isDone());
    }

    /**
     * This handler is external to the statistics handler and it is used to
     * ensure that statistics handler's handle() is fully executed before
     * asserting its values in the tests, to avoid race conditions with the
     * tests' code where the test executes but the statistics handler has not
     * finished yet.
     */
    private static class LatchHandler extends HandlerWrapper
    {
        private volatile CountDownLatch _latch = new CountDownLatch(1);

        @Override
        public void handle(String path, Request request,
                HttpServletRequest httpRequest,
                HttpServletResponse httpResponse)
                throws IOException, ServletException
        {
            final CountDownLatch latch = _latch;
            try
            {
                super.handle(path, request, httpRequest, httpResponse);
            }
            finally
            {
                latch.countDown();
            }
        }
    }
}
