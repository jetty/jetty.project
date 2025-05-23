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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.DumpHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RequestTest
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        connector.setIdleTimeout(60000);
        server.addConnector(connector);
        server.setHandler(new DumpHandler());
        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(server);
        connector = null;
    }

    @Test
    public void testAsInContext() throws Exception
    {
        // no request
        assertNull(Request.asInContext(null, MockRequest.Wrapper.class));

        // request with no context is the same as Request.as
        MockRequest request = new MockRequest(new MockContext());
        MockContext wrappedContext = new MockContext();
        MockRequest.MockWrapper wrapperA = new MockRequest.MockWrapper(request, wrappedContext);
        MockRequest.MockWrapper wrapperB = new MockRequest.MockWrapper(wrapperA, null);
        Request asInContext = Request.asInContext(wrapperB, MockRequest.MockWrapper.class);
        Request as = Request.as(wrapperB, MockRequest.MockWrapper.class);
        assertSame(asInContext, as);
        assertSame(wrapperB, asInContext);
        assertSame(wrapperB, as);

        // request with context must not cross boundary
        wrapperB = new MockRequest.MockWrapper(wrapperA, new MockContext());
        Request.Wrapper wrapperC = new Request.Wrapper(wrapperB)
        {
            @Override
            public Context getContext()
            {
                return new MockContext();
            }
        };
        assertNull(Request.asInContext(wrapperC, MockRequest.MockWrapper.class));

        // request with same context in more than one wrap returns first matching class
        wrapperB = new MockRequest.MockWrapper(wrapperA, wrapperA.getContext());
        wrapperC = new Request.Wrapper(wrapperB)
        {
            @Override
            public Context getContext()
            {
                return getWrapped().getContext();
            }
        };

        assertSame(wrapperB, Request.asInContext(wrapperC, MockRequest.MockWrapper.class));
    }

    @Test
    public void testEncodedSpace() throws Exception
    {
        String request = """
                GET /fo%6f%20bar HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getContent(), containsString("httpURI.path=/fo%6f%20bar"));
        assertThat(response.getContent(), containsString("pathInContext=/foo%20bar"));
    }

    @Test
    public void testEncodedPath() throws Exception
    {
        String request = """
                GET /fo%6f%2fbar HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    public static Stream<Arguments> getUriTests()
    {
        return Stream.of(
            Arguments.of(UriCompliance.DEFAULT, "/", 200, "local"),
            Arguments.of(UriCompliance.DEFAULT, "https://local/", 200, "local"),
            Arguments.of(UriCompliance.DEFAULT, "https://other/", 400, "Authority!=Host"),
            Arguments.of(UriCompliance.UNSAFE, "https://other/", 200, "other"),
            Arguments.of(UriCompliance.DEFAULT, "https://user@local/", 400, "Deprecated User Info"),
            Arguments.of(UriCompliance.LEGACY, "https://user@local/", 200, "local"),
            Arguments.of(UriCompliance.LEGACY, "https://user@local:port/", 400, "Bad Request"),
            Arguments.of(UriCompliance.LEGACY, "https://user@local:8080/", 400, "Authority!=Host"),
            Arguments.of(UriCompliance.UNSAFE, "https://user@local:8080/", 200, "local:8080"),
            Arguments.of(UriCompliance.DEFAULT, "https://user:password@local/", 400, "Deprecated User Info"),
            Arguments.of(UriCompliance.LEGACY, "https://user:password@local/", 200, "local"),
            Arguments.of(UriCompliance.DEFAULT, "https://user@other/", 400, "Deprecated User Info"),
            Arguments.of(UriCompliance.LEGACY, "https://user@other/", 400, "Authority!=Host"),
            Arguments.of(UriCompliance.DEFAULT, "https://user:password@other/", 400, "Deprecated User Info"),
            Arguments.of(UriCompliance.LEGACY, "https://user:password@other/", 400, "Authority!=Host"),
            Arguments.of(UriCompliance.UNSAFE, "https://user:password@other/", 200, "other"),
            Arguments.of(UriCompliance.DEFAULT, "/%2F/", 400, "Ambiguous URI path separator"),
            Arguments.of(UriCompliance.UNSAFE, "/%2F/", 200, "local")
        );
    }

    @ParameterizedTest
    @MethodSource("getUriTests")
    public void testGETUris(UriCompliance compliance, String uri, int status, String content) throws Exception
    {
        server.stop();
        for (Connector connector: server.getConnectors())
        {
            HttpConnectionFactory httpConnectionFactory = connector.getConnectionFactory(HttpConnectionFactory.class);
            if (httpConnectionFactory != null)
            {
                HttpConfiguration httpConfiguration = httpConnectionFactory.getHttpConfiguration();
                httpConfiguration.setUriCompliance(compliance);
                if (compliance == UriCompliance.UNSAFE)
                    httpConfiguration.setHttpCompliance(HttpCompliance.RFC2616_LEGACY);
            }
        }

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String msg = String.format("authority=\"%s\"", request.getHttpURI().getAuthority());
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, msg, callback);
                return true;
            }
        });
        server.start();
        String request = """
                GET %s HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """.formatted(uri);
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertThat(response.getStatus(), is(status));
        if (content != null)
        {
            if (status == 200)
                assertThat(response.getContent(), is("authority=\"%s\"".formatted(content)));
            else
                assertThat(response.getContent(), containsString(content));
        }
    }

    @Test
    public void testAmbiguousPathSep() throws Exception
    {
        server.stop();
        for (Connector connector: server.getConnectors())
        {
            HttpConnectionFactory httpConnectionFactory = connector.getConnectionFactory(HttpConnectionFactory.class);
            if (httpConnectionFactory != null)
            {
                HttpConfiguration httpConfiguration = httpConnectionFactory.getHttpConfiguration();
                httpConfiguration.setUriCompliance(UriCompliance.from(
                    EnumSet.of(UriCompliance.Violation.AMBIGUOUS_PATH_SEPARATOR)
                ));
            }
        }

        ContextHandler fooContext = new ContextHandler();
        fooContext.setContextPath("/foo");
        fooContext.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String pathInContext = Request.getPathInContext(request);
                String msg = String.format("pathInContext=\"%s\"", pathInContext);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, msg, callback);
                return true;
            }
        });
        server.setHandler(fooContext);
        server.start();
        String request = """
                GET /foo/zed%2Fbar HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getContent(), is("pathInContext=\"/zed%2Fbar\""));
    }

    @Test
    public void testConnectRequestURLSameAsHost() throws Exception
    {
        String request = """
                CONNECT myhost:9999 HTTP/1.1\r
                Host: myhost:9999\r
                Connection: close\r
                \r
                """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("httpURI=http://myhost:9999/"));
        assertThat(responseBody, containsString("httpURI.path=/"));
        assertThat(responseBody, containsString("servername=myhost"));
    }

    @Test
    public void testConnectRequestURLDifferentThanHost() throws Exception
    {
        // per spec, "Host" is ignored if request-target is authority-form
        String request = """
                CONNECT myhost:9999 HTTP/1.1\r
                Host: otherhost:8888\r
                Connection: close\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("httpURI=http://myhost:9999/"));
        assertThat(responseBody, containsString("httpURI.path=/"));
        assertThat(responseBody, containsString("servername=myhost"));
    }

    /**
     * Test to ensure that response.write() will add Content-Length on HTTP/1.1 responses.
     */
    @Test
    public void testContentLengthNotSetOneWrites() throws Exception
    {
        final int bufferSize = 4096;
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                byte[] buf = new byte[bufferSize];
                Arrays.fill(buf, (byte)'x');

                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                response.write(true, ByteBuffer.wrap(buf), Callback.NOOP);
                return true;
            }
        });
        server.start();

        String request = """
                GET /foo HTTP/1.1\r
                Host: local\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getLongField(HttpHeader.CONTENT_LENGTH), greaterThan(0L));
        String responseBody = response.getContent();
        assertThat(responseBody.length(), is(bufferSize));
    }

    /**
     * Test to ensure that multiple response.write() will use
     * Transfer-Encoding chunked on HTTP/1.1 responses.
     */
    @Test
    public void testContentLengthNotSetTwoWrites() throws Exception
    {
        final int bufferSize = 4096;
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                byte[] buf = new byte[bufferSize];
                Arrays.fill(buf, (byte)'x');

                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");

                ByteBuffer bbuf = ByteBuffer.wrap(buf);
                int half = bufferSize / 2;
                ByteBuffer halfBuf = bbuf.slice();
                halfBuf.limit(half);
                response.write(false, halfBuf, Callback.from(() ->
                {
                    bbuf.position(half);
                    response.write(true, bbuf, callback);
                }));
                return true;
            }
        });
        server.start();

        String request = """
                GET /foo HTTP/1.1\r
                Host: local\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertNull(response.getField(HttpHeader.CONTENT_LENGTH));
        assertThat(response.get(HttpHeader.TRANSFER_ENCODING), containsString("chunked"));
        String responseBody = response.getContent();
        assertThat(responseBody.length(), is(bufferSize));
    }

    /**
     * Test that multiple requests on the same connection with different cookies
     * do not bleed cookies.
     * 
     * @throws Exception if there is a problem
     */
    @Test
    public void testDifferentCookies() throws Exception
    {
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                ByteArrayOutputStream buff = new ByteArrayOutputStream();
                List<HttpCookie> coreCookies = org.eclipse.jetty.server.Request.getCookies(request);

                if (coreCookies != null)
                {
                    for (HttpCookie c : coreCookies)
                        buff.writeBytes(("Core Cookie: " + c.getName() + "=" + c.getValue() + "\n").getBytes());
                }
                response.write(true, ByteBuffer.wrap(buff.toByteArray()), callback);
                return true;
            }
        });
        
        server.start();
        String sessionId1 = "JSESSIONID=node0o250bm47otmz1qjqqor54fj6h0.node0";
        String sessionId2 = "JSESSIONID=node0q4z00xb0pnyl1f312ec6e93lw1.node0";
        String sessionId3 = "JSESSIONID=node0gqgmw5fbijm0f9cid04b4ssw2.node0";
        String request1 = "GET /ctx HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId1 + "\r\n\r\n";
        String request2 = "GET /ctx HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId2 + "\r\n\r\n";
        String request3 = "GET /ctx HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId3 + "\r\n\r\n";
        
        try (LocalEndPoint lep = connector.connect())
        {
            lep.addInput(request1);
            HttpTester.Response response = HttpTester.parseResponse(lep.getResponse());
            checkCookieResult(sessionId1, new String[]{sessionId2, sessionId3}, response.getContent());
            lep.addInput(request2);
            response = HttpTester.parseResponse(lep.getResponse());
            checkCookieResult(sessionId2, new String[]{sessionId1, sessionId3}, response.getContent());
            lep.addInput(request3);
            response = HttpTester.parseResponse(lep.getResponse());
            checkCookieResult(sessionId3, new String[]{sessionId1, sessionId2}, response.getContent());
        }
    }

    /**
     * Test for GET behavior on persistent connection (not Connection: close)
     *
     * @throws Exception if there is a problem
     */
    @Test
    public void testGETNoConnectionClose() throws Exception
    {
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                byte[] buf = new byte[4096];
                Arrays.fill(buf, (byte)'x');
                response.write(true, ByteBuffer.wrap(buf), callback);
                return true;
            }
        });

        server.start();

        String rawRequest = """
            GET / HTTP/1.1
            Host: tester
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    /**
     * Test for HEAD behavior on persistent connection (not Connection: close)
     *
     * @throws Exception if there is a problem
     */
    @Test
    public void testHEADNoConnectionClose() throws Exception
    {
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                byte[] buf = new byte[4096];
                Arrays.fill(buf, (byte)'x');
                response.write(true, ByteBuffer.wrap(buf), callback);
                return true;
            }
        });

        server.start();

        String rawRequest = """
                HEAD / HTTP/1.1
                Host: tester

                """;

        LocalConnector.LocalEndPoint localEndPoint = connector.executeRequest(rawRequest);
        ByteBuffer rawResponse = localEndPoint.waitForResponse(true, 2, TimeUnit.SECONDS);
        HttpTester.Response response = HttpTester.parseHeadResponse(rawResponse);
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    /**
     * Test for HEAD behavior on persistent connection (not Connection: close)
     *
     * @throws Exception if there is a problem
     */
    @Test
    public void testHEADWithConnectionClose() throws Exception
    {
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                byte[] buf = new byte[4096];
                Arrays.fill(buf, (byte)'x');
                response.write(true, ByteBuffer.wrap(buf), callback);
                return true;
            }
        });

        server.start();

        String rawRequest = """
                HEAD / HTTP/1.1
                Host: tester
                Connection: close
                            
                """;

        HttpTester.Response response = HttpTester.parseHeadResponse(connector.getResponse(rawRequest));
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    public static Stream<Arguments> localeTests()
    {
        return Stream.of(
            Arguments.of(null, List.of(Locale.getDefault().toLanguageTag()).toString()),
            Arguments.of("zz", "[zz]"),
            Arguments.of("en", "[en]"),
            Arguments.of("en-gb", List.of(Locale.UK.toLanguageTag()).toString()),
            Arguments.of("en-us", List.of(Locale.US.toLanguageTag()).toString()),
            Arguments.of("EN-US", List.of(Locale.US.toLanguageTag()).toString()),
            Arguments.of("en-us,en-gb", List.of(Locale.US.toLanguageTag(), Locale.UK.toLanguageTag()).toString()),
            Arguments.of("en-us;q=0.5,fr;q=0.0,en-gb;q=1.0", List.of(Locale.UK.toLanguageTag(), Locale.US.toLanguageTag()).toString()),
            Arguments.of("en-us;q=0.5,zz-yy;q=0.7,en-gb;q=1.0", List.of(Locale.UK.toLanguageTag(), Locale.US.toLanguageTag(), "zz-YY").toString())
        );
    }

    @ParameterizedTest
    @MethodSource("localeTests")
    public void testAcceptableLocales(String acceptLanguage, String expectedLocales) throws Exception
    {
        acceptLanguage = acceptLanguage == null ? "" : (HttpHeader.ACCEPT_LANGUAGE.asString() + ": " + acceptLanguage + "\n");
        String rawRequest = """
                GET / HTTP/1.1
                Host: tester
                Connection: close
                %s
                """.formatted(acceptLanguage);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("locales=" + expectedLocales));
    }

    private static void checkCookieResult(String containedCookie, String[] notContainedCookies, String response)
    {
        assertNotNull(containedCookie);
        assertNotNull(response);
        assertThat(response, containsString("Core Cookie: " + containedCookie));
        if (notContainedCookies != null)
        {
            for (String notContainsCookie : notContainedCookies)
            {
                assertThat(response, not(containsString(notContainsCookie)));
            }
        }
    }

    /**
     * The set of behaviors collected from Jetty 11.
     */
    public static Stream<Arguments> queryBehaviorsLegacy()
    {
        List<Arguments> cases = new ArrayList<>();

        // Normal cases
        cases.add(Arguments.of("param=aaa", 200, "param", "aaa"));
        cases.add(Arguments.of("param=aaa&other=foo", 200, "param", "aaa"));
        cases.add(Arguments.of("param=", 200, "param", ""));
        cases.add(Arguments.of("param=&other=foo", 200, "param", ""));
        cases.add(Arguments.of("param=%E2%9C%94", 200, "param", "✔"));
        cases.add(Arguments.of("param=%E2%9C%94&other=foo", 200, "param", "✔"));

        // Truncated / Insufficient Hex cases
        cases.add(Arguments.of("param=%E2%9C%9", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%9C%9&other=foo", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%9C%", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%9C%&other=foo", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%9C", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%9C&other=foo", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%9", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%9&other=foo", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%&other=foo", 400, "param", ""));
        cases.add(Arguments.of("param=%E2", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2&other=foo", 200, "param", "�"));

        // Tokenized cases
        cases.add(Arguments.of("param=%%TOK%%", 400, "param", ""));
        cases.add(Arguments.of("param=%%TOK%%&other=foo", 400, "param", ""));

        // Bad Hex
        cases.add(Arguments.of("param=%xx", 400, "param", ""));
        cases.add(Arguments.of("param=%xx&other=foo", 400, "param", ""));

        // Overlong UTF-8 Encoding
        cases.add(Arguments.of("param=%C0%AF", 400, "param", ""));
        cases.add(Arguments.of("param=%C0%AF&other=foo", 400, "param", ""));

        // Out of range
        cases.add(Arguments.of("param=%F4%90%80%80", 400, "param", ""));
        cases.add(Arguments.of("param=%F4%90%80%80&other=foo", 400, "param", ""));

        // Long surrogate
        cases.add(Arguments.of("param=%ED%A0%80", 400, "param", ""));
        cases.add(Arguments.of("param=%ED%A0%80&other=foo", 400, "param", ""));

        // Standalone continuations
        cases.add(Arguments.of("param=%80", 400, "param", ""));
        cases.add(Arguments.of("param=%80&other=foo", 400, "param", ""));

        // Truncated sequence
        cases.add(Arguments.of("param=%E2%82", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%82&other=foo", 200, "param", "�"));

        // C1 never starts UTF-8
        cases.add(Arguments.of("param=%C1%BF", 400, "param", ""));
        cases.add(Arguments.of("param=%C1%BF&other=foo", 400, "param", ""));

        // E0 must be followed by A0-BF
        cases.add(Arguments.of("param=%E0%9F%80", 400, "param", ""));
        cases.add(Arguments.of("param=%E0%9F%80&other=foo", 400, "param", ""));

        // Community Examples
        cases.add(Arguments.of("param=f_%e0%b8", 200, "param", "f_�"));
        cases.add(Arguments.of("param=f_%e0%b8&other=foo", 200, "param", "f_�"));
        cases.add(Arguments.of("param=%£", 400, "param", ""));
        cases.add(Arguments.of("param=%£&other=foo", 400, "param", ""));

        // Extra ampersands
        cases.add(Arguments.of("param=aaa&&&", 200, "param", "aaa"));
        cases.add(Arguments.of("&&&param=aaa", 200, "param", "aaa"));
        cases.add(Arguments.of("&&param=aaa&&other=foo", 200, "param", "aaa"));
        cases.add(Arguments.of("param=aaa&&other=foo&&", 200, "param", "aaa"));

        // Encoded ampersands
        cases.add(Arguments.of("param=aaa%26&other=foo", 200, "param", "aaa&"));
        cases.add(Arguments.of("param=aaa&%26other=foo", 200, "&other", "foo"));

        // pct-encoded parameter names ("帽子" means "hat" in japanese)
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%90=Beret", 200, "帽子", "Beret"));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%90=Beret&other=foo", 200, "帽子", "Beret"));
        cases.add(Arguments.of("other=foo&%E5%B8%BD%E5%AD%90=Beret", 200, "帽子", "Beret"));

        // truncated pct-encoded parameter names
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%9=Beret", 400, "", "")); // Not LEGACY
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%=Beret", 400, "帽子", ""));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD=Beret", 200, "帽�", "Beret"));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%9=Beret&other=foo", 400, "帽孝Beret", "")); // Not LEGACY
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%=Beret&other=foo", 400, "帽子", ""));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD=Beret&other=foo", 200, "帽�", "Beret"));

        // raw unicode parameter names (strange replacement logic here)
        cases.add(Arguments.of("€=currency", 200, "?", "currency"));
        cases.add(Arguments.of("帽子=Beret", 200, "??", "Beret"));

        // invalid pct-encoded parameter name
        cases.add(Arguments.of("foo%xx=abc", 400, "foo�", ""));
        cases.add(Arguments.of("foo%x=abc", 400, "foo�", ""));
        cases.add(Arguments.of("foo%=abc", 400, "foo�", ""));

        // utf-16 values (LEGACY has UTF16_ENCODINGS enabled, but it doesn't work for query apparently)
        cases.add(Arguments.of("foo=a%u2192z", 400, "foo", ""));

        // truncated utf-16 values (LEGACY has UTF16_ENCODINGS enabled, but it doesn't work for query apparently)
        cases.add(Arguments.of("foo=a%u219z", 400, "foo", ""));

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("queryBehaviorsLegacy")
    public void testQueryExtractionBehaviorLegacy(String inputQuery, int expectedStatus, String expectedKey, String expectedValue) throws Exception
    {
        UriCompliance uriCompliance = UriCompliance.LEGACY;
        testQueryExtractionBehavior(uriCompliance, inputQuery, expectedStatus, expectedKey, expectedValue);
    }

    /**
     * Behaviors as they existed in Jetty 12.0.16
     */
    public static Stream<Arguments> queryBehaviorsDefault()
    {
        List<Arguments> cases = new ArrayList<>();

        // Normal cases
        cases.add(Arguments.of("param=aaa", 200, "param", "aaa"));
        cases.add(Arguments.of("param=aaa&other=foo", 200, "param", "aaa"));
        cases.add(Arguments.of("param=", 200, "param", ""));
        cases.add(Arguments.of("param=&other=foo", 200, "param", ""));
        cases.add(Arguments.of("param=%E2%9C%94", 200, "param", "✔"));
        cases.add(Arguments.of("param=%E2%9C%94&other=foo", 200, "param", "✔"));

        // Truncated / Insufficient Hex cases
        cases.add(Arguments.of("param=%E2%9C%9", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%9C%9&other=foo", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%9C%", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%9C%&other=foo", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%9C", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%9C&other=foo", 400, "param", "�"));
        cases.add(Arguments.of("param=%E2%9", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%9&other=foo", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%&other=foo", 400, "param", ""));
        cases.add(Arguments.of("param=%E2", 400, "param", ""));
        cases.add(Arguments.of("param=%E2&other=foo", 400, "param", ""));

        // Tokenized cases
        cases.add(Arguments.of("param=%%TOK%%", 400, "param", ""));
        cases.add(Arguments.of("param=%%TOK%%&other=foo", 400, "param", ""));

        // Bad Hex
        cases.add(Arguments.of("param=%xx", 400, "param", ""));
        cases.add(Arguments.of("param=%xx&other=foo", 400, "param", ""));

        // Overlong UTF-8 Encoding
        cases.add(Arguments.of("param=%C0%AF", 400, "param", ""));
        cases.add(Arguments.of("param=%C0%AF&other=foo", 400, "param", ""));

        // Out of range
        cases.add(Arguments.of("param=%F4%90%80%80", 400, "param", ""));
        cases.add(Arguments.of("param=%F4%90%80%80&other=foo", 400, "param", ""));

        // Long surrogate
        cases.add(Arguments.of("param=%ED%A0%80", 400, "param", ""));
        cases.add(Arguments.of("param=%ED%A0%80&other=foo", 400, "param", ""));

        // Standalone continuations
        cases.add(Arguments.of("param=%80", 400, "param", ""));
        cases.add(Arguments.of("param=%80&other=foo", 400, "param", ""));

        // Truncated sequence
        cases.add(Arguments.of("param=%E2%82", 400, "param", ""));
        cases.add(Arguments.of("param=%E2%82&other=foo", 400, "param", ""));

        // C1 never starts UTF-8
        cases.add(Arguments.of("param=%C1%BF", 400, "param", ""));
        cases.add(Arguments.of("param=%C1%BF&other=foo", 400, "param", ""));

        // E0 must be followed by A0-BF
        cases.add(Arguments.of("param=%E0%9F%80", 400, "param", ""));
        cases.add(Arguments.of("param=%E0%9F%80&other=foo", 400, "param", ""));

        // Community Examples
        cases.add(Arguments.of("param=f_%e0%b8", 400, "param", ""));
        cases.add(Arguments.of("param=f_%e0%b8&other=foo", 400, "param", ""));
        cases.add(Arguments.of("param=%£", 400, "param", ""));
        cases.add(Arguments.of("param=%£&other=foo", 400, "param", ""));

        // Extra ampersands
        cases.add(Arguments.of("param=aaa&&&", 200, "param", "aaa"));
        cases.add(Arguments.of("&&&param=aaa", 200, "param", "aaa"));
        cases.add(Arguments.of("&&param=aaa&&other=foo", 200, "param", "aaa"));
        cases.add(Arguments.of("param=aaa&&other=foo&&", 200, "param", "aaa"));

        // Encoded ampersands
        cases.add(Arguments.of("param=aaa%26&other=foo", 200, "param", "aaa&"));
        cases.add(Arguments.of("param=aaa&%26other=foo", 200, "&other", "foo"));

        // pct-encoded parameter names ("帽子" means "hat" in japanese)
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%90=Beret", 200, "帽子", "Beret"));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%90=Beret&other=foo", 200, "帽子", "Beret"));
        cases.add(Arguments.of("other=foo&%E5%B8%BD%E5%AD%90=Beret", 200, "帽子", "Beret"));

        // bad pct-encoded parameter names
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%9=Beret", 400, "帽子", null));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%=Beret", 400, "帽子", null));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD=Beret", 400, "帽子", null));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%9=Beret&other=foo", 400, "帽子", null));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%=Beret&other=foo", 400, "帽子", null));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD=Beret&other=foo", 400, "帽子", null));

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("queryBehaviorsDefault")
    public void testQueryExtractionBehaviorDefault(String inputQuery, int expectedStatus, String expectedKey, String expectedValue) throws Exception
    {
        UriCompliance uriCompliance = UriCompliance.DEFAULT;
        testQueryExtractionBehavior(uriCompliance, inputQuery, expectedStatus, expectedKey, expectedValue);
    }

    public static Stream<Arguments> queryBehaviorsBadUtf8Allowed()
    {
        List<Arguments> cases = new ArrayList<>();

        // Normal cases
        cases.add(Arguments.of("param=aaa", 200, "param", "aaa"));
        cases.add(Arguments.of("param=aaa&other=foo", 200, "param", "aaa"));
        cases.add(Arguments.of("param=", 200, "param", ""));
        cases.add(Arguments.of("param=&other=foo", 200, "param", ""));
        cases.add(Arguments.of("param=%E2%9C%94", 200, "param", "✔"));
        cases.add(Arguments.of("param=%E2%9C%94&other=foo", 200, "param", "✔"));

        // Truncated / Insufficient Hex cases
        cases.add(Arguments.of("param=%E2%9C%9", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%9C%9&other=foo", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%9C%", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%9C%&other=foo", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%9C", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%9C&other=foo", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%9", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%9&other=foo", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%&other=foo", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2&other=foo", 200, "param", "�"));

        // Tokenized cases
        cases.add(Arguments.of("param=%%TOK%%", 200, "param", "%%TOK%%"));
        cases.add(Arguments.of("param=%%TOK%%&other=foo", 200, "param", "%%TOK%%"));

        // Bad Hex
        cases.add(Arguments.of("param=%xx", 200, "param", "%xx"));
        cases.add(Arguments.of("param=%xx&other=foo", 200, "param", "%xx"));

        // Overlong UTF-8 Encoding
        cases.add(Arguments.of("param=%C0%AF", 200, "param", "��"));
        cases.add(Arguments.of("param=%C0%AF&other=foo", 200, "param", "��"));

        // Out of range
        cases.add(Arguments.of("param=%F4%90%80%80", 200, "param", "����"));
        cases.add(Arguments.of("param=%F4%90%80%80&other=foo", 200, "param", "����"));

        // Long surrogate
        cases.add(Arguments.of("param=%ED%A0%80", 200, "param", "���"));
        cases.add(Arguments.of("param=%ED%A0%80&other=foo", 200, "param", "���"));

        // Standalone continuations
        cases.add(Arguments.of("param=%80", 200, "param", "�"));
        cases.add(Arguments.of("param=%80&other=foo", 200, "param", "�"));

        // Truncated sequence
        cases.add(Arguments.of("param=%E2%82", 200, "param", "�"));
        cases.add(Arguments.of("param=%E2%82&other=foo", 200, "param", "�"));

        // C1 never starts UTF-8
        cases.add(Arguments.of("param=%C1%BF", 200, "param", "��"));
        cases.add(Arguments.of("param=%C1%BF&other=foo", 200, "param", "��"));

        // E0 must be followed by A0-BF
        cases.add(Arguments.of("param=%E0%9F%80", 200, "param", "���"));
        cases.add(Arguments.of("param=%E0%9F%80&other=foo", 200, "param", "���"));

        // Community Examples
        cases.add(Arguments.of("param=f_%e0%b8", 200, "param", "f_�"));
        cases.add(Arguments.of("param=f_%e0%b8&other=foo", 200, "param", "f_�"));
        cases.add(Arguments.of("param=%x", 200, "param", "%x"));
        cases.add(Arguments.of("param=%£", 200, "param", "%�"));
        cases.add(Arguments.of("param=%x&other=foo", 200, "param", "%x"));
        cases.add(Arguments.of("param=%£&other=foo", 200, "param", "%�"));

        // Extra ampersands
        cases.add(Arguments.of("param=aaa&&&", 200, "param", "aaa"));
        cases.add(Arguments.of("&&&param=aaa", 200, "param", "aaa"));
        cases.add(Arguments.of("&&param=aaa&&other=foo", 200, "param", "aaa"));
        cases.add(Arguments.of("param=aaa&&other=foo&&", 200, "param", "aaa"));

        // Encoded ampersands
        cases.add(Arguments.of("param=aaa%26&other=foo", 200, "param", "aaa&"));
        cases.add(Arguments.of("param=aaa&%26other=foo", 200, "&other", "foo"));

        // pct-encoded parameter names ("帽子" means "hat" in japanese)
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%90=Beret", 200, "帽子", "Beret"));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%90=Beret&other=foo", 200, "帽子", "Beret"));
        cases.add(Arguments.of("other=foo&%E5%B8%BD%E5%AD%90=Beret", 200, "帽子", "Beret"));

        // bad pct-encoded parameter names
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%9=Beret", 200, "帽�", "Beret"));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%=Beret", 200, "帽�", "Beret"));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD=Beret", 200, "帽�", "Beret"));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%9=Beret&other=foo", 200, "帽�", "Beret"));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD%=Beret&other=foo", 200, "帽�", "Beret"));
        cases.add(Arguments.of("%E5%B8%BD%E5%AD=Beret&other=foo", 200, "帽�", "Beret"));

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("queryBehaviorsBadUtf8Allowed")
    public void testQueryExtractionBehaviorBadUtf8Allowed(String inputQuery, int expectedStatus, String expectedKey, String expectedValue) throws Exception
    {
        UriCompliance uriCompliance = UriCompliance.DEFAULT.with("test", UriCompliance.Violation.BAD_UTF8_ENCODING,
            UriCompliance.Violation.BAD_PERCENT_ENCODING, UriCompliance.Violation.TRUNCATED_UTF8_ENCODING);
        testQueryExtractionBehavior(uriCompliance, inputQuery, expectedStatus, expectedKey, expectedValue);
    }

    private void testQueryExtractionBehavior(UriCompliance uriCompliance, String inputQuery, int expectedStatus, String expectedKey, String expectedValue) throws Exception
    {
        server.stop();
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(uriCompliance);

        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (expectedStatus == 200)
                {
                    try
                    {
                        Fields fields = Request.extractQueryParameters(request);
                        Fields.Field field = fields.get(expectedKey);
                        assertNotNull(field);
                        String value = field.getValue();

                        if (expectedValue == null)
                        {
                            assertThat(field.getValue(), is(""));
                        }
                        else
                        {
                            assertThat(value, is(expectedValue));
                        }
                        response.setStatus(200);
                        callback.succeeded();
                        return true;
                    }
                    catch (Throwable t)
                    {
                        callback.failed(t);
                        return true;
                    }
                }
                else
                {
                    System.err.println(Request.extractQueryParameters(request));
                    RuntimeException e = assertThrows(RuntimeException.class, () -> Request.extractQueryParameters(request));
                    callback.failed(e);
                }
                return true;
            }
        });
        server.start();

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String rawRequest = String.format("GET /?%s HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Connection: close\n" +
            "\n", inputQuery);

        String rawResponse = connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(expectedStatus));
    }

    static Stream<Arguments> suspiciousCharactersLegacy()
    {
        return Stream.of(
            Arguments.of("o", "o", "o", UriCompliance.DEFAULT),
            Arguments.of("%5C", "400", "400", UriCompliance.DEFAULT),
            Arguments.of("%0A", "400", "400", UriCompliance.DEFAULT),
            Arguments.of("%00", "400", "400", UriCompliance.DEFAULT),
            Arguments.of("%01", "400", "400", UriCompliance.DEFAULT),
            Arguments.of("%5F", "_", "_", UriCompliance.DEFAULT),
            Arguments.of("%2F", "400", "400", UriCompliance.DEFAULT),
            Arguments.of("%252F", "400", "400", UriCompliance.DEFAULT),
            Arguments.of("//", "400", "400", UriCompliance.DEFAULT),

            // these results are from jetty-11 DEFAULT
            Arguments.of("o", "o", "o", UriCompliance.JETTY_11),
            Arguments.of("%5C", "%5C", "\\", UriCompliance.JETTY_11),
            Arguments.of("%0A", "%0A", "\n", UriCompliance.JETTY_11),
            Arguments.of("%00", "400", "400", UriCompliance.JETTY_11),
            Arguments.of("%01", "%01", "\u0001", UriCompliance.JETTY_11),
            Arguments.of("%5F", "_", "_", UriCompliance.JETTY_11),
            Arguments.of("%2F", "%2F", "/", UriCompliance.JETTY_11),
            Arguments.of("%252F", "%252F", "%2F", UriCompliance.JETTY_11),
            Arguments.of("//", "400", "400", UriCompliance.JETTY_11),

            // these results are from jetty-11 LEGACY
            Arguments.of("o", "o", "o", UriCompliance.LEGACY),
            Arguments.of("%5C", "%5C", "\\", UriCompliance.LEGACY),
            Arguments.of("%0A", "%0A", "\n", UriCompliance.LEGACY),
            Arguments.of("%00", "400", "400", UriCompliance.LEGACY),
            Arguments.of("%01", "%01", "\u0001", UriCompliance.LEGACY),
            Arguments.of("%5F", "_", "_", UriCompliance.LEGACY),
            Arguments.of("%2F", "%2F", "/", UriCompliance.LEGACY),
            Arguments.of("%252F", "%252F", "%2F", UriCompliance.LEGACY),
            Arguments.of("//", "//", "//", UriCompliance.LEGACY)
        );
    }

    @ParameterizedTest
    @MethodSource("suspiciousCharactersLegacy")
    public void testSuspiciousCharactersLegacy(String suspect, String canonical, String decoded, UriCompliance compliance) throws Exception
    {
        server.stop();
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(compliance);
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (decoded.length() != 3 || !Character.isDigit(decoded.charAt(0)))
                {
                    assertThat(request.getHttpURI().getCanonicalPath(), is("/test/fo" + canonical + "bar"));
                    assertThat(request.getHttpURI().getDecodedPath(), is("/test/fo" + decoded + "bar"));
                }
                callback.succeeded();
                return true;
            }
        });
        server.start();

        String request = "GET /test/fo" + suspect + "bar HTTP/1.0\r\n" +
            "Host: whatever\r\n" +
            "\r\n";
        String response = connector.getResponse(request);

        if (decoded.length() == 3 && Character.isDigit(decoded.charAt(0)))
            assertThat(response, startsWith("HTTP/1.1 " + decoded + " "));
        else
            assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }
}