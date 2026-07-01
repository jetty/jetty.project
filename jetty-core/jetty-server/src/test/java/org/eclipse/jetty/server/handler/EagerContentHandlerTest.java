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

package org.eclipse.jetty.server.handler;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EagerContentHandlerTest
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
    public void testNotEager() throws Exception
    {
        EagerContentHandler eagerContentHandler = new EagerContentHandler(new HelloHandler(), new EagerContentHandler.ContentLoaderFactory[]{});
        _server.setHandler(eagerContentHandler);
        eagerContentHandler.setHandler(new HelloHandler());
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
    public void testEager() throws Exception
    {
        Exchanger<Runnable> handleEx = new Exchanger<>();
        EagerContentHandler eagerContentHandler = new EagerContentHandler(new HelloHandler(),
            new EagerContentHandler.ContentLoaderFactory()
            {
                @Override
                public String getApplicableMimeType()
                {
                    return null;
                }

                @Override
                public EagerContentHandler.ContentLoader newContentLoader(String contentType, String mimeType, Handler handler, Request request, Response response, Callback callback)
                {
                    return new EagerContentHandler.ContentLoader(handler, request, response, callback)
                    {
                        @Override
                        protected void load() throws Exception
                        {
                            handleEx.exchange(this::handle);
                        }
                    };
                }
            });

        _server.setHandler(eagerContentHandler);
        CountDownLatch processing = new CountDownLatch(1);
        eagerContentHandler.setHandler(new HelloHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                assertThat(Content.Source.asString(request), is("0123456789"));
                processing.countDown();
                return super.handle(request, response, callback);
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
                0123456789\r
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
    public void testEagerRetainedContent() throws Exception
    {
        EagerContentHandler eagerContentHandler = new EagerContentHandler(new EagerContentHandler.RetainedContentLoaderFactory(-1, -1, true));

        _server.setHandler(eagerContentHandler);
        CountDownLatch processing = new CountDownLatch(1);
        eagerContentHandler.setHandler(new HelloHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Check that we are not called via any demand callback
                ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                new Throwable().printStackTrace(new PrintStream(out));
                String stack = out.toString(StandardCharsets.ISO_8859_1);
                assertThat(stack, not(containsString("DemandContentCallback.succeeded")));
                assertThat(stack, not(containsString("%s.%s".formatted(
                    EagerContentHandler.RetainedContentLoaderFactory.RetainedContentLoader.class.getSimpleName(),
                    EagerContentHandler.RetainedContentLoaderFactory.RetainedContentLoader.class.getDeclaredMethod("run").getName()))));

                processing.countDown();
                return super.handle(request, response, callback);
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
    public void testEagerContentInContext() throws Exception
    {
        ContextHandler context = new ContextHandler();
        _server.setHandler(context);
        EagerContentHandler eagerContentHandler = new EagerContentHandler();
        context.setHandler(eagerContentHandler);

        CountDownLatch processing = new CountDownLatch(1);
        eagerContentHandler.setHandler(new HelloHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Check that we are not called via any demand callback
                ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                new Throwable().printStackTrace(new PrintStream(out));
                String stack = out.toString(StandardCharsets.ISO_8859_1);
                assertThat(stack, not(containsString("DemandContentCallback.succeeded")));
                assertThat(stack, not(containsString("%s.%s".formatted(
                    EagerContentHandler.RetainedContentLoaderFactory.RetainedContentLoader.class.getSimpleName(),
                    EagerContentHandler.RetainedContentLoaderFactory.RetainedContentLoader.class.getDeclaredMethod("run").getName()))));

                // Check content
                String body = Content.Source.asString(request, StandardCharsets.ISO_8859_1);
                assertThat(body, is("0123456789"));

                // Check the thread is in the context
                assertThat(ContextHandler.getCurrentContext(), sameInstance(context.getContext()));

                // Check the request is wrapped in the context
                assertThat(request.getContext(), sameInstance(context.getContext()));

                processing.countDown();
                return super.handle(request, response, callback);
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

            output.write("0123456".getBytes(StandardCharsets.UTF_8));
            output.flush();

            assertFalse(processing.await(250, TimeUnit.MILLISECONDS));

            output.write("789".getBytes(StandardCharsets.UTF_8));
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
    public void testDirectCallWithContent() throws Exception
    {
        EagerContentHandler eagerContentHandler = new EagerContentHandler();

        _server.setHandler(eagerContentHandler);
        eagerContentHandler.setHandler(new HelloHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Check that we are called directly from HttpConnection.onFillable
                ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                new Throwable().printStackTrace(new PrintStream(out));
                String stack = out.toString(StandardCharsets.ISO_8859_1);
                assertThat(stack, containsString("org.eclipse.jetty.server.internal.HttpConnection.onFillable"));

                // Check the content is available
                String content = Content.Source.asString(request);
                assertThat(content, equalTo("1234567890"));

                return super.handle(request, response, callback);
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
    public void testDirectCallWithChunkedContent() throws Exception
    {
        EagerContentHandler eagerContentHandler = new EagerContentHandler();

        _server.setHandler(eagerContentHandler);
        eagerContentHandler.setHandler(new HelloHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Check that we are called directly from HttpConnection.onFillable
                ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                new Throwable().printStackTrace(new PrintStream(out));
                String stack = out.toString(StandardCharsets.ISO_8859_1);
                assertThat(stack, containsString("org.eclipse.jetty.server.internal.HttpConnection.onFillable"));

                // Check the content is available
                String content = Content.Source.asString(request);
                assertThat(content, equalTo("1234567890"));

                return super.handle(request, response, callback);
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                POST / HTTP/1.1\r
                Host: localhost\r
                Transfer-Encoding: chunked\r
                \r
                3\r
                123\r
                4\r
                4567\r
                3\r
                890\r
                0\r
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
    public void testEager404() throws Exception
    {
        EagerContentHandler eagerContentHandler = new EagerContentHandler(new EagerContentHandler.ContentLoaderFactory()
        {
            @Override
            public String getApplicableMimeType()
            {
                return null;
            }

            @Override
            public EagerContentHandler.ContentLoader newContentLoader(String contentType, String mimeType, Handler handler, Request request, Response response, Callback callback)
            {
                return new EagerContentHandler.ContentLoader(handler, request, response, callback)
                {
                    @Override
                    protected void load()
                    {
                        getRequest().getContext().execute(this::handle);
                    }
                };
            }
        });

        _server.setHandler(eagerContentHandler);
        eagerContentHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
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
    public void testEagerFormFields() throws Exception
    {
        EagerContentHandler eagerContentHandler = new EagerContentHandler();

        _server.setHandler(eagerContentHandler);
        CountDownLatch processing = new CountDownLatch(2);
        eagerContentHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                processing.countDown();
                Fields fields = FormFields.getFields(request);
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
    public void testEagerFormFieldsLimits() throws Exception
    {
        // Set the server context limit for maxFormContentSize.
        _server.getContext().setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize", "15");

        CountDownLatch processing = new CountDownLatch(2);
        CompletableFuture<Throwable> handlerErrorFuture = new CompletableFuture<>();
        EagerContentHandler eagerContentHandler = new EagerContentHandler(new EagerContentHandler.FormContentLoaderFactory()
        {
            @Override
            public EagerContentHandler.ContentLoader newContentLoader(String contentType, String mimeType, Handler handler, Request request, Response response, Callback callback)
            {
                EagerContentHandler.ContentLoader contentLoader = super.newContentLoader(contentType, mimeType, handler, request, response, callback);
                return new EagerContentHandler.ContentLoader(handler, request, response, callback)
                {
                    @Override
                    protected void load() throws Exception
                    {
                        contentLoader.load();
                        processing.countDown();
                    }
                };
            }
        });
        _server.setHandler(eagerContentHandler);
        eagerContentHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                try
                {
                    Fields fields = FormFields.getFields(request);
                    Content.Sink.write(response, true, String.valueOf(fields), callback);
                    return true;
                }
                catch (Throwable t)
                {
                    handlerErrorFuture.complete(t);
                    throw t;
                }
            }
        });
        _server.start();

        // Test when the content-length is known so the failure is triggered directly from EagerContentHandler.
        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                POST /foo HTTP/1.1\r
                Host: localhost\r
                Content-Type: application/x-www-form-urlencoded\r
                Content-Length: 27\r
                \r
                param1=value1&param2=value2\
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // The failure should not be thrown from the content loader but the handler.
            Throwable throwable = handlerErrorFuture.get(5, TimeUnit.SECONDS);
            assertThat(throwable, instanceOf(HttpException.IllegalStateException.class));

            // The response code should be 413 PAYLOAD_TOO_LARGE.
            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertThat(response.getStatus(), is(HttpStatus.PAYLOAD_TOO_LARGE_413));
            assertThat(response.getContent(), containsString("form too large"));
        }

        // Test when the content-length is not known so the failure is triggered from the Handler trying to access the form.
        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            // Write the first request content which does not exceed the form limits.
            String request = """
                POST /foo HTTP/1.1\r
                Host: localhost\r
                Content-Type: application/x-www-form-urlencoded\r
                Transfer-Encoding: chunked\r
                \r
                D\r
                param1=value1\r
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Assert we reach the handler and didn't fail in EagerContentHandler.
            assertTrue(processing.await(5, TimeUnit.SECONDS));

            // Write remaining form content which exceeds the limit.
            String requestContinued = """
                E\r
                &param2=value2\r
                0\r
                \r
                """;
            output.write(requestContinued.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // After writing the final bytes of the request we fail in the handler.
            Throwable throwable = handlerErrorFuture.get(5, TimeUnit.SECONDS);
            assertThat(throwable, instanceOf(HttpException.IllegalStateException.class));

            // The response code should be 413 PAYLOAD_TOO_LARGE.
            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertThat(response.getStatus(), is(HttpStatus.PAYLOAD_TOO_LARGE_413));
            assertThat(response.getContent(), containsString("form too large"));
        }
    }

    public static Stream<Arguments> formLimitsProvider()
    {
        // The form content has a size of 27 bytes with 2 fields.
        return Stream.of(
            Arguments.of(-1, -1, HttpStatus.OK_200),
            Arguments.of(100, 100, HttpStatus.OK_200),
            Arguments.of(2, -1, HttpStatus.OK_200),
            Arguments.of(1, -1, HttpStatus.PAYLOAD_TOO_LARGE_413),
            Arguments.of(-1, 27, HttpStatus.OK_200),
            Arguments.of(-1, 26, HttpStatus.PAYLOAD_TOO_LARGE_413)
            );
    }

    @ParameterizedTest
    @MethodSource("formLimitsProvider")
    public void perRequestFormLimitsTest(int maxFormFields, int maxFormLength, int expectedStatusCode) throws Exception
    {
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                String maxFieldsAttribute = request.getHeaders().get(FormFields.MAX_FIELDS_ATTRIBUTE);
                if (maxFieldsAttribute != null)
                    request.setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, maxFieldsAttribute);

                String maxLengthAttribute = request.getHeaders().get(FormFields.MAX_LENGTH_ATTRIBUTE);
                if (maxLengthAttribute != null)
                    request.setAttribute(FormFields.MAX_LENGTH_ATTRIBUTE, maxLengthAttribute);

                Fields fields = FormFields.getFields(request);
                Content.Sink.write(response, true, String.valueOf(fields), callback);
                return true;
            }
        });
        _server.start();

        HttpTester.Response response = getResponse(maxFormFields, maxFormLength);
        assertThat(response.getStatus(), is(expectedStatusCode));
    }

    private HttpTester.Response getResponse(int maxFormFields, int maxFormLength) throws Exception
    {
        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            StringBuilder request = new StringBuilder();
            request.append("""
                POST /foo HTTP/1.1\r
                Host: localhost\r
                """);
            if (maxFormFields != -1)
                request.append(FormFields.MAX_FIELDS_ATTRIBUTE).append(": ").append(maxFormFields).append("\r\n");
            if (maxFormLength != -1)
                request.append(FormFields.MAX_LENGTH_ATTRIBUTE).append(": ").append(maxFormLength).append("\r\n");
            request.append("""
                Content-Type: application/x-www-form-urlencoded\r
                Content-Length: 27\r
                \r
                param1=value1&param2=value2\
                """);
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            return HttpTester.parseResponse(input);
        }
    }

    @Test
    public void testDirectCallFormFields() throws Exception
    {
        EagerContentHandler eagerContentHandler = new EagerContentHandler();

        _server.setHandler(eagerContentHandler);
        eagerContentHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Check that we are called directly from HttpConnection.onFillable via EagerHandler.handle().
                ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                new Throwable().printStackTrace(new PrintStream(out));
                String stack = out.toString(StandardCharsets.ISO_8859_1);
                assertThat(stack, containsString("org.eclipse.jetty.server.internal.HttpConnection.onFillable"));

                Fields fields = FormFields.getFields(request);
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

    @Test
    public void testEagerMultipart() throws Exception
    {
        EagerContentHandler eagerContentHandler = new EagerContentHandler();
        _server.setAttribute(MultiPartConfig.class.getName(), new MultiPartConfig.Builder().build());
        _server.setHandler(eagerContentHandler);
        eagerContentHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                MultiPartFormData.Parts parts = MultiPartFormData.getParts(request);
                assertNotNull(parts);
                assertThat(parts.size(), equalTo(3));
                for (int i = 0; i < 3; i++)
                {
                    assertThat(parts.get(i).getName(), equalTo("part" + i));
                    assertThat(parts.get(i).getContentAsString(StandardCharsets.ISO_8859_1),
                        equalTo("This is the content of Part" + i));
                }

                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                response.write(true, BufferUtil.toBuffer("success"), callback);
                return true;
            }
        });
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String requestContent = """
                --jettyBoundary123\r
                Content-Disposition: form-data; name="part0"\r
                \r
                This is the content of Part0\r
                --jettyBoundary123\r
                Content-Disposition: form-data; name="part1"\r
                \r
                This is the content of Part1\r
                --jettyBoundary123\r
                Content-Disposition: form-data; name="part2"\r
                \r
                This is the content of Part2\r
                --jettyBoundary123--\r
                """;
            String requestHeaders = String.format("""
                POST / HTTP/1.1\r
                Host: localhost\r
                Content-Type: multipart/form-data; boundary=jettyBoundary123\r
                Content-Length: %s\r
                \r
                """, requestContent.getBytes(StandardCharsets.UTF_8).length);
            OutputStream output = socket.getOutputStream();
            output.write((requestHeaders + requestContent).getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, equalTo("success"));
        }
    }
}
