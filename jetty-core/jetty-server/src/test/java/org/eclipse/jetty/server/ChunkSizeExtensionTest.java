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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ChunkSizeExtensionTest
{
    private Server _server;
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

    public void start(Handler handler) throws Exception
    {
        _server = new Server();
        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory();
        httpConnectionFactory.getHttpConfiguration().addComplianceViolationListener(_violationListener);
        httpConnectionFactory.getHttpConfiguration().setNotifyForbiddenComplianceViolations(true);
        _connector = new LocalConnector(_server, httpConnectionFactory);
        _server.addConnector(_connector);
        _server.setHandler(handler);
        _server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(_server);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testExtensionNoValue(boolean bws) throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            10$W;$Wext\r
            0123456789ABCDEF\r
            0\r
            \r
            """.replace("$W", bws ? "\t" : "");
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));

        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertNull(_violationListener.poll());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testExtensionWithValue(boolean bws) throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            1$W;$Wext$W=$Wval\r
            X\r
            0\r
            \r
            """.replace("$W", bws ? "\t" : "");
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));

        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertNull(_violationListener.poll());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testExtensionWithQuotedValue(boolean bws) throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            1$W;$Wext$W=$W"val"\r
            X\r
            0\r
            \r
            """.replace("$W", bws ? "\t" : "");
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));

        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertNull(_violationListener.poll());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testMultipleExtensionsNoValues(boolean bws) throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            4$W;$Wext1$W;$Wext2\r
            0123\r
            0\r
            \r
            """.replace("$W", bws ? "\t" : "");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertNull(_violationListener.poll());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testMultipleExtensionsWithValues(boolean bws) throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            4$W;$Wext1$W=$W1$W;$Wext2$W=$W2\r
            0123\r
            0\r
            \r
            """.replace("$W", bws ? "\t" : "");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertNull(_violationListener.poll());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testMultipleExtensionsWithQuotes(boolean bws) throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            4$W;$Wext1$W=$W"1"$W;$Wext2$W=$W"2"\r
            0123\r
            0\r
            \r
            """.replace("$W", bws ? "\t" : "");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertNull(_violationListener.poll());
    }

    @Test
    public void testEmptyExtension() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        // Cannot legally have an empty extension block after the ';' character.
        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            a;\r
            0123456789\r
            0\r
            \r
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
        assertNull(_violationListener.poll());
    }

    @Test
    public void testQuotesWithinQuotes() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Source.asString(request);
                callback.succeeded();
                return true;
            }
        });

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            1;a="\\"val\\""\r
            X\r
            0\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertNull(_violationListener.poll());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testTerminalChunkWithExtension(boolean quoted) throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            a\r
            0123456789\r
            0;ext=$Q1$Q\r
            \r
            """.replace("$Q", quoted ? "\"" : "");
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertNull(_violationListener.poll());
    }

    @Test
    public void testTerminalChunkWithExtensionNoValue() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            a\r
            0123456789\r
            0;ext\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertNull(_violationListener.poll());
    }

    @Test
    public void testTerminalChunkWithExtensionWithTrailers() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: chunked\r
            \r
            a\r
            0123456789\r
            0;ext\r
            Trailer: value\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertNull(_violationListener.poll());
    }
}
