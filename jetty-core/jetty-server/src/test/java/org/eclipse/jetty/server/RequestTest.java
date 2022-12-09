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

package org.eclipse.jetty.server;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.DumpHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getContent(), containsString("httpURI.path=/fo%6f%2fbar"));
        assertThat(response.getContent(), containsString("pathInContext=/foo%2Fbar"));
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
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                byte[] buf = new byte[bufferSize];
                Arrays.fill(buf, (byte)'x');

                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                response.write(true, ByteBuffer.wrap(buf), Callback.NOOP);
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
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
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
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                ByteArrayOutputStream buff = new ByteArrayOutputStream();

                request.getHeaders().getFields(HttpHeader.COOKIE).forEach(System.err::println);
                List<HttpCookie> coreCookies = org.eclipse.jetty.server.Request.getCookies(request);

                if (coreCookies != null)
                {
                    for (HttpCookie c : coreCookies)
                        buff.writeBytes(("Core Cookie: " + c.getName() + "=" + c.getValue() + "\n").getBytes());
                }
                response.write(true, ByteBuffer.wrap(buff.toByteArray()), callback);
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
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                byte[] buf = new byte[4096];
                Arrays.fill(buf, (byte)'x');
                response.write(true, ByteBuffer.wrap(buf), callback);
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
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                byte[] buf = new byte[4096];
                Arrays.fill(buf, (byte)'x');
                response.write(true, ByteBuffer.wrap(buf), callback);
            }
        });

        server.start();

        String rawRequest = """
                HEAD / HTTP/1.1
                Host: tester

                """;

        LocalConnector.LocalEndPoint localEndPoint = connector.executeRequest(rawRequest);

        ByteBuffer rawResponse = localEndPoint.waitForResponse(true, 2, TimeUnit.SECONDS);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
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
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                byte[] buf = new byte[4096];
                Arrays.fill(buf, (byte)'x');
                response.write(true, ByteBuffer.wrap(buf), callback);
            }
        });

        server.start();

        String rawRequest = """
                HEAD / HTTP/1.1
                Host: tester
                Connection: close
                            
                """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
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
}
