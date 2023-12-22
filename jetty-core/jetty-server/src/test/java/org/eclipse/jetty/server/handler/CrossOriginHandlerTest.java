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

import java.util.List;
import java.util.Set;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CrossOriginHandlerTest
{
    private Server server;
    private LocalConnector connector;

    public void start(CrossOriginHandler crossOriginHandler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
        ContextHandler context = new ContextHandler("/");
        server.setHandler(context);
        context.setHandler(crossOriginHandler);
        crossOriginHandler.setHandler(new ApplicationHandler());
        server.start();
    }

    @AfterEach
    public void destroy()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testRequestWithNoOriginArrivesToApplication() throws Exception
    {
        start(new CrossOriginHandler());

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertThat(response.get(HttpHeader.VARY), is(HttpHeader.ORIGIN.asString()));
    }

    @Test
    public void testSimpleRequestWithNonMatchingOrigin() throws Exception
    {
        String origin = "http://localhost";
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedOriginPatterns(Set.of(origin));
        start(crossOriginHandler);

        String otherOrigin = StringUtil.replace(origin, "localhost", "127.0.0.1");
        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: %s\r
            \r
            """.formatted(otherOrigin);
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertFalse(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertFalse(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    public void testSimpleRequestWithWildcardOrigin() throws Exception
    {
        String origin = "http://foo.example.com";
        start(new CrossOriginHandler());

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: %s\r
            \r
            """.formatted(origin);
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertTrue(response.contains(HttpHeader.VARY));
    }

    @Test
    public void testSimpleRequestWithMatchingWildcardOrigin() throws Exception
    {
        String origin = "http://subdomain.example.com";
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedOriginPatterns(Set.of("http://.*\\.example\\.com"));
        start(crossOriginHandler);

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: %s\r
            \r
            """.formatted(origin);
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertTrue(response.contains(HttpHeader.VARY));
    }

    @Test
    public void testSimpleRequestWithMatchingWildcardOriginAndMultipleSubdomains() throws Exception
    {
        String origin = "http://subdomain.subdomain.example.com";
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedOriginPatterns(Set.of("http://.*\\.example\\.com"));
        start(crossOriginHandler);

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: %s\r
            \r
            """.formatted(origin);
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertTrue(response.contains(HttpHeader.VARY));
    }

    @Test
    public void testSimpleRequestWithMatchingOriginAndWithoutTimingOrigin() throws Exception
    {
        String origin = "http://localhost";
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedOriginPatterns(Set.of(origin));
        start(crossOriginHandler);

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: %s\r
            \r
            """.formatted(origin);
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertFalse(response.contains(HttpHeader.TIMING_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.VARY));
    }

    @Test
    public void testSimpleRequestWithMatchingOriginAndNonMatchingTimingOrigin() throws Exception
    {
        String origin = "http://localhost";
        String timingOrigin = "http://127.0.0.1";
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedOriginPatterns(Set.of(origin));
        crossOriginHandler.setAllowedTimingOriginPatterns(Set.of(timingOrigin.replace(".", "\\.")));
        start(crossOriginHandler);

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: %s\r
            \r
            """.formatted(origin);
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertFalse(response.contains(HttpHeader.TIMING_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.VARY));
    }

    @Test
    public void testSimpleRequestWithMatchingOriginAndMatchingTimingOrigin() throws Exception
    {
        String origin = "http://localhost";
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedOriginPatterns(Set.of(origin));
        crossOriginHandler.setAllowedTimingOriginPatterns(Set.of(origin));
        start(crossOriginHandler);

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: %s\r
            \r
            """.formatted(origin);
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertTrue(response.contains(HttpHeader.TIMING_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.VARY));
    }

    @Test
    public void testSimpleRequestWithMatchingMultipleOrigins() throws Exception
    {
        String origin = "http://localhost";
        String otherOrigin = StringUtil.replace(origin, "localhost", "127.0.0.1");
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedOriginPatterns(Set.of(origin, otherOrigin.replace(".", "\\.")));
        start(crossOriginHandler);

        // Use 2 spaces as separator in the Origin header
        // to test that the implementation does not fail.
        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: %s  %s\r
            \r
            """.formatted(otherOrigin, origin);
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertTrue(response.contains(HttpHeader.VARY));
    }

    @Test
    public void testSimpleRequestWithoutCredentials() throws Exception
    {
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowCredentials(false);
        start(crossOriginHandler);

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: http://localhost\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertFalse(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    public void testNonSimpleRequestWithoutPreflight() throws Exception
    {
        // We cannot know if an actual request has performed the preflight before:
        // we'll trust browsers to do it right, so responses to actual requests
        // will contain the CORS response headers.

        start(new CrossOriginHandler());

        String request = """
            PUT / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: http://localhost\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    public void testOptionsRequestButNotPreflight() throws Exception
    {
        // We cannot know if an actual request has performed the preflight before:
        // we'll trust browsers to do it right, so responses to actual requests
        // will contain the CORS response headers.

        start(new CrossOriginHandler());

        String request = """
            OPTIONS / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: http://localhost\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    public void testPreflightWithWildcardCustomHeaders() throws Exception
    {
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedHeaders(Set.of("*"));
        start(crossOriginHandler);

        String request = """
            OPTIONS / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Access-Control-Request-Headers: X-Foo-Bar\r
            Access-Control-Request-Method: GET\r
            Origin: http://localhost\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertFalse(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    public void testPUTRequestWithPreflight() throws Exception
    {
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedMethods(Set.of("PUT"));
        start(crossOriginHandler);

        // Preflight request.
        String request = """
            OPTIONS / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Access-Control-Request-Method: PUT\r
            Origin: http://localhost\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertFalse(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_MAX_AGE));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_METHODS));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS));

        // Preflight request was ok, now make the actual request.
        request = """
            PUT / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: http://localhost\r
            \r
            """;
        response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    public void testDELETERequestWithPreflightAndAllowedCustomHeaders() throws Exception
    {
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedMethods(Set.of("GET", "HEAD", "POST", "PUT", "DELETE"));
        crossOriginHandler.setAllowedHeaders(Set.of("X-Requested-With"));
        start(crossOriginHandler);

        // Preflight request.
        String request = """
            OPTIONS / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Access-Control-Request-Method: DELETE\r
            Access-Control-Request-Headers: origin,x-custom,x-requested-with\r
            Origin: http://localhost\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertFalse(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_MAX_AGE));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_METHODS));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS));

        // Preflight request was ok, now make the actual request.
        request = """
            DELETE / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            X-Custom: value\r
            X-Requested-With: local\r
            Origin: http://localhost\r
            \r
            """;
        response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    public void testDELETERequestWithPreflightAndNotAllowedCustomHeaders() throws Exception
    {
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedMethods(Set.of("GET", "HEAD", "POST", "PUT", "DELETE"));
        start(crossOriginHandler);

        // Preflight request.
        String request = """
            OPTIONS / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Access-Control-Request-Method: DELETE\r
            Access-Control-Request-Headers: origin, x-custom, x-requested-with\r
            Origin: http://localhost\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertFalse(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN));
        List<String> allowedHeaders = response.getValuesList(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS);
        assertFalse(allowedHeaders.contains("x-custom"));
        // The preflight request failed because header X-Custom is not allowed, actual request not issued.
    }

    @Test
    public void testSimpleRequestWithExposedHeaders() throws Exception
    {
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setExposedHeaders(Set.of("Content-Length"));
        start(crossOriginHandler);

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Origin: http://localhost\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(ApplicationHandler.APPLICATION_HEADER));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_EXPOSE_HEADERS));
    }

    @Test
    public void testDoNotDeliverPreflightRequest() throws Exception
    {
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setDeliverPreflightRequest(false);
        start(crossOriginHandler);

        // Preflight request.
        String request = """
            OPTIONS / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            Access-Control-Request-Method: PUT\r
            Origin: http://localhost\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertTrue(response.contains(HttpHeader.ACCESS_CONTROL_ALLOW_METHODS));
        assertFalse(response.contains(ApplicationHandler.APPLICATION_HEADER));
    }

    public static class ApplicationHandler extends Handler.Abstract
    {
        private static final String APPLICATION_HEADER = "X-Application";

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().put(APPLICATION_HEADER, "true");
            callback.succeeded();
            return true;
        }
    }
}
