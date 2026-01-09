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

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MultiPartCompliance;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.toolchain.test.ExtraMatchers.ordered;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ComplianceViolationListenerTest
{
    private Server server;
    private LocalConnector localConnector;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    protected void startServer(Consumer<Server> serverConsumer) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        if (serverConsumer != null)
            serverConsumer.accept(server);

        server.start();
    }

    @Test
    public void testUriComplianceClean() throws Exception
    {
        String rawPath = "/path/to/resource";

        Queue<String> events = new BlockingArrayQueue<>();
        UriCompliance uriCompliance = UriCompliance.DEFAULT;

        startServer(server ->
        {
            localConnector.getContainedBeans(HttpConfiguration.class)
                .forEach(httpConfig ->
                {
                    httpConfig.setUriCompliance(uriCompliance);
                    httpConfig.addComplianceViolationListener(new MyComplianceListener(events));
                });

            server.setHandler(new EchoRequestUriHandler());
        });

        String rawRequest = """
            GET %s HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """.formatted(rawPath);

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        List<String> expectedEvents = List.of(
            "CVL - initialize()",
            "REQ (GET http://local" + rawPath + ") - onRequestBegin()",
            "REQ (GET http://local" + rawPath + ") - onRequestEnd()"
        );

        await().atMost(5, TimeUnit.SECONDS).until(events::size, equalTo(expectedEvents.size()));

        assertEquals(200, response.getStatus(), rawResponse);
        String responseBody = response.getContent();
        assertThat(responseBody, containsString(rawPath));
        assertThat(events, ordered(expectedEvents));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testUriComplianceBad(boolean notifyForbiddenEvents) throws Exception
    {
        Queue<String> events = new BlockingArrayQueue<>();
        UriCompliance uriCompliance = UriCompliance.DEFAULT;

        startServer(server ->
        {
            localConnector.getContainedBeans(HttpConfiguration.class)
                .forEach(httpConfig ->
                {
                    httpConfig.setUriCompliance(uriCompliance);
                    httpConfig.setNotifyForbiddenComplianceViolations(notifyForbiddenEvents);
                    httpConfig.addComplianceViolationListener(new MyComplianceListener(events));
                });

            server.setHandler(new EchoRequestUriHandler());
        });

        String rawPath = "/path//..//%2e/%2f";

        String rawRequest = """
            GET %s HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """.formatted(rawPath);

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        List<String> expectedEvents = new ArrayList<>();
        expectedEvents.add("CVL - initialize()");
        expectedEvents.add("REQ (GET http://local" + rawPath + ") - onRequestBegin()");
        if (notifyForbiddenEvents)
        {
            expectedEvents.add("REQ (GET http://local" + rawPath + ") - onViolation() - UriCompliance.AMBIGUOUS_PATH_SEGMENT (forbidden)");
            expectedEvents.add("REQ (GET http://local" + rawPath + ") - onViolation() - UriCompliance.AMBIGUOUS_EMPTY_SEGMENT (forbidden)");
            expectedEvents.add("REQ (GET http://local" + rawPath + ") - onViolation() - UriCompliance.AMBIGUOUS_PATH_SEPARATOR (forbidden)");
        }
        expectedEvents.add("REQ (GET http://local" + rawPath + ") - onRequestEnd()");

        await().atMost(5, TimeUnit.SECONDS).until(events::size, equalTo(expectedEvents.size()));

        assertEquals(400, response.getStatus(), rawResponse);
        String responseBody = response.getContent();
        assertThat(responseBody, containsString(rawPath));
        assertThat(events, ordered(expectedEvents));
    }

    @Test
    public void testHttpComplianceClean() throws Exception
    {
        Queue<String> events = new BlockingArrayQueue<>();
        HttpCompliance httpCompliance = HttpCompliance.RFC7230;

        startServer(server ->
        {
            localConnector.getContainedBeans(HttpConfiguration.class)
                .forEach(httpConfig ->
                {
                    httpConfig.setHttpCompliance(httpCompliance);
                    httpConfig.addComplianceViolationListener(new MyComplianceListener(events));
                });

            server.setHandler(new EchoRequestHeadersHandler());
        });

        // Intentionally using CR+LF to avoid allowed violation
        String rawRequest = """
            GET /path/to/resource HTTP/1.1\r
            Host: local\r
            Connection: close\r
            X-Foo: value;param=bad\r
            \r
            """;

        String expectedRequestURI = "http://local/path/to/resource";

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        List<String> expectedEvents = List.of(
            "CVL - initialize()",
            "REQ (GET " + expectedRequestURI + ") - onRequestBegin()",
            "REQ (GET " + expectedRequestURI + ") - onRequestEnd()"
        );

        await().atMost(5, TimeUnit.SECONDS).until(events::size, equalTo(expectedEvents.size()));

        assertEquals(200, response.getStatus(), rawResponse);
        String responseBody = response.getContent();
        assertThat(responseBody, containsString(expectedRequestURI));
        assertThat(responseBody, containsString("Host=local"));
        assertThat(responseBody, containsString("Connection=close"));
        assertThat(responseBody, containsString("X-Foo=value;param=bad"));
        assertThat(events, ordered(expectedEvents));
    }

    @Test
    public void testHttpComplianceBadAllowed() throws Exception
    {
        Queue<String> events = new BlockingArrayQueue<>();
        HttpCompliance httpCompliance = HttpCompliance.RFC7230;

        startServer(server ->
        {
            localConnector.getContainedBeans(HttpConfiguration.class)
                .forEach(httpConfig ->
                {
                    httpConfig.setHttpCompliance(httpCompliance);
                    httpConfig.addComplianceViolationListener(new MyComplianceListener(events));
                });

            server.setHandler(new EchoRequestHeadersHandler());
        });

        // Intentionally NOT using CR+LF in Host header to trigger allowed LF_HEADER_TERMINATION violation
        // Using \t in parameter value triggering allowed WHITESPACE_IN_PARAMETER violation
        String rawRequest = """
            GET /path/to/bad/resource HTTP/1.1\r
            Host: local
            Connection: close\r
            X-Foo: value;param\t=bad\r
            \r
            """;

        String expectedRequestURI = "http://local/path/to/bad/resource";

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        List<String> expectedEvents = List.of(
            "CVL - initialize()",
            // This is a fundamental error during HttpParse, there's no request (yet)
            "REQ (null) - onViolation() - HttpCompliance.LF_HEADER_TERMINATION (allowed)",
            "REQ (GET " + expectedRequestURI + ") - onRequestBegin()",
            "REQ (GET " + expectedRequestURI + ") - onViolation() - HttpCompliance.WHITESPACE_IN_PARAMETER (allowed)",
            "REQ (GET " + expectedRequestURI + ") - onRequestEnd()"
        );

        await().atMost(5, TimeUnit.SECONDS).until(events::size, equalTo(expectedEvents.size()));

        assertEquals(200, response.getStatus(), rawResponse);
        String responseBody = response.getContent();
        assertThat(responseBody, containsString(expectedRequestURI));
        assertThat(responseBody, containsString("Host=local"));
        assertThat(responseBody, containsString("Connection=close"));
        assertThat(responseBody, containsString("X-Foo=value;param=bad"));
        assertThat(events, ordered(expectedEvents));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testHttpComplianceBadForbidden(boolean notifyForbiddenEvents) throws Exception
    {
        String expectedRequestURI = "/path/to/forbidden/resource";

        Queue<String> events = new BlockingArrayQueue<>();
        HttpCompliance httpCompliance = HttpCompliance.RFC7230;

        startServer(server ->
        {
            localConnector.getContainedBeans(HttpConfiguration.class)
                .forEach(httpConfig ->
                {
                    httpConfig.setHttpCompliance(httpCompliance);
                    httpConfig.setNotifyForbiddenComplianceViolations(notifyForbiddenEvents);
                    httpConfig.addComplianceViolationListener(new MyComplianceListener(events));
                });

            server.setHandler(new EchoRequestHeadersHandler());
        });

        // Duplicate Host headers are forbidden.
        String rawRequest = """
            GET /path/to/forbidden/resource HTTP/1.1\r
            Host: local\r
            Host: badother\r
            Connection: close\r
            \r
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        List<String> expectedEvents = new ArrayList<>();
        expectedEvents.add("CVL - initialize()");
        if (notifyForbiddenEvents)
        {
            // This is a fundamental error during HttpParse, there's no request (yet)
            expectedEvents.add("REQ (null) - onViolation() - HttpCompliance.DUPLICATE_HOST_HEADERS (forbidden)");
        }
        // TODO: there's no onRequestBegin, but there is a onRequestEnd?
        expectedEvents.add("REQ (GET " + expectedRequestURI + ") - onRequestEnd()");

        await().atMost(5, TimeUnit.SECONDS).until(events::size, equalTo(expectedEvents.size()));

        assertEquals(400, response.getStatus(), rawResponse);
        String responseBody = response.getContent();
        assertThat(responseBody, containsString(expectedRequestURI));
        assertThat(events, ordered(expectedEvents));
    }

    @Test
    public void testCookieComplianceClean() throws Exception
    {
        Queue<String> events = new BlockingArrayQueue<>();
        CookieCompliance cookieCompliance = CookieCompliance.RFC6265;

        startServer(server ->
        {
            localConnector.getContainedBeans(HttpConfiguration.class)
                .forEach(httpConfig ->
                {
                    httpConfig.setRequestCookieCompliance(cookieCompliance);
                    httpConfig.addComplianceViolationListener(new MyComplianceListener(events));
                });

            server.setHandler(new EchoRequestCookiesHandler());
        });

        String rawRequest = """
            GET /path/to/good/cookie HTTP/1.1\r
            Host: local\r
            Connection: close\r
            Cookie: foo=bar\r
            \r
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        List<String> expectedEvents = List.of(
            "CVL - initialize()",
            "REQ (GET http://local/path/to/good/cookie) - onRequestBegin()",
            "REQ (GET http://local/path/to/good/cookie) - onRequestEnd()"
        );

        await().atMost(5, TimeUnit.SECONDS).until(events::size, equalTo(expectedEvents.size()));

        assertEquals(200, response.getStatus(), rawResponse);
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("/path/to/good/cookie"));
        assertThat(responseBody, containsString("foo=bar"));
        assertThat(events, ordered(expectedEvents));
    }

    @Test
    public void testCookieComplianceBadAllowed() throws Exception
    {
        Queue<String> events = new BlockingArrayQueue<>();
        CookieCompliance cookieCompliance = CookieCompliance.RFC6265;

        startServer(server ->
        {
            localConnector.getContainedBeans(HttpConfiguration.class)
                .forEach(httpConfig ->
                {
                    httpConfig.setRequestCookieCompliance(cookieCompliance);
                    httpConfig.addComplianceViolationListener(new MyComplianceListener(events));
                });

            server.setHandler(new EchoRequestCookiesHandler());
        });

        // Extra Cookie `\t` triggers allowed OPTIONAL_WHITE_SPACE violation
        String rawRequest = """
            GET /path/to/bad/cookie/allowed HTTP/1.1\r
            Host: local\r
            Connection: close\r
            Cookie: foo\t=bar\r
            \r
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        List<String> expectedEvents = List.of(
            "CVL - initialize()",
            "REQ (GET http://local/path/to/bad/cookie/allowed) - onRequestBegin()",
            "REQ (GET http://local/path/to/bad/cookie/allowed) - onViolation() - CookieCompliance.OPTIONAL_WHITE_SPACE (allowed)",
            "REQ (GET http://local/path/to/bad/cookie/allowed) - onRequestEnd()"
        );

        await().atMost(5, TimeUnit.SECONDS).until(events::size, equalTo(expectedEvents.size()));

        assertEquals(200, response.getStatus(), rawResponse);
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("/path/to/bad/cookie/allowed"));
        assertThat(responseBody, containsString("foo=bar"));
        assertThat(events, ordered(expectedEvents));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testCookieComplianceBadForbidden(boolean notifyForbiddenEvents) throws Exception
    {
        Queue<String> events = new BlockingArrayQueue<>();
        CookieCompliance cookieCompliance = CookieCompliance.RFC6265;

        startServer(server ->
        {
            localConnector.getContainedBeans(HttpConfiguration.class)
                .forEach(httpConfig ->
                {
                    httpConfig.setRequestCookieCompliance(cookieCompliance);
                    httpConfig.setNotifyForbiddenComplianceViolations(notifyForbiddenEvents);
                    httpConfig.addComplianceViolationListener(new MyComplianceListener(events));
                });

            server.setHandler(new EchoRequestCookiesHandler());
        });

        // Extra Cookie `\t` triggers forbidden SPECIAL_CHARS_IN_QUOTES violation
        String rawRequest = """
            GET /path/to/bad/cookie/forbidden HTTP/1.1\r
            Host: local\r
            Connection: close\r
            Cookie: name=value\r
            Cookie: foo="bar\t"\r
            \r
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        List<String> expectedEvents = new ArrayList<>();
        expectedEvents.add("CVL - initialize()");
        expectedEvents.add("REQ (GET http://local/path/to/bad/cookie/forbidden) - onRequestBegin()");
        if (notifyForbiddenEvents)
            expectedEvents.add("REQ (GET http://local/path/to/bad/cookie/forbidden) - onViolation() - CookieCompliance.SPECIAL_CHARS_IN_QUOTES (forbidden)");
        expectedEvents.add("REQ (GET http://local/path/to/bad/cookie/forbidden) - onViolation() - CookieCompliance.INVALID_COOKIES (allowed)");
        expectedEvents.add("REQ (GET http://local/path/to/bad/cookie/forbidden) - onViolation() - CookieCompliance.STRIPPED_QUOTES (allowed)");
        expectedEvents.add("REQ (GET http://local/path/to/bad/cookie/forbidden) - onRequestEnd()");

        await().atMost(5, TimeUnit.SECONDS).until(events::size, equalTo(expectedEvents.size()));

        assertEquals(200, response.getStatus(), rawResponse);
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("/path/to/bad/cookie/forbidden"));
        assertThat(responseBody, containsString("cookies.count=1"));
        assertThat(responseBody, containsString("name=value")); // "foo" cookie is INVALID and not seen
        assertThat(events, ordered(expectedEvents));
    }

    public abstract static class EventCapture
    {
        private static final Logger LOG = LoggerFactory.getLogger(EventCapture.class);
        private final Queue<String> events;

        public EventCapture(Queue<String> eventQueue)
        {
            this.events = eventQueue;
        }

        public Queue<String> getEventQueue()
        {
            return events;
        }

        protected void event(String format, Object... args)
        {
            String str = format.formatted(args);
            LOG.debug(str);
            events.add(str);
        }
    }

    private static String formatted(ComplianceViolation.Event event)
    {
        String type = "ComplianceViolation";
        if (event.violation() instanceof UriCompliance.Violation)
            type = "UriCompliance";
        if (event.violation() instanceof HttpCompliance.Violation)
            type = "HttpCompliance";
        if (event.violation() instanceof MultiPartCompliance.Violation)
            type = "MultiPartCompliance";
        if (event.violation() instanceof CookieCompliance.Violation)
            type = "CookieCompliance";
        return "%s.%s (%s)".formatted(
            type,
            event.violation().getName(),
            event.allowed() ? "allowed" : "forbidden");
    }

    public static class MyComplianceListener extends EventCapture implements ComplianceViolation.Listener
    {
        public MyComplianceListener(Queue<String> eventQueue)
        {
            super(eventQueue);
        }

        @Override
        public ComplianceViolation.Listener initialize()
        {
            event("CVL - initialize()");
            return new MyRequestComplianceListener(getEventQueue());
        }

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            event("CVL - onViolation() - " + formatted(event));
        }
    }

    public static class MyRequestComplianceListener extends EventCapture implements ComplianceViolation.Listener
    {
        private String requestId;

        public MyRequestComplianceListener(Queue<String> eventQueue)
        {
            super(eventQueue);
        }

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            event("REQ (%s) - onViolation() - %s", requestId, formatted(event));
        }

        @Override
        public void onRequestBegin(Attributes request)
        {
            requestId = toId(request);
            event("REQ (%s) - onRequestBegin()", requestId);
        }

        @Override
        public void onRequestEnd(Attributes request)
        {
            event("REQ (%s) - onRequestEnd()", toId(request));
        }

        private String toId(Attributes attr)
        {
            if (attr == null)
                return "null";

            if (attr instanceof Request req)
            {
                return "%s %s".formatted(req.getMethod(), req.getHttpURI().toString());
            }

            return attr.toString();
        }
    }

    public static class EchoRequestUriHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback)
        {
            try
            {
                String requestUri = request.getHttpURI().toURI().toASCIIString();
                Content.Sink.write(response, true, requestUri, callback);
                return true;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    public static class EchoRequestCookiesHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback)
        {
            try
            {
                StringBuilder str = new StringBuilder();
                str.append(request.getHttpURI().toURI().toASCIIString());
                List<HttpCookie> cookies = Request.getCookies(request);
                str.append("\ncookies.count=").append(cookies.size());
                for (HttpCookie cookie: cookies)
                {
                    str.append("\n").append(cookie.getName()).append("=").append(cookie.getValue());
                }
                Content.Sink.write(response, true, str.toString(), callback);
                return true;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    public static class EchoRequestHeadersHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback)
        {
            try
            {
                StringBuilder str = new StringBuilder();
                str.append(request.getHttpURI().toURI().toASCIIString());
                HttpFields httpFields = request.getHeaders();
                for (HttpField field: httpFields)
                {
                    String name = field.getName();
                    String value = field.getValue();
                    if (name.startsWith("X-"))
                    {
                        List<String> values = httpFields.getCSV(name, true);
                        value = String.join(", ", values);
                    }
                    str.append("\n").append(name).append("=").append(value);
                }
                Content.Sink.write(response, true, str.toString(), callback);
                return true;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }
}
