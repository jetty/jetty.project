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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DelayedHandlerTest
{
    private Server _server;
    private ServerConnector _connector;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testNotDelayed() throws Exception
    {
        DelayedHandler delayedHandler = new DelayedHandler()
        {
            @Override
            protected DelayedProcess newDelayedProcess(boolean contentExpected, String contentType, MimeTypes.Type mimeType, Handler handler, Request request, Response response, Callback callback)
            {
                return null;
            }
        };

        _server.setHandler(delayedHandler);
        delayedHandler.setHandler(new HelloHandler());
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                \r
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("Hello"));
        }
    }

    @Test
    public void testDelayed() throws Exception
    {
        Exchanger<Runnable> handleEx = new Exchanger<>();
        DelayedHandler delayedHandler = new DelayedHandler()
        {
            @Override
            protected DelayedProcess newDelayedProcess(boolean contentExpected, String contentType, MimeTypes.Type mimeType, Handler handler, Request request, Response response, Callback callback)
            {
                return new DelayedProcess(handler, request, response, callback)
                {
                    @Override
                    protected void delay() throws Exception
                    {
                        handleEx.exchange(this::process);
                    }
                };
            }
        };

        _server.setHandler(delayedHandler);
        CountDownLatch processing = new CountDownLatch(1);
        delayedHandler.setHandler(new HelloHandler()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                processing.countDown();
                return super.process(request, response, callback);
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                \r
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            Runnable handle = handleEx.exchange(null, 10, TimeUnit.SECONDS);
            assertNotNull(handle);
            assertFalse(processing.await(250, TimeUnit.MILLISECONDS));

            handle.run();

            assertTrue(processing.await(10, TimeUnit.SECONDS));

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("Hello"));
        }
    }

    @Test
    public void testDelayedUntilContent() throws Exception
    {
        DelayedHandler delayedHandler = new DelayedHandler();

        _server.setHandler(delayedHandler);
        CountDownLatch processing = new CountDownLatch(1);
        delayedHandler.setHandler(new HelloHandler()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                // Check that we are not called via any demand callback
                ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                new Throwable().printStackTrace(new PrintStream(out));
                String stack = out.toString(StandardCharsets.ISO_8859_1);
                assertThat(stack, not(containsString("DemandContentCallback.succeeded")));
                assertThat(stack, not(containsString("UntilContentDelayedProcess.onContent")));

                processing.countDown();
                return super.process(request, response, callback);
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                POST / HTTP/1.1\r
                Host: localhost\r
                Content-Length: 10\r
                \r
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            assertFalse(processing.await(250, TimeUnit.MILLISECONDS));

            output.write("01234567\r\n".getBytes(StandardCharsets.UTF_8));
            output.flush();

            assertTrue(processing.await(10, TimeUnit.SECONDS));

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("Hello"));
        }
    }

    @Test
    public void testNoDelayWithContent() throws Exception
    {
        DelayedHandler delayedHandler = new DelayedHandler();

        _server.setHandler(delayedHandler);
        delayedHandler.setHandler(new HelloHandler()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                // Check that we are called directly from HttpConnection.onFillable
                ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                new Throwable().printStackTrace(new PrintStream(out));
                String stack = out.toString(StandardCharsets.ISO_8859_1);
                assertThat(stack, containsString("org.eclipse.jetty.server.internal.HttpConnection.onFillable"));
                assertThat(stack, containsString("org.eclipse.jetty.server.handler.DelayedHandler.process"));

                // Check the content is available
                String content = Content.Source.asString(request);
                assertThat(content, equalTo("1234567890"));

                return super.process(request, response, callback);
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                POST / HTTP/1.1\r
                Host: localhost\r
                Content-Length: 10\r
                \r
                1234567890\r
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("Hello"));
        }
    }

    @Test
    public void testDelayed404() throws Exception
    {
        DelayedHandler delayedHandler = new DelayedHandler()
        {
            @Override
            protected DelayedProcess newDelayedProcess(boolean contentExpected, String contentType, MimeTypes.Type mimeType, Handler handler, Request request, Response response, Callback callback)
            {
                return new DelayedProcess(handler, request, response, callback)
                {
                    @Override
                    protected void delay() throws Exception
                    {
                        getRequest().getContext().execute(this::process);
                    }
                };
            }
        };

        _server.setHandler(delayedHandler);
        delayedHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                return false;
            }
        });

        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                \r
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("<tr><th>MESSAGE:</th><td>Not Found</td></tr>"));
        }
    }

    @Test
    public void testDelayedFormFields() throws Exception
    {
        DelayedHandler delayedHandler = new DelayedHandler();

        _server.setHandler(delayedHandler);
        CountDownLatch processing = new CountDownLatch(2);
        delayedHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                processing.countDown();
                Fields fields = FormFields.from(request).get(1, TimeUnit.NANOSECONDS);
                Content.Sink.write(response, true, String.valueOf(fields), callback);
                return true;
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            OutputStream output = socket.getOutputStream();

            output.write("""
                GET / HTTP/1.1
                Host: localhost
                
                """.getBytes(StandardCharsets.UTF_8));
            output.flush();

            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(processing::getCount, equalTo(1L));
            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("[]"));

            output.write("""
                POST / HTTP/1.1
                Host: localhost
                Content-Type: %s
                Content-Length: 22
                
                """.formatted(MimeTypes.Type.FORM_ENCODED).getBytes(StandardCharsets.UTF_8));
            output.flush();
            assertFalse(processing.await(100, TimeUnit.MILLISECONDS));

            output.write("name=value".getBytes(StandardCharsets.UTF_8));
            output.flush();
            assertFalse(processing.await(100, TimeUnit.MILLISECONDS));

            output.write("&x=1&x=2&".getBytes(StandardCharsets.UTF_8));
            output.flush();
            assertFalse(processing.await(100, TimeUnit.MILLISECONDS));

            output.write("x=3".getBytes(StandardCharsets.UTF_8));
            output.flush();
            assertTrue(processing.await(10, TimeUnit.SECONDS));

            input = HttpTester.from(socket.getInputStream());
            response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("name=[value]"));
            assertThat(content, containsString("x=[1, 2, 3]"));
        }
    }

    @Test
    public void testNoDelayFormFields() throws Exception
    {
        DelayedHandler delayedHandler = new DelayedHandler();

        _server.setHandler(delayedHandler);
        delayedHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                // Check that we are called directly from HttpConnection.onFillable via DelayedHandler.process
                ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                new Throwable().printStackTrace(new PrintStream(out));
                String stack = out.toString(StandardCharsets.ISO_8859_1);
                assertThat(stack, containsString("org.eclipse.jetty.server.internal.HttpConnection.onFillable"));
                assertThat(stack, containsString("org.eclipse.jetty.server.handler.DelayedHandler.process"));

                Fields fields = FormFields.from(request).get(1, TimeUnit.NANOSECONDS);
                Content.Sink.write(response, true, String.valueOf(fields), callback);
                return true;
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            OutputStream output = socket.getOutputStream();

            output.write("""
                POST / HTTP/1.1
                Host: localhost
                Content-Type: %s
                Content-Length: 22
                
                name=value&x=1&x=2&x=3
                """.formatted(MimeTypes.Type.FORM_ENCODED).getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);

            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("name=[value]"));
            assertThat(content, containsString("x=[1, 2, 3]"));
        }
    }
}
