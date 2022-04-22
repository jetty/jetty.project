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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AsyncListenerTest
{
    private QueuedThreadPool threadPool;
    private Server server;
    private LocalConnector connector;

    public void startServer(ServletContextHandler context) throws Exception
    {
        server = threadPool == null ? new Server() : new Server(threadPool);
        connector = new LocalConnector(server);
        connector.setIdleTimeout(20 * 60 * 1000L);
        server.addConnector(connector);
        server.setHandler(context);
        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testStartAsyncThrowOnErrorDispatch() throws Exception
    {
        testStartAsyncThrowOnError(event -> event.getAsyncContext().dispatch("/dispatch"));
        String httpResponse = connector.getResponse(
            "GET /ctx/path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 200 "));
    }

    @Test
    public void testStartAsyncThrowOnErrorComplete() throws Exception
    {
        testStartAsyncThrowOnError(event ->
        {
            HttpServletResponse response = (HttpServletResponse)event.getAsyncContext().getResponse();
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            ServletOutputStream output = response.getOutputStream();
            output.println(event.getThrowable().getClass().getName());
            if (event.getThrowable().getCause() != null)
                output.println(event.getThrowable().getCause().getClass().getName());
            output.println("COMPLETE");
            event.getAsyncContext().complete();
        });
        String httpResponse = connector.getResponse(
            "GET /ctx/path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 500 "));
        assertThat(httpResponse, containsString(TestRuntimeException.class.getName()));
        assertThat(httpResponse, containsString("COMPLETE"));
    }

    @Test
    public void testStartAsyncThrowOnErrorThrow() throws Exception
    {
        testStartAsyncThrowOnError(event ->
        {
            throw new IOException();
        });
        String httpResponse = connector.getResponse(
            "GET /ctx/path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 500 "));
        assertThat(httpResponse, containsString(TestRuntimeException.class.getName()));
    }

    @Test
    public void testStartAsyncThrowOnErrorNothing() throws Exception
    {
        testStartAsyncThrowOnError(event ->
        {
        });
        String httpResponse = connector.getResponse(
            "GET /ctx/path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 500 "));
        assertThat(httpResponse, containsString(TestRuntimeException.class.getName()));
    }

    @Test
    public void testStartAsyncThrowOnErrorSendError() throws Exception
    {
        testStartAsyncThrowOnError(event ->
        {
            HttpServletResponse response = (HttpServletResponse)event.getAsyncContext().getResponse();
            response.sendError(HttpStatus.BAD_GATEWAY_502, "Message!!!");
        });
        String httpResponse = connector.getResponse(
            "GET /ctx/path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 502 "));
        assertThat(httpResponse, containsString("Message!!!"));
        assertThat(httpResponse, not(containsString(TestRuntimeException.class.getName())));
    }

    @Test
    public void testStartAsyncThrowOnErrorSendErrorCustomErrorPage() throws Exception
    {
        testStartAsyncThrowOnError(event ->
        {
            HttpServletResponse response = (HttpServletResponse)event.getAsyncContext().getResponse();
            response.sendError(HttpStatus.BAD_GATEWAY_502);
        });

        // Add a custom error page.
        ErrorHandler errorHandler = new ErrorHandler()
        {
            @Override
            protected void writeErrorPageMessage(HttpServletRequest request, Writer writer, int code, String message, String uri) throws IOException
            {
                writer.write("CUSTOM\n");
                super.writeErrorPageMessage(request, writer, code, message, uri);
            }
        };
        server.setErrorProcessor(errorHandler);

        String httpResponse = connector.getResponse(
            "GET /ctx/path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n", 10, TimeUnit.MINUTES);
        assertThat(httpResponse, containsString("HTTP/1.1 502 "));
        assertThat(httpResponse, containsString("CUSTOM"));
    }

    private void testStartAsyncThrowOnError(IOConsumer<AsyncEvent> consumer) throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/ctx");
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(10000);
                asyncContext.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onError(AsyncEvent event) throws IOException
                    {
                        consumer.accept(event);
                    }
                });
                throw new QuietServletException(new TestRuntimeException());
            }
        }), "/path/*");
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                response.setStatus(HttpStatus.OK_200);
            }
        }), "/dispatch/*");
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.getOutputStream().print("CUSTOM");
            }
        }), "/error/*");

        startServer(context);
    }

    @Test
    public void testStartAsyncOnTimeoutDispatch() throws Exception
    {
        testStartAsyncOnTimeout(500, event -> event.getAsyncContext().dispatch("/dispatch"));
        String httpResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 200 "));
    }

    @Test
    public void testStartAsyncOnTimeoutComplete() throws Exception
    {
        testStartAsyncOnTimeout(500, event ->
        {
            HttpServletResponse response = (HttpServletResponse)event.getAsyncContext().getResponse();
            response.setStatus(HttpStatus.OK_200);
            ServletOutputStream output = response.getOutputStream();
            output.println("COMPLETE");
            event.getAsyncContext().complete();
        });
        String httpResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 200 "));
        assertThat(httpResponse, containsString("COMPLETE"));
    }

    @Test
    public void testStartAsyncOnTimeoutThrow() throws Exception
    {
        testStartAsyncOnTimeout(500, event ->
        {
            throw new TestRuntimeException();
        });
        String httpResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 500 "));
        assertThat(httpResponse, containsString("AsyncContext timeout"));
        assertThat(httpResponse, not(containsString(TestRuntimeException.class.getName())));
    }

    @Test
    public void testStartAsyncOnTimeoutNothing() throws Exception
    {
        testStartAsyncOnTimeout(500, event ->
        {
        });
        String httpResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 500 "));
    }

    @Test
    public void testStartAsyncOnTimeoutSendError() throws Exception
    {
        testStartAsyncOnTimeout(500, event ->
        {
            HttpServletResponse response = (HttpServletResponse)event.getAsyncContext().getResponse();
            response.sendError(HttpStatus.BAD_GATEWAY_502);
            event.getAsyncContext().complete();
        });
        String httpResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 502 "));
    }

    @Test
    public void testStartAsyncOnTimeoutSendErrorCustomErrorPage() throws Exception
    {
        testStartAsyncOnTimeout(500, event ->
        {
            AsyncContext asyncContext = event.getAsyncContext();
            HttpServletResponse response = (HttpServletResponse)asyncContext.getResponse();
            response.sendError(HttpStatus.BAD_GATEWAY_502);
            asyncContext.complete();
        });

        // Add a custom error page.
        ErrorHandler errorHandler = new ErrorHandler()
        {
            @Override
            protected void writeErrorPageMessage(HttpServletRequest request, Writer writer, int code, String message, String uri) throws IOException
            {
                writer.write("CUSTOM\n");
                super.writeErrorPageMessage(request, writer, code, message, uri);
            }
        };
        server.setErrorProcessor(errorHandler);

        String httpResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 502 "));
        assertThat(httpResponse, containsString("CUSTOM"));
    }

    private void testStartAsyncOnTimeout(long timeout, IOConsumer<AsyncEvent> consumer) throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(timeout);
                asyncContext.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onTimeout(AsyncEvent event) throws IOException
                    {
                        consumer.accept(event);
                    }
                });
            }
        }), "/*");
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                response.setStatus(HttpStatus.OK_200);
            }
        }), "/dispatch/*");
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.getOutputStream().print("CUSTOM");
            }
        }), "/error/*");

        startServer(context);
    }

    @Test
    public void testStartAsyncOnCompleteThrow() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(10000);
                asyncContext.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onComplete(AsyncEvent event)
                    {
                        throw new TestRuntimeException();
                    }
                });
                response.getOutputStream().print("DATA");
                asyncContext.complete();
            }
        }), "/*");

        startServer(context);

        String httpResponse = connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertThat(httpResponse, containsString("HTTP/1.1 200 "));
        assertThat(httpResponse, containsString("DATA"));
    }

    @Test
    public void testStartAsyncOnTimeoutCalledByPooledThread() throws Exception
    {
        String threadNamePrefix = "async_listener";
        threadPool = new QueuedThreadPool();
        threadPool.setName(threadNamePrefix);
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(1000);
                asyncContext.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onTimeout(AsyncEvent event)
                    {
                        if (Thread.currentThread().getName().startsWith(threadNamePrefix))
                            response.setStatus(HttpStatus.OK_200);
                        else
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                        asyncContext.complete();
                    }
                });
            }
        }), "/*");
        startServer(context);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"));
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    // Unique named RuntimeException to help during debugging / assertions.
    public static class TestRuntimeException extends RuntimeException implements QuietException
    {
    }

    public static class AsyncListenerAdapter implements AsyncListener
    {
        @Override
        public void onComplete(AsyncEvent event)
        {
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onStartAsync(AsyncEvent event)
        {
        }
    }

    @FunctionalInterface
    private interface IOConsumer<T>
    {
        void accept(T t) throws IOException;
    }
}
