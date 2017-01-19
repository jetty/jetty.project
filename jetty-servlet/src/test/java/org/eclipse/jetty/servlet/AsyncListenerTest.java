//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class AsyncListenerTest
{
    private QueuedThreadPool threadPool;
    private Server server;
    private LocalConnector connector;
    private String servletPath;

    private void start(HttpServlet servlet) throws Exception
    {
        server = threadPool == null ? new Server() : new Server(threadPool);
        connector = new LocalConnector(server);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        servletPath = "/async_listener";
        context.addServlet(holder, servletPath + "/*");
        server.setHandler(context);
        server.start();
    }

    @After
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testOnTimeoutCalledByPooledThread() throws Exception
    {
        String threadNamePrefix = "async_listener";
        threadPool = new QueuedThreadPool();
        threadPool.setName(threadNamePrefix);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(1000);
                asyncContext.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onTimeout(AsyncEvent event) throws IOException
                    {
                        if (Thread.currentThread().getName().startsWith(threadNamePrefix))
                            response.setStatus(HttpStatus.OK_200);
                        else
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                        asyncContext.complete();
                    }
                });
            }
        });

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(newRequest("")));
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testOnErrorCalledForExceptionAfterStartAsync() throws Exception
    {
        RuntimeException exception = new RuntimeException();
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onError(AsyncEvent event) throws IOException
                    {
                        if (exception == event.getThrowable())
                            response.setStatus(HttpStatus.OK_200);
                        else
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                        asyncContext.complete();
                    }
                });
                throw exception;
            }
        });

        try (StacklessLogging suppressor = new StacklessLogging(HttpChannel.class))
        {
            HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(newRequest("")));
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testOnErrorCalledForExceptionThrownByOnTimeout() throws Exception
    {
        RuntimeException exception = new RuntimeException();
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(1000);
                asyncContext.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onTimeout(AsyncEvent event) throws IOException
                    {
                        throw exception;
                    }

                    @Override
                    public void onError(AsyncEvent event) throws IOException
                    {
                        if (exception == event.getThrowable())
                            response.setStatus(HttpStatus.OK_200);
                        else
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                        asyncContext.complete();
                    }
                });
            }
        });

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(newRequest("")));
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testOnErrorNotCalledForExceptionThrownByOnComplete() throws Exception
    {
        CountDownLatch errorLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onComplete(AsyncEvent event) throws IOException
                    {
                        // Way too late to handle this exception, should only be logged.
                        throw new Error();
                    }

                    @Override
                    public void onError(AsyncEvent event) throws IOException
                    {
                        errorLatch.countDown();
                    }
                });
                new Thread(() ->
                {
                    try
                    {
                        Thread.sleep(1000);
                        response.setStatus(HttpStatus.OK_200);
                        asyncContext.complete();
                    }
                    catch (InterruptedException ignored)
                    {
                    }
                }).start();
            }
        });

        try (StacklessLogging suppressor = new StacklessLogging(HttpChannelState.class))
        {
            HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(newRequest("")));
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
            Assert.assertFalse(errorLatch.await(1, TimeUnit.SECONDS));
        }
    }

    private String newRequest(String pathInfo)
    {
        return "" +
                "GET " + servletPath + pathInfo + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
    }

    private static class AsyncListenerAdapter implements AsyncListener
    {
        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
        }

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
        }
    }
}
