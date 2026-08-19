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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ChunkedRequestTest
{
    private Server _server;
    private ContextHandler _context;
    private LocalConnector _connector;
    private final TestViolationListener _violationListener = new TestViolationListener();

    public static class TestViolationListener implements ComplianceViolation.Listener
    {
        private final BlockingArrayQueue<ComplianceViolation> _events = new BlockingArrayQueue<>();

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            _events.add(event.violation());
        }

        public ComplianceViolation poll()
        {
            return _events.poll();
        }

        public ComplianceViolation poll(long time, TimeUnit unit) throws InterruptedException
        {
            return _events.poll(time, unit);
        }
    }

    public void prepare(HttpCompliance httpCompliance) throws Exception
    {
        _server = new Server();
        _context = new ContextHandler("/");
        _server.setHandler(_context);
        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory();
        httpConnectionFactory.getHttpConfiguration().setHttpCompliance(httpCompliance);
        httpConnectionFactory.getHttpConfiguration().addComplianceViolationListener(_violationListener);
        _connector = new LocalConnector(_server, httpConnectionFactory);
        _server.addConnector(_connector);
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(_server);
        _connector = null;
    }

    public static Stream<Arguments> httpComplianceVersions()
    {
        return Stream.of(
            Arguments.of(HttpCompliance.RFC7230, true),
            Arguments.of(HttpCompliance.RFC2616, true),
            Arguments.of(HttpCompliance.LEGACY, false),
            Arguments.of(HttpCompliance.RFC7230_LEGACY, false),
            Arguments.of(HttpCompliance.RFC2616_LEGACY, false)
        );
    }

    @ParameterizedTest
    @MethodSource("httpComplianceVersions")
    public void testQuotedChunkExtensionValue(HttpCompliance compliance) throws Exception
    {
        prepare(compliance);
        AtomicInteger count = new AtomicInteger();
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                // Only ever bother trying to read the content of the first request.
                if (count.incrementAndGet() == 1)
                    Content.Source.asString(request);
                callback.succeeded();
                return true;
            }
        });
        _server.start();

        String request = "POST / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Transfer-Encoding: chunked\r\n" +
                         "\r\n" +
                         "1;a=\"\r\n" +
                         "X\r\n" +
                         "0\r\n" +
                         "\r\n" +
                         "GET /smuggled HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Content-Length: 11\r\n" +
                         "\r\n" +
                         "\"\r\n" +
                         "Y\r\n" +
                         "0\r\n" +
                         "\r\n";

        // This is not a compliance violation just bad quotes, so always a bad request.
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
        assertNull(_violationListener.poll());
    }

    @ParameterizedTest
    @MethodSource("httpComplianceVersions")
    public void testChunkLengthTooLong(HttpCompliance compliance) throws Exception
    {
        prepare(compliance);
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Source.asString(request);
                callback.succeeded();
                return true;
            }
        });
        _server.start();

        // This covers the TERM.SPILL and SPILL.TERM cases.
        String request = "POST / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Transfer-Encoding: chunked\r\n" +
                         "\r\n" +
                         "9\r\n" +
                         "some extra data\r\n" +
                         "e;foo=bar\r\n" +
                         "some more data\r\n" +
                         "0\r\n" +
                         "\r\n";

        // Bad chunk length is always an error no matter compliance mode.
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
        assertNull(_violationListener.poll());
    }

    @ParameterizedTest
    @MethodSource("httpComplianceVersions")
    public void testChunkLengthTooShort(HttpCompliance compliance) throws Exception
    {
        prepare(compliance);
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Source.asString(request);
                callback.succeeded();
                return true;
            }
        });
        _server.start();

        // Where the chunk is supposed to end define by the length is not on a CRLF terminator, so this should fail.
        String request = "POST / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Transfer-Encoding: chunked\r\n" +
                         "\r\n" +
                         "6\r\n" +
                         "x\r\n" + // This chunk is too short.
                         "7\r\n" +
                         "foo=bar\r\n" + // Because we do not see a boundary directly at the end of the chunk we should fail.
                         "0\r\n" +
                         "\r\n";

        // Bad chunk length is always an error no matter compliance mode.
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
        assertNull(_violationListener.poll());
    }

    @ParameterizedTest
    @MethodSource("httpComplianceVersions")
    public void testLineFeedTerminator(HttpCompliance compliance, boolean expectError) throws Exception
    {
        prepare(compliance);
        AtomicInteger count = new AtomicInteger();
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                // Only ever bother trying to read the content of the first request.
                if (count.incrementAndGet() == 1)
                    Content.Source.asString(request);
                callback.succeeded();
                return true;
            }
        });
        _server.start();

        String request = "POST / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Transfer-Encoding: chunked\r\n" +
                         "\r\n" +
                         "3D;!\n" +
                         "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\r\n" +
                         "0\r\n" +
                         "\r\n" +
                         "GET /smuggled HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Content-Length: 11\r\n" +
                         "\r\n" +
                         "0\r\n" +
                         "\r\n";

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        if (expectError)
        {
            // This should be an 400 response due to invalid line terminator.
            assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
            assertThat(count.get(), equalTo(1));

            // Violation is only reported if allowed.
            assertNull(_violationListener.poll());
        }
        else
        {
            // If no error is expected this should be parsed as two requests.
            assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
            await().atMost(Duration.ofSeconds(5)).until(() -> count.get() == 2);

            // Violation is only reported if allowed.
            assertThat(_violationListener.poll(5, TimeUnit.SECONDS), equalTo(HttpCompliance.Violation.LF_CHUNK_TERMINATION));
            assertThat(_violationListener._events, empty());
        }
    }

    @ParameterizedTest
    @MethodSource("httpComplianceVersions")
    public void testSpillTerm(HttpCompliance compliance, boolean expectError) throws Exception
    {
        prepare(compliance);
        AtomicInteger count = new AtomicInteger();
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                // Only ever bother trying to read the content of the first request.
                if (count.incrementAndGet() == 1)
                    Content.Source.asString(request);
                callback.succeeded();
                return true;
            }
        });
        _server.start();

        String request = "POST / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Transfer-Encoding: chunked\r\n" +
                         "\r\n" +
                         "5\r\n" +
                         "AAAAA\n" +
                         "2\r\n" +
                         "42\r\n" +
                         "0\r\n" +
                         "\r\n" +
                         "GET /smuggled HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Content-Length: 11\r\n" +
                         "\r\n" +
                         "0\r\n" +
                         "\r\n";

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        if (expectError)
        {
            // This should be an 400 response due to invalid line terminator.
            assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
            assertThat(count.get(), equalTo(1));

            // Violation is only reported if allowed.
            assertNull(_violationListener.poll());
        }
        else
        {
            // If no error is expected this should be parsed as two requests.
            assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
            await().atMost(Duration.ofSeconds(5)).until(() -> count.get() == 2);

            // Violation is only reported if allowed.
            assertThat(_violationListener.poll(5, TimeUnit.SECONDS), equalTo(HttpCompliance.Violation.LF_CHUNK_TERMINATION));
            assertThat(_violationListener._events, empty());
        }
    }

    @ParameterizedTest
    @MethodSource("httpComplianceVersions")
    public void testAnyByteTerminator(HttpCompliance compliance) throws Exception
    {
        prepare(compliance);
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Source.asString(request);
                callback.succeeded();
                return true;
            }
        });
        _server.start();

        // The XX is not a valid terminator and shouldn't be accepted.
        String request = "GET /one HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Transfer-Encoding: chunked\r\n" +
                         "\r\n" +
                         "d\r\n" +
                         "Hello, world!XX" +
                         "0\r\n" +
                         "\r\n";

        // Invalid terminator causes chunk length to be wrong which always results in an error.
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
        assertNull(_violationListener.poll());
    }

    public static Stream<Arguments> trailerLineFeedCases()
    {
        // Each variant places a lone LF (instead of CRLF) at a different point of the chunked trailer section.
        // All of them must be treated as an LF_CHUNK_TERMINATION violation.
        List<Arguments> variants = List.of(
            Arguments.of("empty trailer section terminated by LF",
                "2\r\n" +
                "AB\r\n" +
                "0\r\n" +
                "\n"),
            Arguments.of("trailer field terminated by LF",
                "2\r\n" +
                "AB\r\n" +
                "0\r\n" +
                "Trailer: foo\n" +
                "\r\n"),
            Arguments.of("trailer section terminated by LF after a trailer field",
                "2\r\n" +
                "AB\r\n" +
                "0\r\n" +
                "Trailer: foo\r\n" +
                "\n")
        );

        // Combine each compliance mode with each request variant.
        return httpComplianceVersions().flatMap(compliance ->
            variants.stream().map(variant ->
            {
                Object[] c = compliance.get();
                Object[] v = variant.get();
                return Arguments.of(c[0], c[1], v[0], v[1]);
            }));
    }

    @ParameterizedTest(name = "[{index}] {2} (compliance={0})")
    @MethodSource("trailerLineFeedCases")
    public void testTrailerLineFeed(HttpCompliance compliance, boolean expectError, String description, String chunkedBody) throws Exception
    {
        prepare(compliance);
        AtomicInteger count = new AtomicInteger();
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                count.incrementAndGet();
                Content.Source.asString(request);
                request.getTrailers();
                callback.succeeded();
                return true;
            }
        });
        _server.start();

        // The lone LF is embedded in the trailer section at a point that varies per test case, but the second
        // request is always well-formed so that in lenient modes the whole input parses as two requests.
        String request = "POST /one HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Transfer-Encoding: chunked\r\n" +
                         "\r\n" +
                         chunkedBody +
                         "GET /two HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "\r\n";

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        if (expectError)
        {
            // This should be an 400 response due to invalid line terminator.
            assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
            assertThat(count.get(), equalTo(1));

            // Violation is only reported if allowed.
            assertNull(_violationListener.poll());
        }
        else
        {
            // If no error is expected this should be parsed as two requests.
            assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
            await().atMost(Duration.ofSeconds(5)).until(() -> count.get() == 2);

            // Violation is only reported if allowed.
            assertThat(_violationListener.poll(5, TimeUnit.SECONDS), equalTo(HttpCompliance.Violation.LF_CHUNK_TERMINATION));
            assertThat(_violationListener._events, empty());
        }
    }

    @ParameterizedTest
    @MethodSource("httpComplianceVersions")
    public void testValidQuotes(HttpCompliance compliance) throws Exception
    {
        prepare(compliance);
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Source.asString(request);
                callback.succeeded();
                return true;
            }
        });
        _server.start();

        String request = "POST / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Transfer-Encoding: chunked\r\n" +
                         "\r\n" +
                         "1;a=\"\\\"\\\"\"\r\n" + // We can have quotes as long as they are escaped.
                         "X\r\n" +
                         "0\r\n" +
                         "\r\n";

        // Always valid.
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertNull(_violationListener.poll());
    }

    @ParameterizedTest
    @MethodSource("httpComplianceVersions")
    public void testInvalidQuotes(HttpCompliance compliance) throws Exception
    {
        prepare(compliance);
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Source.asString(request);
                callback.succeeded();
                return true;
            }
        });
        _server.start();

        String request = "POST / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Transfer-Encoding: chunked\r\n" +
                         "\r\n" +
                         "1;a=\"\"\"\r\n" + // Any extra quotes unescaped are not allowed.
                         "X\r\n" +
                         "0\r\n" +
                         "\r\n";

        // Always invalid and results in 400 response.
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
        assertNull(_violationListener.poll());
    }

    @ParameterizedTest
    @MethodSource("httpComplianceVersions")
    public void testTerminalChunk(HttpCompliance compliance, boolean expectError) throws Exception
    {
        prepare(compliance);
        AtomicInteger count = new AtomicInteger();
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                count.incrementAndGet();
                Content.Source.asString(request);
                callback.succeeded();
                return true;
            }
        });
        _server.start();

        String request = "POST / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Transfer-Encoding: chunked\r\n" +
                         "\r\n" +
                         "a\r\n" +
                         "0123456789\r\n" +
                         "0;foo\n" +
                         // Ambiguity whether this is header section or a continuation of extension.
                         "\r\n" +
                         "GET /two HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "\r\n";

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        if (expectError)
        {
            // This should be an 400 response due to invalid line terminator.
            assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
            assertThat(count.get(), equalTo(1));

            // Violation is only reported if allowed.
            assertNull(_violationListener.poll());
        }
        else
        {
            // If no error is expected this should be parsed as two requests.
            assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
            await().atMost(Duration.ofSeconds(5)).until(() -> count.get() == 2);

            // Violation is only reported if allowed.
            assertThat(_violationListener.poll(5, TimeUnit.SECONDS), equalTo(HttpCompliance.Violation.LF_CHUNK_TERMINATION));
            assertThat(_violationListener._events, empty());
        }
    }

    @ParameterizedTest
    @MethodSource("httpComplianceVersions")
    public void testEmptyExtension(HttpCompliance compliance) throws Exception
    {
        prepare(compliance);
        _context.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Source.asString(request);
                callback.succeeded();
                return true;
            }
        });
        _server.start();

        String request = "POST / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "Transfer-Encoding: chunked\r\n" +
                         "\r\n" +
                         "a;\r\n" + // Cannot legally have an empty extension block after the ';' character.
                         "0123456789\r\n" +
                         "0\r\n" +
                         "\r\n";

        // This is always invalid and results in 400 response.
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
        assertNull(_violationListener.poll());
    }
}