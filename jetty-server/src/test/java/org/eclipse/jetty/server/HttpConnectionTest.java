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

/*
 * Created on 9/01/2004
 *
 * To change the template for this generated file go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.NanoTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpConnectionTest
{
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(HttpConnectionTest.class);
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void init() throws Exception
    {
        server = new Server();

        HttpConfiguration config = new HttpConfiguration();
        config.setRequestHeaderSize(1024);
        config.setResponseHeaderSize(1024);
        config.setSendDateHeader(true);
        HttpConnectionFactory http = new HttpConnectionFactory(config);

        connector = new LocalConnector(server, http, null);
        connector.setIdleTimeout(5000);
        server.addConnector(connector);
        server.setHandler(new DumpHandler());
        ErrorHandler eh = new ErrorHandler();
        eh.setServer(server);
        server.addBean(eh);
        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testFragmentedChunk() throws Exception
    {
        String response = null;
        try
        {
            int offset = 0;

            // Chunk last
            response = connector.getResponse("GET /R1 HTTP/1.1\r\n" +
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
            response = connector.getResponse("GET /R2 HTTP/1.1\r\n" +
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
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setHttpCompliance(HttpCompliance.RFC2616);
        String request = "GET / HTTP/0.9\r\n\r\n";
        String response = connector.getResponse(request);
        assertThat(response, containsString("505 HTTP Version Not Supported"));
        assertThat(response, containsString("reason: Unsupported Version"));

        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setHttpCompliance(HttpCompliance.RFC7230);
        request = "GET / HTTP/0.9\r\n\r\n";
        response = connector.getResponse(request);
        assertThat(response, containsString("505 HTTP Version Not Supported"));
        assertThat(response, containsString("reason: Unsupported Version"));
    }

    /**
     * HTTP/0.9 does not support headers
     */
    @Test
    public void testHttp09NoHeaders() throws Exception
    {
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setHttpCompliance(HttpCompliance.RFC2616);
        // header looking like another request is ignored
        String request = "GET /one\r\nGET :/two\r\n\r\n";
        String response = BufferUtil.toString(connector.executeRequest(request).waitForOutput(10, TimeUnit.SECONDS));
        assertThat(response, containsString("pathInfo=/"));
        assertThat(response, not(containsString("two")));
    }

    /**
     * Http/0.9 does not support pipelining.
     */
    @Test
    public void testHttp09MultipleRequests() throws Exception
    {
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setHttpCompliance(HttpCompliance.RFC2616);

        // Verify that pipelining does not work with HTTP/0.9.
        String requests = "GET /?id=123\r\n\r\nGET /?id=456\r\n\r\n";
        LocalEndPoint endp = connector.executeRequest(requests);
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

        String response = connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Connection: close"));
        assertThat(response, containsString("Early EOF"));
    }

    /**
     * More then 1 Content-Length is a bad requests per HTTP rfcs.
     */
    @Test
    public void testHttp11MultipleContentLength() throws Exception
    {
        HttpParser.LOG.info("badMessage: 400 Bad messages EXPECTED...");
        int[][] contentLengths = {
            {0, 8},
            {8, 0},
            {8, 8},
            {0, 8, 0},
            {1, 2, 3, 4, 5, 6, 7, 8},
            {8, 2, 1},
            {0, 0},
            {8, 0, 8},
            {-1, 8},
            {8, -1},
            {-1, 8, -1},
            {-1, -1},
            {8, -1, 8},
            };

        for (int x = 0; x < contentLengths.length; x++)
        {
            StringBuilder request = new StringBuilder();
            request.append("POST /?id=").append(Integer.toString(x)).append(" HTTP/1.1\r\n");
            request.append("Host: local\r\n");
            int[] clen = contentLengths[x];
            for (int n = 0; n < clen.length; n++)
            {
                request.append("Content-Length: ").append(Integer.toString(clen[n])).append("\r\n");
            }
            request.append("Content-Type: text/plain\r\n");
            request.append("Connection: close\r\n");
            request.append("\r\n");
            request.append("abcdefgh"); // actual content of 8 bytes

            String rawResponse = connector.getResponse(request.toString());
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_BAD_REQUEST));
        }
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
        HttpParser.LOG.info("badMessage: 400 Bad messages EXPECTED...");

        StringBuilder request = new StringBuilder();
        request.append("POST / HTTP/1.1\r\n");
        request.append("Host: local\r\n");
        for (int n = 0; n < contentLengths.length; n++)
        {
            switch (contentLengths[n])
            {
                case CHUNKED:
                    request.append("Transfer-Encoding: chunked\r\n");
                    break;
                case DQUOTED_CHUNKED:
                    request.append("Transfer-Encoding: \"chunked\"\r\n");
                    break;
                case BAD_CHUNKED:
                    request.append("Transfer-Encoding: 'chunked'\r\n");
                    break;
                case UNKNOWN_TE:
                    request.append("Transfer-Encoding: bogus\r\n");
                    break;
                default:
                    request.append("Content-Length: ").append(contentLengths[n]).append("\r\n");
                    break;
            }
        }
        request.append("Content-Type: text/plain\r\n");
        request.append("\r\n");
        request.append("8;\r\n"); // chunk header
        request.append("abcdefgh"); // actual content of 8 bytes
        request.append("\r\n0;\r\n\r\n"); // last chunk

        String rawResponse = connector.getResponse(request.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_BAD_REQUEST));
    }

    /**
     * Examples of valid Chunked behaviors.
     */
    public static Stream<Arguments> http11TransferEncodingChunked()
    {
        return Stream.of(
            Arguments.of(Arrays.asList("chunked, ")), // results in 1 entry
            Arguments.of(Arrays.asList(", chunked")),

            // invalid tokens with chunked as last
            // no conflicts, chunked token is specified and is last, will result in chunked
            Arguments.of(Arrays.asList("bogus, chunked")),
            Arguments.of(Arrays.asList("'chunked', chunked")), // apostrophe characters with and without
            Arguments.of(Arrays.asList("identity, chunked")), // identity was removed in RFC2616 errata and has been dropped in RFC7230

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
        StringBuilder request = new StringBuilder();
        request.append("POST / HTTP/1.1\r\n");
        request.append("Host: local\r\n");
        tokens.forEach((token) -> request.append("Transfer-Encoding: ").append(token).append("\r\n"));
        request.append("Content-Type: text/plain\r\n");
        request.append("\r\n");
        request.append("8;\r\n"); // chunk header
        request.append("abcdefgh"); // actual content of 8 bytes
        request.append("\r\n0;\r\n\r\n"); // last chunk

        System.out.println(request.toString());

        String rawResponse = connector.getResponse(request.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response.status (" + response.getReason() + ")", response.getStatus(), is(HttpServletResponse.SC_OK));
    }

    public static Stream<Arguments> http11TransferEncodingInvalidChunked()
    {
        return Stream.of(
            // == Results in 400 Bad Request
            Arguments.of(Arrays.asList("bogus", "identity")), // 2 separate headers

            Arguments.of(Arrays.asList("bad")),
            Arguments.of(Arrays.asList("identity")),  // identity was removed in RFC2616 errata and has been dropped in RFC7230
            Arguments.of(Arrays.asList("'chunked'")), // apostrophe characters
            Arguments.of(Arrays.asList("`chunked`")), // backtick "quote" characters
            Arguments.of(Arrays.asList("[chunked]")), // bracketed (seen as mistake in several REST libraries)
            Arguments.of(Arrays.asList("{chunked}")), // json'd (seen as mistake in several REST libraries)
            Arguments.of(Arrays.asList("\u201Cchunked\u201D")), // opening and closing (fancy) double quotes characters

            // invalid tokens with chunked not as last
            Arguments.of(Arrays.asList("chunked, bogus")),
            Arguments.of(Arrays.asList("chunked, 'chunked'")),
            Arguments.of(Arrays.asList("chunked, identity")),
            Arguments.of(Arrays.asList("chunked, identity, chunked")), // duplicate chunked
            Arguments.of(Arrays.asList("chunked", "identity")), // 2 separate header lines

            // multiple chunked tokens present
            Arguments.of(Arrays.asList("chunked", "identity", "chunked")), // 3 separate header lines
            Arguments.of(Arrays.asList("chunked", "chunked")), // 2 separate header lines
            Arguments.of(Arrays.asList("chunked, chunked")) // on same line
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
        HttpParser.LOG.info("badMessage: 400 Bad messages EXPECTED...");
        StringBuilder request = new StringBuilder();
        request.append("POST / HTTP/1.1\r\n");
        request.append("Host: local\r\n");
        tokens.forEach((token) -> request.append("Transfer-Encoding: ").append(token).append("\r\n"));
        request.append("Content-Type: text/plain\r\n");
        request.append("\r\n");
        request.append("8;\r\n"); // chunk header
        request.append("abcdefgh"); // actual content of 8 bytes
        request.append("\r\n0;\r\n\r\n"); // last chunk

        System.out.println(request.toString());

        String rawResponse = connector.getResponse(request.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_BAD_REQUEST));
    }

    @Test
    public void testNoPath() throws Exception
    {
        String response = connector.getResponse("GET http://localhost:80 HTTP/1.1\r\n" +
            "Host: localhost:80\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkContains(response, offset, "pathInfo=/");
    }

    @Test
    public void testDate() throws Exception
    {
        String response = connector.getResponse("GET / HTTP/1.1\r\n" +
            "Host: localhost:80\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "Date: ");
        checkContains(response, offset, "pathInfo=/");
    }

    @Test
    public void testSetDate() throws Exception
    {
        String response = connector.getResponse("GET /?date=1+Jan+1970 HTTP/1.1\r\n" +
            "Host: localhost:80\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "Date: 1 Jan 1970");
        checkContains(response, offset, "pathInfo=/");
    }

    @Test
    public void testBadNoPath() throws Exception
    {
        String response = connector.getResponse("GET http://localhost:80/../cheat HTTP/1.1\r\n" +
            "Host: localhost:80\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 400");
    }

    @Test
    public void testOKPathDotDotPath() throws Exception
    {
        String response = connector.getResponse("GET /ooops/../path HTTP/1.0\r\nHost: localhost:80\r\n\n");
        checkContains(response, 0, "HTTP/1.1 200 OK");
        checkContains(response, 0, "pathInfo=/path");
    }

    @Test
    public void testBadPathDotDotPath() throws Exception
    {
        String response = connector.getResponse("GET /ooops/../../path HTTP/1.0\r\nHost: localhost:80\r\n\n");
        checkContains(response, 0, "HTTP/1.1 400 ");
        checkContains(response, 0, "reason: Bad Request");
    }

    @Test
    public void testBadDotDotPath() throws Exception
    {
        String response = connector.getResponse("GET ../path HTTP/1.0\r\nHost: localhost:80\r\n\n");
        checkContains(response, 0, "HTTP/1.1 400 ");
        checkContains(response, 0, "reason: Bad Request");
    }

    @Test
    public void testBadSlashDotDotPath() throws Exception
    {
        String response = connector.getResponse("GET /../path HTTP/1.0\r\nHost: localhost:80\r\n\n");
        checkContains(response, 0, "HTTP/1.1 400 ");
        checkContains(response, 0, "reason: Bad Request");
    }

    @Test
    public void test09() throws Exception
    {
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setHttpCompliance(HttpCompliance.RFC2616_LEGACY);
        LocalEndPoint endp = connector.executeRequest("GET /R1\n");
        endp.waitUntilClosed();
        String response = BufferUtil.toString(endp.takeOutput());

        int offset = 0;
        checkNotContained(response, offset, "HTTP/1.1");
        checkNotContained(response, offset, "200");
        checkContains(response, offset, "pathInfo=/R1");
    }

    @Test
    public void testSimple() throws Exception
    {
        String response = connector.getResponse("GET /R1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkContains(response, offset, "/R1");
    }

    @Test
    public void testEmptyNotPersistent() throws Exception
    {
        String response = connector.getResponse("GET /R1?empty=true HTTP/1.0\r\n" +
            "Host: localhost\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkNotContained(response, offset, "Content-Length");

        response = connector.getResponse("GET /R1?empty=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkContains(response, offset, "Connection: close");
        checkNotContained(response, offset, "Content-Length");
    }

    @Test
    public void testEmptyPersistent() throws Exception
    {
        String response = connector.getResponse("GET /R1?empty=true HTTP/1.0\r\n" +
            "Host: localhost\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n");

        int offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkContains(response, offset, "Content-Length: 0");
        checkNotContained(response, offset, "Connection: close");

        response = connector.getResponse("GET /R1?empty=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n");

        offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200");
        checkContains(response, offset, "Content-Length: 0");
        checkNotContained(response, offset, "Connection: close");
    }

    @Test
    public void testEmptyChunk() throws Exception
    {
        String response = connector.getResponse("GET /R1 HTTP/1.1\r\n" +
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
        String response = connector.getResponse("GET /R1 HTTP/1.1\r\n" +
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
        String response = connector.getResponse("GET /R1 HTTP/1.1\r\n" +
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
        // Expect TimeoutException logged
        connector.setIdleTimeout(1000);
        String response = connector.getResponse("GET /R1 HTTP/1.1\r\n" +
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
        String responsePOST = connector.getResponse("POST /R1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        String responseHEAD = connector.getResponse("HEAD /R1 HTTP/1.1\r\n" +
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
        assertTrue(postHeaders.equals(headHeaders));
    }

    @Test
    public void testHeadChunked() throws Exception
    {
        String responsePOST = connector.getResponse("POST /R1?no-content-length=true HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n", false, 1, TimeUnit.SECONDS);

        String responseHEAD = connector.getResponse("HEAD /R1?no-content-length=true HTTP/1.1\r\n" +
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
        assertTrue(postHeaders.equals(headHeaders));
    }

    @Test
    public void testBadHostPort() throws Exception
    {
        LOG.info("badMessage: Number formate exception expected ...");
        String response;

        response = connector.getResponse("GET http://localhost:EXPECTED_NUMBER_FORMAT_EXCEPTION/ HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 400");
    }

    @Test
    public void testNoHost() throws Exception
    {
        String response;

        response = connector.getResponse("GET / HTTP/1.1\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 400");
    }

    @Test
    public void testEmptyHost() throws Exception
    {
        String response;

        response = connector.getResponse("GET / HTTP/1.1\r\n" +
            "Host:\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 200");
    }

    @Test
    public void testBadURIencoding() throws Exception
    {
        String response = connector.getResponse("GET /bad/encoding%x HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 400");
    }

    @Test
    public void testBadUTF8FallsbackTo8859() throws Exception
    {
        LOG.info("badMessage: bad encoding expected ...");
        String response;

        response = connector.getResponse("GET /foo/bar%c0%00 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 400");

        response = connector.getResponse("GET /bad/utf8%c1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");
        checkContains(response, 0, "HTTP/1.1 200"); //now fallback to iso-8859-1
    }

    @Test
    public void testAutoFlush() throws Exception
    {
        int offset = 0;

        String response = connector.getResponse("GET /R1 HTTP/1.1\r\n" +
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
        server.stop();
        server.setHandler(new AbstractHandler()
        {
            @SuppressWarnings("unused")
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setStatus(200);
                OutputStream out = response.getOutputStream();
                out.flush();
                out.flush();
            }
        });
        server.start();

        String response = connector.getResponse("GET / HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n" +
            "\r\n");

        assertThat(response, Matchers.containsString("200 OK"));
    }

    @Test
    public void testCharset() throws Exception
    {
        String response = null;
        try
        {
            int offset = 0;
            response = connector.getResponse("GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n" +
                "0;\r\n" +
                "\r\n");
            offset = checkContains(response, offset, "HTTP/1.1 200");
            offset = checkContains(response, offset, "/R1");
            offset = checkContains(response, offset, "encoding=UTF-8");
            checkContains(response, offset, "12345");

            offset = 0;
            response = connector.getResponse("GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain; charset =  iso-8859-1 ; other=value\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n" +
                "0;\r\n" +
                "\r\n");
            offset = checkContains(response, offset, "HTTP/1.1 200");
            offset = checkContains(response, offset, "encoding=iso-8859-1");
            offset = checkContains(response, offset, "/R1");
            checkContains(response, offset, "12345");

            offset = 0;
            LOG.info("Expecting java.io.UnsupportedEncodingException");
            response = connector.getResponse("GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain; charset=unknown\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "5;\r\n" +
                "12345\r\n" +
                "0;\r\n" +
                "\r\n");

            offset = checkContains(response, offset, "HTTP/1.1 200");
            offset = checkContains(response, offset, "encoding=unknown");
            offset = checkContains(response, offset, "/R1");
            checkContains(response, offset, "UnsupportedEncodingException");
        }
        catch (Exception e)
        {
            if (response != null)
                System.err.println(response);
            throw e;
        }
    }

    @Test
    public void testUnconsumed() throws Exception
    {
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
                "abcdefghij\r\n";

        LocalEndPoint endp = connector.executeRequest(requests);
        String response = endp.getResponse() + endp.getResponse();

        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "pathInfo=/R1");
        offset = checkContains(response, offset, "1234");
        checkNotContained(response, offset, "56789");
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "pathInfo=/R2");
        offset = checkContains(response, offset, "encoding=UTF-8");
        checkContains(response, offset, "abcdefghij");
    }

    @Test
    public void testUnconsumedTimeout() throws Exception
    {
        connector.setIdleTimeout(500);
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
        String response = connector.getResponse(requests, 2000, TimeUnit.MILLISECONDS);
        assertThat(NanoTime.millisSince(start), lessThanOrEqualTo(2000L));

        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "pathInfo=/R1");
        offset = checkContains(response, offset, "1234");
        checkNotContained(response, offset, "56789");
    }

    @Test
    public void testUnconsumedErrorRead() throws Exception
    {
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

        LocalEndPoint endp = connector.executeRequest(requests);
        String response = endp.getResponse() + endp.getResponse();

        offset = checkContains(response, offset, "HTTP/1.1 499");
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "/R2");
        offset = checkContains(response, offset, "encoding=UTF-8");
        checkContains(response, offset, "abcdefghij");
    }

    @Test
    public void testUnconsumedErrorStream() throws Exception
    {
        int offset = 0;
        String requests =
            "GET /R1?error=599 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: application/data; charset=utf-8\r\n" +
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

        LocalEndPoint endp = connector.executeRequest(requests);
        String response = endp.getResponse() + endp.getResponse();

        offset = checkContains(response, offset, "HTTP/1.1 599");
        offset = checkContains(response, offset, "HTTP/1.1 200");
        offset = checkContains(response, offset, "/R2");
        offset = checkContains(response, offset, "encoding=UTF-8");
        checkContains(response, offset, "abcdefghij");
    }

    @Test
    public void testUnconsumedException() throws Exception
    {
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

        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            LOG.info("EXPECTING: java.lang.IllegalStateException...");
            String response = connector.getResponse(requests);
            offset = checkContains(response, offset, "HTTP/1.1 500");
            offset = checkContains(response, offset, "Connection: close");
            checkNotContained(response, offset, "HTTP/1.1 200");
        }
    }

    @Test
    public void testConnection() throws Exception
    {
        String response = null;
        try
        {
            int offset = 0;
            response = connector.getResponse("GET /R1 HTTP/1.1\r\n" +
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
        String response = null;
        try
        {
            int offset = 0;
            String cookie = "thisisastringthatshouldreachover1kbytes";
            for (int i = 0; i < 100; i++)
            {
                cookie += "xxxxxxxxxxxx";
            }
            response = connector.getResponse("GET / HTTP/1.1\r\n" +
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

        String response = connector.getResponse(request.toString());
        offset = checkContains(response, offset, "HTTP/1.1 431");
        checkContains(response, offset, "<h1>Bad Message 431</h1>");
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
        server.stop();
        server.setHandler(new AbstractHandler()
        {
            @SuppressWarnings("unused")
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader(HttpHeader.CONTENT_TYPE.toString(), MimeTypes.Type.TEXT_HTML.toString());
                response.setHeader("LongStr", longstr);
                PrintWriter writer = response.getWriter();
                writer.write("<html><h1>FOO</h1></html>");
                writer.flush();
                if (writer.checkError())
                    checkError.countDown();
                response.flushBuffer();
            }
        });
        server.start();

        String response = null;
        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            LOG.info("Expect IOException: Response header too large...");
            response = connector.getResponse("GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
            );

            checkContains(response, 0, "HTTP/1.1 500");
            assertTrue(checkError.await(1, TimeUnit.SECONDS));
        }
        catch (Exception e)
        {
            if (response != null)
                System.err.println(response);
            throw e;
        }
    }

    @Test
    public void testAllowedLargeResponse() throws Exception
    {
        connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setResponseHeaderSize(16 * 1024);
        connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setOutputBufferSize(8 * 1024);

        byte[] bytes = new byte[12 * 1024];
        Arrays.fill(bytes, (byte)'X');
        final String longstr = "thisisastringthatshouldreachover12kbytes-" + new String(bytes, StandardCharsets.ISO_8859_1) + "_Z_";
        final CountDownLatch checkError = new CountDownLatch(1);
        server.stop();
        server.setHandler(new AbstractHandler()
        {
            @SuppressWarnings("unused")
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader(HttpHeader.CONTENT_TYPE.toString(), MimeTypes.Type.TEXT_HTML.toString());
                response.setHeader("LongStr", longstr);
                PrintWriter writer = response.getWriter();
                writer.write("<html><h1>FOO</h1></html>");
                writer.flush();
                if (writer.checkError())
                    checkError.countDown();
                response.flushBuffer();
            }
        });
        server.start();

        String response = null;
        response = connector.getResponse("GET / HTTP/1.1\r\n" +
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
        String response = null;
        try (StacklessLogging stackless = new StacklessLogging(HttpParser.class))
        {
            int offset = 0;

            response = connector.getResponse("OPTIONS * HTTP/1.1\r\n" +
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
            response = connector.getResponse("GET * HTTP/1.1\r\n" +
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
            response = connector.getResponse("GET ** HTTP/1.1\r\n" +
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
        String response = null;
        try
        {
            int offset = 0;

            response = connector.getResponse("CONNECT www.webtide.com:8080 HTTP/1.1\r\n" +
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
        server.stop();
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                jettyRequest.setHandled(true);
                IO.copy(request.getInputStream(), IO.getNullStream());

                HttpConnection connection = HttpConnection.getCurrentConnection();
                long bytesIn = connection.getBytesIn();
                assertThat(bytesIn, greaterThan(dataLength));
            }
        });
        server.start();

        LocalEndPoint localEndPoint = connector.executeRequest("" +
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

        localEndPoint = connector.executeRequest("" +
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

    private int checkContains(String s, int offset, String c)
    {
        assertThat(s.substring(offset), Matchers.containsString(c));
        return s.indexOf(c, offset);
    }

    private void checkNotContained(String s, int offset, String c)
    {
        assertThat(s.substring(offset), Matchers.not(Matchers.containsString(c)));
    }
}
