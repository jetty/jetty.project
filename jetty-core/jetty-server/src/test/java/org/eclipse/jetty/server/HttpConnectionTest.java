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

/*
 * Created on 9/01/2004
 *
 * To change the template for this generated file go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.DumpHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpConnectionTest
{
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(HttpConnectionTest.class);
    private Server _server;
    private LocalConnector _connector;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();

        HttpConfiguration config = new HttpConfiguration();
        config.setRequestHeaderSize(1024);
        config.setResponseHeaderSize(1024);
        config.setSendDateHeader(true);
        HttpConnectionFactory http = new HttpConnectionFactory(config);

        _connector = new LocalConnector(_server, http, null);
        _connector.setIdleTimeout(5000);
        _server.addConnector(_connector);
        _server.setHandler(new DumpHandler());
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testFragmentedChunk() throws Exception
    {
        _server.start();
        String response = null;
        try
        {
            int offset = 0;

            // Chunk last
            response = _connector.getResponse("GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n" +
                "0;\r\n" +
                "\r\n");
            offset = checkContains(response, offset, "HTTP/1.1 200");
            offset = checkContains(response, offset, "/R1");
            checkContains(response, offset, "12345");

            offset = 0;
            response = _connector.getResponse("GET /R2 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "5;\r\n" +
                "ABCDE\r\n" +
                "0;\r\n" +
                "\r\n");
            offset = checkContains(response, offset, "HTTP/1.1 200");
            offset = checkContains(response, offset, "/R2");
            checkContains(response, offset, "ABCDE");
        }
        catch (Exception e)
        {
            if (response != null)
                System.err.println(response);
            throw e;
        }
    }

    /**
     * HTTP/0.9 does not support HttpVersion (this is a bad request)
     */
    @Test
    public void testHttp09NoVersion() throws Exception
    {
        _server.start();
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setHttpCompliance(HttpCompliance.RFC2616);
        String request = "GET / HTTP/0.9\r\n\r\n";
        String response = _connector.getResponse(request);
        assertThat(response, containsString("505 HTTP Version Not Supported"));
        assertThat(response, containsString("<th>MESSAGE:</th><td>Unsupported Version</td>"));

        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setHttpCompliance(HttpCompliance.RFC7230);
        request = "GET / HTTP/0.9\r\n\r\n";
        response = _connector.getResponse(request);
        assertThat(response, containsString("505 HTTP Version Not Supported"));
        assertThat(response, containsString("<th>MESSAGE:</th><td>Unsupported Version</td>"));
    }

    /**
     * HTTP/0.9 does not support headers
     */
    @Test
    public void testHttp09NoHeaders() throws Exception
    {
        _server.start();
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setHttpCompliance(HttpCompliance.RFC2616);
        // header looking like another request is ignored
        String request = "GET /one\r\nGET :/two\r\n\r\n";
        String response = BufferUtil.toString(_connector.executeRequest(request).waitForOutput(10, TimeUnit.SECONDS));
        assertThat(response, containsString("pathInContext=/one"));
        assertThat(response, not(containsString("two")));
    }

    /**
     * Http/0.9 does not support pipelining.
     */
    @Test
    public void testHttp09MultipleRequests() throws Exception
    {
        _server.start();
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setHttpCompliance(HttpCompliance.RFC2616);

        // Verify that pipelining does not work with HTTP/0.9.
        String requests = "GET /?id=123\r\n\r\nGET /?id=456\r\n\r\n";
        LocalConnector.LocalEndPoint endp = _connector.executeRequest(requests);
        String response = BufferUtil.toString(endp.waitForOutput(10, TimeUnit.SECONDS));

        assertThat(response, containsString("id=123"));
        assertThat(response, not(containsString("id=456")));
    }

    /**
     * Ensure that excessively large hexadecimal chunk body length is parsed properly.
     */
    @Test
    public void testHttp11ChunkedBodyTruncation() throws Exception
    {
        _server.start();
        String request = "POST /?id=123 HTTP/1.1\r\n" +
            "Host: local\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "1ff00000008\r\n" +
            "abcdefgh\r\n" +
            "\r\n" +
            "0\r\n" +
            "\r\n" +
            "POST /?id=bogus HTTP/1.1\r\n" +
            "Content-Length: 5\r\n" +
            "Host: dummy-host.example.com\r\n" +
            "\r\n" +
            "12345";

        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 400 Bad Request"));
        assertThat(response, containsString("Connection: close"));
        assertThat(response, containsString("<th>MESSAGE:</th><td>Early EOF</td>"));
    }

    public static Stream<int[]> contentLengths()
    {
        return Stream.of(
            new int[] {0, 8},
            new int[] {8, 0},
            new int[] {8, 8},
            new int[] {0, 8, 0},
            new int[] {1, 2, 3, 4, 5, 6, 7, 8},
            new int[] {8, 2, 1},
            new int[] {0, 0},
            new int[] {8, 0, 8},
            new int[] {-1, 8},
            new int[] {8, -1},
            new int[] {-1, 8, -1},
            new int[] {-1, -1},
            new int[] {8, -1, 8}
        );
    }

    /**
     * More then 1 Content-Length is a bad requests per HTTP rfcs.
     */
    @ParameterizedTest
    @MethodSource("contentLengths")
    public void testHttp11MultipleContentLength(int[] clen) throws Exception
    {
        _server.start();
        LOG.info("badMessage: 400 Bad messages EXPECTED...");
        StringBuilder request = new StringBuilder();
        request.append("POST / HTTP/1.1\r\n");
        request.append("Host: local\r\n");
        for (int i : clen)
            request.append("Content-Length: ").append(i).append("\r\n");
        request.append("Content-Type: text/plain\r\n");
        request.append("Connection: close\r\n");
        request.append("\r\n");
        request.append("abcdefgh"); // actual content of 8 bytes

        String rawResponse = _connector.getResponse(request.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response.status", response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    static final int CHUNKED = -1;
    static final int DQUOTED_CHUNKED = -2;
    static final int BAD_CHUNKED = -3;
    static final int UNKNOWN_TE = -4;

    public static Stream<Arguments> http11ContentLengthAndChunkedData()
    {
        return Stream.of(
            Arguments.of(new int[]{CHUNKED, 8}),
            Arguments.of(new int[]{8, CHUNKED}),
            Arguments.of(new int[]{8, CHUNKED, 8}),
            Arguments.of(new int[]{DQUOTED_CHUNKED, 8}),
            Arguments.of(new int[]{8, DQUOTED_CHUNKED}),
            Arguments.of(new int[]{8, DQUOTED_CHUNKED, 8}),
            Arguments.of(new int[]{BAD_CHUNKED, 8}),
            Arguments.of(new int[]{8, BAD_CHUNKED}),
            Arguments.of(new int[]{8, BAD_CHUNKED, 8}),
            Arguments.of(new int[]{UNKNOWN_TE, 8}),
            Arguments.of(new int[]{8, UNKNOWN_TE}),
            Arguments.of(new int[]{8, UNKNOWN_TE, 8}),
            Arguments.of(new int[]{8, UNKNOWN_TE, CHUNKED, DQUOTED_CHUNKED, BAD_CHUNKED, 8})
        );
    }

    /**
     * More then 1 Content-Length is a bad requests per HTTP rfcs.
     */
    @ParameterizedTest
    @MethodSource("http11ContentLengthAndChunkedData")
    public void testHttp11ContentLengthAndChunk(int[] contentLengths) throws Exception
    {
        _server.start();
        LOG.info("badMessage: 400 Bad messages EXPECTED...");

        StringBuilder request = new StringBuilder();
        request.append("POST / HTTP/1.1\r\n");
        request.append("Host: local\r\n");
        for (int contentLength : contentLengths)
        {
            switch (contentLength)
            {
                case CHUNKED -> request.append("Transfer-Encoding: chunked\r\n");
                case DQUOTED_CHUNKED -> request.append("Transfer-Encoding: \"chunked\"\r\n");
                case BAD_CHUNKED -> request.append("Transfer-Encoding: 'chunked'\r\n");
                case UNKNOWN_TE -> request.append("Transfer-Encoding: bogus\r\n");
                default -> request.append("Content-Length: ").append(contentLength).append("\r\n");
            }
        }
        request.append("Content-Type: text/plain\r\n");
        request.append("\r\n");
        request.append("8;\r\n"); // chunk header
        request.append("abcdefgh"); // actual content of 8 bytes
        request.append("\r\n0;\r\n\r\n"); // last chunk

        String rawResponse = _connector.getResponse(request.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response.status", response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    /**
     * Examples of valid Chunked behaviors.
     */
    public static Stream<Arguments> http11TransferEncodingChunked()
    {
        return Stream.of(
            Arguments.of(List.of("chunked, ")), // results in 1 entry
            Arguments.of(List.of(", chunked")),

            // invalid tokens with chunked as last
            // no conflicts, chunked token is specified and is last, will result in chunked
            Arguments.of(List.of("bogus, chunked")),
            Arguments.of(List.of("'chunked', chunked")), // apostrophe characters with and without
            Arguments.of(List.of("identity, chunked")), // identity was removed in RFC2616 errata and has been dropped in RFC7230

            // multiple headers
            Arguments.of(Arrays.asList("identity", "chunked")), // 2 separate headers
            Arguments.of(Arrays.asList("", "chunked")) // 2 separate headers
        );
    }

    /**
     * Test Chunked Transfer-Encoding behavior indicated by
     * https://tools.ietf.org/html/rfc7230#section-3.3.1
     */
    @ParameterizedTest
    @MethodSource("http11TransferEncodingChunked")
    public void testHttp11TransferEncodingChunked(List<String> tokens) throws Exception
    {
        _server.start();
        StringBuilder request = new StringBuilder();
        request.append("POST / HTTP/1.1\r\n");
        request.append("Host: local\r\n");
        tokens.forEach((token) -> request.append("Transfer-Encoding: ").append(token).append("\r\n"));
        request.append("Content-Type: text/plain\r\n");
        request.append("\r\n");
        request.append("8;\r\n"); // chunk header
        request.append("abcdefgh"); // actual content of 8 bytes
        request.append("\r\n0;\r\n\r\n"); // last chunk

        String rawResponse = _connector.getResponse(request.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response.status (" + response.getReason() + ")", response.getStatus(), is(HttpStatus.OK_200));
    }

    public static Stream<Arguments> http11TransferEncodingInvalidChunked()
    {
        return Stream.of(
            // == Results in 400 Bad Request
            Arguments.of(Arrays.asList("bogus", "identity")), // 2 separate headers

            Arguments.of(List.of("bad")),
            Arguments.of(List.of("identity")),  // identity was removed in RFC2616 errata and has been dropped in RFC7230
            Arguments.of(List.of("'chunked'")), // apostrophe characters
            Arguments.of(List.of("`chunked`")), // backtick "quote" characters
            Arguments.of(List.of("[chunked]")), // bracketed (seen as mistake in several REST libraries)
            Arguments.of(List.of("{chunked}")), // json'd (seen as mistake in several REST libraries)
            Arguments.of(List.of("\u201Cchunked\u201D")), // opening and closing (fancy) double quotes characters

            // invalid tokens with chunked not as last
            Arguments.of(List.of("chunked, bogus")),
            Arguments.of(List.of("chunked, 'chunked'")),
            Arguments.of(List.of("chunked, identity")),
            Arguments.of(List.of("chunked, identity, chunked")), // duplicate chunked
            Arguments.of(List.of("chunked", "identity")), // 2 separate header lines

            // multiple chunked tokens present
            Arguments.of(List.of("chunked", "identity", "chunked")), // 3 separate header lines
            Arguments.of(List.of("chunked", "chunked")), // 2 separate header lines
            Arguments.of(List.of("chunked, chunked")) // on same line
        );
    }

    /**
     * Test bad Transfer-Encoding behavior as indicated by
     * https://tools.ietf.org/html/rfc7230#section-3.3.1
     */
    @ParameterizedTest
    @MethodSource("http11TransferEncodingInvalidChunked")
    public void testHttp11TransferEncodingInvalidChunked(List<String> tokens) throws Exception
    {
        _server.start();
        LOG.info("badMessage: 400 Bad messages EXPECTED...");
        StringBuilder request = new StringBuilder();
        request.append("POST / HTTP/1.1\r\n");
        request.append("Host: local\r\n");
        tokens.forEach((token) -> request.append("Transfer-Encoding: ").append(token).append("\r\n"));
        request.append("Content-Type: text/plain\r\n");
        request.append("\r\n");
        request.append("8;\r\n"); // chunk header
        request.append("abcdefgh"); // actual content of 8 bytes
        request.append("\r\n0;\r\n\r\n"); // last chunk

        String rawResponse = _connector.getResponse(request.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response.status", response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    public void testNoPath() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET http://localhost:80 HTTP/1.1\r\n" +
            "Host: localhost:80\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkContains(response, offset, "pathInContext=/");
    }

    @Test
    public void testDate() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET / HTTP/1.1\r\n" +
            "Host: localhost:80\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "Date: ");
        checkContains(response, offset, "pathInContext=/");
    }

    @Test
    public void testSetDate() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET /?date=1+Jan+1970 HTTP/1.1\r\n" +
            "Host: localhost:80\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "Date: 1 Jan 1970");
        checkContains(response, offset, "pathInContext=/");
    }

    @Test
    public void testBadNoPath() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET http://localhost:80/../cheat HTTP/1.1\r\n" +
            "Host: localhost:80\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 400");
    }

    @Test
    public void testOKPathDotDotPath() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET /ooops/../path HTTP/1.0\r\nHost: localhost:80\r\n\n");
        checkContains(response, 0, "HTTP/1.1 200 OK");
        checkContains(response, 0, "pathInContext=/path");
    }

    @Test
    public void testBadPathDotDotPath() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET /ooops/../../path HTTP/1.0\r\nHost: localhost:80\r\n\n");
        checkContains(response, 0, "HTTP/1.1 400 ");
        checkContains(response, 0, "<th>MESSAGE:</th><td>Bad Request</td>");
    }

    @Test
    public void testBadDotDotPath() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET ../path HTTP/1.0\r\nHost: localhost:80\r\n\n");
        checkContains(response, 0, "HTTP/1.1 400 ");
        checkContains(response, 0, "<th>MESSAGE:</th><td>Bad Request</td>");
    }

    @Test
    public void testBadSlashDotDotPath() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET /../path HTTP/1.0\r\nHost: localhost:80\r\n\n");
        checkContains(response, 0, "HTTP/1.1 400 ");
        checkContains(response, 0, "<th>MESSAGE:</th><td>Bad Request</td>");
    }

    @Test
    public void test09() throws Exception
    {
        _server.start();
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setHttpCompliance(HttpCompliance.RFC2616_LEGACY);
        LocalConnector.LocalEndPoint endp = _connector.executeRequest("GET /R1\n");
        endp.waitUntilClosed();
        String response = BufferUtil.toString(endp.takeOutput());

        int offset = 0;
        checkNotContained(response, offset, "HTTP/1.1");
        checkNotContained(response, offset, "200");
        checkContains(response, offset, "httpURI=http://0.0.0.0/R1");
        checkContains(response, offset, "pathInContext=/R1");
    }

    @Test
    public void testSimple() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET /R1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkContains(response, offset, "httpURI=http://localhost/R1");
        checkContains(response, offset, "pathInContext=/R1");
    }

    @Test
    public void testEmptyNotPersistent() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("""
            GET /R1?empty=true HTTP/1.0\r
            Host: localhost\r
            \r
            """);

        int offset = 0;
        checkContains(response, offset, "HTTP/1.1 200");

        response = _connector.getResponse("""
            GET /R1?empty=true HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkContains(response, offset, "Connection: close");
    }

    @Test
    public void testEmptyPersistent() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("""
            GET /R1?empty=true HTTP/1.0\r
            Host: localhost\r
            Connection: keep-alive\r
            \r
            """);

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkContains(response, offset, "Content-Length: 0");
        checkNotContained(response, offset, "Connection: close");

        response = _connector.getResponse("""
            GET /R1?empty=true HTTP/1.1\r
            Host: localhost\r
            \r
            """);

        offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkContains(response, offset, "Content-Length: 0");
        checkNotContained(response, offset, "Connection: close");
    }

    @Test
    public void testEmptyChunk() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET /R1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "0\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkContains(response, offset, "/R1");
    }

    @Test
    public void testChunk() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET /R1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "A\r\n" +
            "0123456789\r\n" +
            "0\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "/R1");
        checkContains(response, offset, "0123456789");
    }

    @Test
    public void testChunkTrailer() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET /R1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "A\r\n" +
            "0123456789\r\n" +
            "0\r\n" +
            "Trailer: ignored\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "/R1");
        checkContains(response, offset, "0123456789");
    }

    @Test
    public void testChunkNoTrailer() throws Exception
    {
        _server.start();
        // Expect TimeoutException logged
        _connector.setIdleTimeout(1000);
        String response = _connector.getResponse("GET /R1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "A\r\n" +
            "0123456789\r\n" +
            "0\r\n\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "/R1");
        checkContains(response, offset, "0123456789");
    }

    @Test
    public void testHead() throws Exception
    {
        _server.start();
        String responsePOST = _connector.getResponse("POST /R1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        String responseHEAD = _connector.getResponse("HEAD /R1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        String postLine;
        boolean postDate = false;
        Set<String> postHeaders = new HashSet<>();
        try (BufferedReader in = new BufferedReader(new StringReader(responsePOST)))
        {
            postLine = in.readLine();
            String line = in.readLine();
            while (line != null && line.length() > 0)
            {
                if (line.startsWith("Date:"))
                    postDate = true;
                else
                    postHeaders.add(line);
                line = in.readLine();
            }
        }
        String headLine;
        boolean headDate = false;
        Set<String> headHeaders = new HashSet<>();
        try (BufferedReader in = new BufferedReader(new StringReader(responseHEAD)))
        {
            headLine = in.readLine();
            String line = in.readLine();
            while (line != null && line.length() > 0)
            {
                if (line.startsWith("Date:"))
                    headDate = true;
                else
                    headHeaders.add(line);
                line = in.readLine();
            }
        }

        assertThat(postLine, equalTo(headLine));
        assertThat(postDate, equalTo(headDate));
        assertEquals(postHeaders, headHeaders);
    }

    @Test
    public void testHeadChunked() throws Exception
    {
        _server.start();
        String responsePOST = _connector.getResponse("POST /R1?no-content-length=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n", false, 1, TimeUnit.SECONDS);

        String responseHEAD = _connector.getResponse("HEAD /R1?no-content-length=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n", true, 1, TimeUnit.SECONDS);

        String postLine;
        boolean postDate = false;
        Set<String> postHeaders = new HashSet<>();
        try (BufferedReader in = new BufferedReader(new StringReader(responsePOST)))
        {
            postLine = in.readLine();
            String line = in.readLine();
            while (line != null && line.length() > 0)
            {
                if (line.startsWith("Date:"))
                    postDate = true;
                else
                    postHeaders.add(line);
                line = in.readLine();
            }
        }
        String headLine;
        boolean headDate = false;
        Set<String> headHeaders = new HashSet<>();
        try (BufferedReader in = new BufferedReader(new StringReader(responseHEAD)))
        {
            headLine = in.readLine();
            String line = in.readLine();
            while (line != null && line.length() > 0)
            {
                if (line.startsWith("Date:"))
                    headDate = true;
                else
                    headHeaders.add(line);
                line = in.readLine();
            }
        }

        assertThat(postLine, equalTo(headLine));
        assertThat(postDate, equalTo(headDate));
        assertEquals(postHeaders, headHeaders);
    }

    @Test
    public void testHostOnlyPort() throws Exception
    {
        _server.start();
        String response;

        response = _connector.getResponse("""
            GET /foo HTTP/1.1
            Host: :1234
            Connection: close
            
            """);
        checkContains(response, 0, "HTTP/1.1 400");
    }

    @Test
    public void testBadHostPort() throws Exception
    {
        _server.start();
        String response;

        response = _connector.getResponse("GET http://localhost:EXPECTED_NUMBER_FORMAT_EXCEPTION/ HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 400");
    }

    @Test
    public void testNoHost() throws Exception
    {
        _server.start();
        String response;

        response = _connector.getResponse("""
            GET / HTTP/1.1
            
            """);
        checkContains(response, 0, "HTTP/1.1 400");
    }

    @Test
    public void testEmptyHost() throws Exception
    {
        _server.start();
        String response;

        response = _connector.getResponse("GET / HTTP/1.1\r\n" +
            "Host:\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 400");
    }

    @Test
    public void testEmptyHostAbsolute() throws Exception
    {
        _server.start();
        String response;

        response = _connector.getResponse("GET scheme:/// HTTP/1.1\r\n" +
            "Host:\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 200");
    }

    @Test
    public void testBadURIencoding() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET /bad/encoding%x HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 400");
    }

    @Test
    @Disabled("review this test. seems a security issue to fallback from utf-8 to iso-1, was there a reason to do that?")
    public void testBadUTF8FallsbackTo8859() throws Exception
    {
        _server.start();
        LOG.info("badMessage: bad encoding expected ...");
        String response;

        response = _connector.getResponse("GET /foo/bar%c0%00 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 400");

        response = _connector.getResponse("GET /bad/utf8%c1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 200"); //now fallback to iso-8859-1
    }

    @Test
    public void testAutoFlush() throws Exception
    {
        _server.start();
        int offset = 0;

        String response = _connector.getResponse("GET /R1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "5;\r\n" +
            "12345\r\n" +
            "0;\r\n" +
            "\r\n");
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkNotContained(response, offset, "IgnoreMe");
        offset = checkContains(response, offset, "/R1");
        checkContains(response, offset, "12345");
    }

    @Test
    public void testEmptyFlush() throws Exception
    {
        _server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.write(false, null, callback);
                return true;
            }
        });
        _server.start();

        String response = _connector.getResponse("GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        assertThat(response, Matchers.containsString("200 OK"));
    }

    @Test
    public void testUnconsumed() throws Exception
    {
        _server.start();
        int offset = 0;
        String requests =
            "GET /R1?read=4 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n" +
                "5;\r\n" +
                "67890\r\n" +
                "0;\r\n" +
                "\r\n" +
                "GET /R2 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: 10\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "abcdefghij";

        LocalConnector.LocalEndPoint endp = _connector.executeRequest(requests);
        String response = endp.getResponse() + endp.getResponse();

        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "pathInContext=/R1");
        offset = checkContains(response, offset, "1234");
        checkNotContained(response, offset, "56789");
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "pathInContext=/R2");
        offset = checkContains(response, offset, "charset=UTF-8");
        checkContains(response, offset, "abcdefghij");
    }

    @Test
    public void testUnconsumedTimeout() throws Exception
    {
        _server.start();
        _connector.setIdleTimeout(500);
        int offset = 0;
        String requests =
            "GET /R1?read=4 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n";

        long start = NanoTime.now();
        String response = _connector.getResponse(requests, 2000, TimeUnit.MILLISECONDS);
        assertThat(NanoTime.millisSince(start), lessThanOrEqualTo(2000L));

        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "pathInContext=/R1");
        offset = checkContains(response, offset, "1234");
        checkNotContained(response, offset, "56789");
    }

    @Test
    public void testUnconsumedErrorRead() throws Exception
    {
        _server.start();
        int offset = 0;
        String requests =
            "GET /R1?read=1&error=499 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n" +
                "5;\r\n" +
                "67890\r\n" +
                "0;\r\n" +
                "\r\n" +
                "GET /R2 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: 10\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "abcdefghij\r\n";

        LocalConnector.LocalEndPoint endp = _connector.executeRequest(requests);
        String response = endp.getResponse() + endp.getResponse();

        offset = checkContains(response, offset, "HTTP/1.1 499");
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "/R2");
        checkContains(response, offset, "abcdefghij");
    }

    @Test
    public void testUnconsumedErrorStream() throws Exception
    {
        _server.start();
        int offset = 0;
        String requests =
            "GET /R1?error=599 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: application/data; charset=utf-8\r\n" +
                "Some: header\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n" +
                "5;\r\n" +
                "67890\r\n" +
                "0;\r\n" +
                "\r\n" +
                "GET /R2 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: 10\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "abcdefghij\r\n";

        LocalConnector.LocalEndPoint endp = _connector.executeRequest(requests);
        String response = endp.getResponse() + endp.getResponse();

        assertThat(response, not(containsString("Some: header")));
        offset = checkContains(response, offset, "HTTP/1.1 599");
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "/R2");
        offset = checkContains(response, offset, "text/plain; charset=UTF-8");
        checkContains(response, offset, "abcdefghij");
    }

    @Test
    public void testUnconsumedException() throws Exception
    {
        _server.start();
        int offset = 0;
        String requests = "GET /R1?read=1&ISE=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "\r\n" +
            "5;\r\n" +
            "12345\r\n" +
            "5;\r\n" +
            "67890\r\n" +
            "0;\r\n" +
            "\r\n" +
            "GET /R2 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n" +
            "abcdefghij\r\n";

        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            LOG.info("EXPECTING: java.lang.IllegalStateException...");
            String response = _connector.getResponse(requests);
            offset = checkContains(response, offset, "HTTP/1.1 500");
            checkNotContained(response, offset, "HTTP/1.1 200");
        }
    }

    @Test
    public void testConnection() throws Exception
    {
        _server.start();
        String response = null;
        try
        {
            int offset = 0;
            response = _connector.getResponse("GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: TE, close\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n" +
                "0;\r\n" +
                "\r\n");
            checkContains(response, offset, "Connection: close");
        }
        catch (Exception e)
        {
            if (response != null)
                System.err.println(response);
            throw e;
        }
    }

    /**
     * Creates a request header over 1k in size, by creating a single header entry with an huge value.
     *
     * @throws Exception if test failure
     */
    @Test
    public void testOversizedBuffer() throws Exception
    {
        _server.start();
        String response = null;
        try
        {
            int offset = 0;
            String cookie = "thisisastringthatshouldreachover1kbytes";
            for (int i = 0; i < 100; i++)
            {
                cookie += "xxxxxxxxxxxx";
            }
            response = _connector.getResponse("GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Cookie: " + cookie + "\r\n" +
                "\r\n"
            );
            checkContains(response, offset, "HTTP/1.1 431");
        }
        catch (Exception e)
        {
            if (response != null)
                System.err.println(response);
            throw e;
        }
    }

    /**
     * Creates a request header with over 1000 entries.
     *
     * @throws Exception if test failure
     */
    @Test
    public void testExcessiveHeader() throws Exception
    {
        _server.start();
        int offset = 0;

        StringBuilder request = new StringBuilder();
        request.append("GET / HTTP/1.1\r\n");
        request.append("Host: localhost\r\n");
        request.append("Cookie: thisisastring\r\n");
        for (int i = 0; i < 1000; i++)
        {
            request.append(String.format("X-Header-%04d: %08x\r\n", i, i));
        }
        request.append("\r\n");

        String response = _connector.getResponse(request.toString());
        offset = checkContains(response, offset, "HTTP/1.1 431");
        checkContains(response, offset, "<h2>HTTP ERROR 431 Request Header Fields Too Large</h2>");
    }

    @Test
    public void testOversizedResponse() throws Exception
    {
        String str = "thisisastringthatshouldreachover1kbytes-";
        for (int i = 0; i < 500; i++)
        {
            str += "xxxxxxxxxxxx";
        }
        final String longstr = str;
        final CountDownLatch checkError = new CountDownLatch(1);
        _server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE.toString(), MimeTypes.Type.TEXT_HTML.toString());
                response.getHeaders().put("LongStr", longstr);
                response.write(false,
                    BufferUtil.toBuffer("<html><h1>FOO</h1></html>"), Callback.from(callback::succeeded, t ->
                    {
                        checkError.countDown();
                        callback.failed(t);
                    })
                );
                return true;
            }
        });
        _server.start();

        String response = _connector.getResponse("GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n"
        );

        checkContains(response, 0, "HTTP/1.1 500");
        assertTrue(checkError.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testAllowedLargeResponse() throws Exception
    {
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setResponseHeaderSize(16 * 1024);
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setOutputBufferSize(8 * 1024);

        byte[] bytes = new byte[12 * 1024];
        Arrays.fill(bytes, (byte)'X');
        final String longstr = "thisisastringthatshouldreachover12kbytes-" + new String(bytes, StandardCharsets.ISO_8859_1) + "_Z_";
        final CountDownLatch checkError = new CountDownLatch(1);
        _server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE.toString(), MimeTypes.Type.TEXT_HTML.toString());
                response.getHeaders().put("LongStr", longstr);

                response.write(false,
                    BufferUtil.toBuffer("<html><h1>FOO</h1></html>"), Callback.from(callback::succeeded, t ->
                    {
                        checkError.countDown();
                        callback.failed(t);
                    })
                );
                return true;
            }
        });
        _server.start();

        String response = _connector.getResponse("GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n"
        );

        checkContains(response, 0, "HTTP/1.1 200");
        checkContains(response, 0, "LongStr: thisisastringthatshouldreachover12kbytes");
        checkContains(response, 0, "XXX_Z_");
        assertThat(checkError.getCount(), is(1L));
    }

    @Test
    public void testAsterisk() throws Exception
    {
        _server.start();
        String response = null;
        try (StacklessLogging stackless = new StacklessLogging(HttpParser.class))
        {
            int offset = 0;

            response = _connector.getResponse("OPTIONS * HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n" +
                "0;\r\n" +
                "\r\n");
            checkContains(response, offset, "HTTP/1.1 200");

            offset = 0;
            response = _connector.getResponse("GET * HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n" +
                "0;\r\n" +
                "\r\n");
            checkContains(response, offset, "HTTP/1.1 400");

            offset = 0;
            response = _connector.getResponse("GET ** HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n" +
                "0;\r\n" +
                "\r\n");
            checkContains(response, offset, "HTTP/1.1 400 Bad Request");
        }
        catch (Exception e)
        {
            if (response != null)
                System.err.println(response);
            throw e;
        }
    }

    @Test
    public void testCONNECT() throws Exception
    {
        _server.start();
        String response = null;
        try
        {
            int offset = 0;

            response = _connector.getResponse("CONNECT www.webtide.com:8080 HTTP/1.1\r\n" +
                "Host: myproxy:8888\r\n" +
                "\r\n", 200, TimeUnit.MILLISECONDS);
            checkContains(response, offset, "HTTP/1.1 200");
        }
        catch (Exception e)
        {
            if (response != null)
                System.err.println(response);
            throw e;
        }
    }

    @Test
    public void testBytesIn() throws Exception
    {
        String chunk1 = "0123456789ABCDEF";
        String chunk2 = IntStream.range(0, 64).mapToObj(i -> chunk1).collect(Collectors.joining());
        long dataLength = chunk1.length() + chunk2.length();
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        try
                        {
                            CountDownLatch blocker = new CountDownLatch(1);
                            request.demand(blocker::countDown);
                            blocker.await();
                        }
                        catch (InterruptedException e)
                        {
                            // ignored
                        }
                        continue;
                    }

                    if (chunk.hasRemaining())
                        chunk.getByteBuffer().clear();
                    chunk.release();
                    if (chunk.isLast())
                        break;
                }

                HttpConnection connection = HttpConnection.getCurrentConnection();
                long bytesIn = connection.getBytesIn();
                assertThat(bytesIn, greaterThan(dataLength));

                callback.succeeded();
                return true;
            }
        });
        _server.start();

        LocalConnector.LocalEndPoint localEndPoint = _connector.executeRequest("" +
            "POST / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Length: " + dataLength + "\r\n" +
            "\r\n" +
            chunk1);

        // Wait for the server to block on the read().
        Thread.sleep(500);

        // Send more content.
        localEndPoint.addInput(chunk2);

        HttpTester.Response response = HttpTester.parseResponse(localEndPoint.getResponse());
        assertEquals(response.getStatus(), HttpStatus.OK_200);
        localEndPoint.close();

        localEndPoint = _connector.executeRequest("" +
            "POST / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            Integer.toHexString(chunk1.length()) + "\r\n" +
            chunk1 + "\r\n");

        // Wait for the server to block on the read().
        Thread.sleep(500);

        // Send more content.
        localEndPoint.addInput("" +
            Integer.toHexString(chunk2.length()) + "\r\n" +
            chunk2 + "\r\n" +
            "0\r\n" +
            "\r\n");

        response = HttpTester.parseResponse(localEndPoint.getResponse());
        assertEquals(response.getStatus(), HttpStatus.OK_200);
        localEndPoint.close();
    }

    @Test
    public void testBadURI() throws Exception
    {
        _server.start();
        String request = """
            GET /ambiguous/doubleSlash// HTTP/1.0
            Host: whatever
            
            """;
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testAmbiguousParameters() throws Exception
    {
        _server.start();
        String request = """
            GET /ambiguous/..;/path HTTP/1.0\r
            Host: whatever\r
            \r
            """;
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.from(EnumSet.of(UriCompliance.Violation.AMBIGUOUS_PATH_PARAMETER)));
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testAmbiguousSegments() throws Exception
    {
        _server.start();
        String request = """
            GET /ambiguous/%2e%2e/path HTTP/1.0\r
            Host: whatever\r
            \r
            """;
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testAmbiguousSeparators() throws Exception
    {
        _server.start();
        String request = """
            GET /ambiguous/%2f/path HTTP/1.0\r
            Host: whatever\r
            \r
            """;
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.from("DEFAULT,AMBIGUOUS_PATH_SEPARATOR"));
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testAmbiguousPaths() throws Exception
    {
        _server.start();
        String request = """
            GET /unnormal/.././path/ambiguous%2f%2e%2e/%2e;/info HTTP/1.0\r
            Host: whatever\r
            \r
            """;

        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));

        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.from(EnumSet.of(
            UriCompliance.Violation.AMBIGUOUS_PATH_SEPARATOR,
            UriCompliance.Violation.AMBIGUOUS_PATH_SEGMENT,
            UriCompliance.Violation.AMBIGUOUS_PATH_PARAMETER)));
        assertThat(_connector.getResponse(request), Matchers.allOf(
            startsWith("HTTP/1.1 200"),
            containsString("pathInContext=/path/ambiguous%2F../info")));
    }

    @Test
    public void testAmbiguousEncoding() throws Exception
    {
        _server.start();
        String request = """
            GET /ambiguous/encoded/%25/path HTTP/1.0\r
            Host: whatever\r
            \r
            """;
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.from(EnumSet.of(UriCompliance.Violation.AMBIGUOUS_PATH_ENCODING)));
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));

        UriCompliance custom = new UriCompliance("Custom", EnumSet.complementOf(
            EnumSet.of(UriCompliance.Violation.AMBIGUOUS_PATH_ENCODING)));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(custom);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testAmbiguousDoubleSlash() throws Exception
    {
        _server.start();
        String request = """
            GET /ambiguous/doubleSlash// HTTP/1.0
            Host: whatever
            
            """;
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testRelativePath() throws Exception
    {
        _server.start();
        String request = """
            GET foo/bar HTTP/1.0\r
            Host: whatever\r
            \r
            """;

        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
    }

    private int checkContains(String s, int offset, String c)
    {
        assertThat(s.substring(offset), Matchers.containsString(c));
        return s.indexOf(c, offset);
    }

    private void checkNotContained(String s, int offset, String c)
    {
        assertThat(s.substring(offset), Matchers.not(Matchers.containsString(c)));
    }

    public static Stream<Arguments> connectionBehavior()
    {
        List<Arguments> cases = new ArrayList<>();

        // --- VALID HTTP/1.0 SPEC REQUESTS ---

        // Request does not send connection header.
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, null, null, null));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, null, "close", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, null, "keep-alive", null));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, null, "Close", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, null, "Keep-Alive", null));

        // Request sends "keep-alive"
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, "keep-alive", null, "keep-alive"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, "keep-alive", "close", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, "keep-alive", "keep-alive", "keep-alive"));

        // --- INVALID HTTP/1.0 SPEC REQUESTS ---

        // Request sends invalid value "close" (on HTTP/1.0)
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, "close", null, null));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, "close", "close", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, "close", "keep-alive", null));

        // --- INVALID HTTP RESPONSE HEADERS ---
        // this is when the servlet is setting headers that are in conflict with
        // the spec and each other.

        // Request does not set "connection" header.
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, null, "close, keep-alive", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, null, "keep-alive, close", "close"));

        // Request sends invalid value "close" (on HTTP/1.0)
        // keep-alive is forbidden when not persistent
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, "close", "close, keep-alive", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, "close", "keep-alive, close", "close"));

        // Request sends "keep-alive"
        // connection lists are preserved per hop-by-hop rules
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, "keep-alive", "close, keep-alive", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, "keep-alive", "keep-alive, close", "close"));

        // Other connection values
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, null, "Other, Fields", "Other, Fields"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, null, "Other, close, Fields", "Other, close, Fields"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, null, "Other, keep-alive, Fields", "Other, Fields"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, null, "Other, close, keep-alive, Fields", "Other, close, Fields"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_0, "keep-alive", "Other, keep-alive, Fields", "Other, keep-alive, Fields"));


        // --- VALID HTTP/1.1 SPEC REQUESTS ---

        // Request does not send connection header.
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, null, null, null));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, null, "close", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, null, "keep-alive", null));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, null, "Close", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, null, "Keep-Alive", null));

        // Request sends value "close"
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, "close", null, "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, "close", "close", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, "close", "keep-alive", "close"));

        // --- INVALID HTTP/1.0 SPEC REQUESTS ---

        // Request sends invalid "keep-alive" header value (on HTTP/1.1)
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, "keep-alive", null, null));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, "keep-alive", "close", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, "keep-alive", "keep-alive", null));

        // --- INVALID HTTP RESPONSE HEADERS ---
        // this is when the servlet is setting headers that are in conflict with
        // the spec and each other.

        // Request does not set "connection" header.
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, null, "close, keep-alive", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, null, "keep-alive, close", "close"));

        // Request sends value "close"
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, "close", "close, keep-alive", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, "close", "keep-alive, close", "close"));

        // Request sends invalid "keep-alive" header value (on HTTP/1.1)
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, "keep-alive", "close, keep-alive", "close"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, "keep-alive", "keep-alive, close", "close"));

        // Other connection values
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, null, "Other, Fields", "Other, Fields"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, null, "Other, close, Fields", "Other, close, Fields"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, null, "Other, keep-alive, Fields", "Other, Fields"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, null, "Other, close, keep-alive, Fields", "Other, close, Fields"));
        cases.add(Arguments.of(HttpVersion.HTTP_1_1, "close", "Other, Fields", "Other, Fields|close"));

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("connectionBehavior")
    public void testConnectionBehavior(HttpVersion version, String requestConnectionHeader, String responseConnectionHeader, String expectedConnectionHeader) throws Exception
    {
        _server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                if (responseConnectionHeader != null)
                    response.getHeaders().put(HttpHeader.CONNECTION, responseConnectionHeader);

                callback.succeeded();
                return true;
            }
        });

        _server.start();

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /nothing %s\r\n".formatted(version.asString()));
        rawRequest.append("Host: test\r\n");
        if (requestConnectionHeader != null)
            rawRequest.append("Connection: ").append(requestConnectionHeader).append("\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getVersion(), is(HttpVersion.HTTP_1_1));
        if (expectedConnectionHeader == null)
            assertFalse(response.getMetaData().getHttpFields().contains(HttpHeader.CONNECTION));
        else
        {
            List<String> actual = response.getValuesList(HttpHeader.CONNECTION);
            for (String expected : expectedConnectionHeader.split("\\|"))
                assertThat(actual.remove(0), is(expected));
        }
    }
}
