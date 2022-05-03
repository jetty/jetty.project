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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Exchanger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class HttpServerTestBase extends HttpServerTestFixture
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpServerTestBase.class);

    private static final String REQUEST1_HEADER = "POST / HTTP/1.0\n" +
        "Host: localhost\n" +
        "Content-Type: text/xml; charset=utf-8\n" +
        "Connection: close\n" +
        "Content-Length: ";
    private static final String REQUEST1_CONTENT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<nimbus xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
        "        xsi:noNamespaceSchemaLocation=\"nimbus.xsd\" version=\"1.0\">\n" +
        "</nimbus>";
    private static final String REQUEST1 = REQUEST1_HEADER + REQUEST1_CONTENT.getBytes().length + "\n\n" + REQUEST1_CONTENT;

    private static final String RESPONSE1 = "HTTP/1.1 200 OK\n" +
        "Content-Length: 13\n" +
        "Server: Jetty(" + Server.getVersion() + ")\n" +
        "\n" +
        "Hello world\n";

    // Break the request up into three pieces, splitting the header.
    private static final String FRAGMENT1 = REQUEST1.substring(0, 16);
    private static final String FRAGMENT2 = REQUEST1.substring(16, 34);
    private static final String FRAGMENT3 = REQUEST1.substring(34);

    protected static final String REQUEST2_HEADER =
        "POST / HTTP/1.0\n" +
            "Host: localhost\n" +
            "Content-Type: text/xml; charset=ISO-8859-1\n" +
            "Content-Length: ";
    protected static final String REQUEST2_CONTENT =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<nimbus xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "        xsi:noNamespaceSchemaLocation=\"nimbus.xsd\" version=\"1.0\">\n" +
            "    <request requestId=\"1\">\n" +
            "        <getJobDetails>\n" +
            "            <jobId>73</jobId>\n" +
            "        </getJobDetails>\n" +
            "    </request>\n" +
            "</nimbus>";
    protected static final String REQUEST2 = REQUEST2_HEADER + REQUEST2_CONTENT.getBytes().length + "\n\n" + REQUEST2_CONTENT;

    protected static final String RESPONSE2_CONTENT =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<nimbus xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "        xsi:noNamespaceSchemaLocation=\"nimbus.xsd\" version=\"1.0\">\n" +
            "    <request requestId=\"1\">\n" +
            "        <getJobDetails>\n" +
            "            <jobId>73</jobId>\n" +
            "        </getJobDetails>\n" +
            "    </request>\n" +
            "</nimbus>\n";
    protected static final String RESPONSE2 =
        "HTTP/1.1 200 OK\n" +
            "Content-Type: text/xml;charset=iso-8859-1\n" +
            "Content-Length: " + RESPONSE2_CONTENT.getBytes().length + "\n" +
            "Server: Jetty(" + Server.getVersion() + ")\n" +
            "\n" +
            RESPONSE2_CONTENT;

    @Test
    public void testSimple() throws Exception
    {
        configureServer(new HelloWorldHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, Matchers.containsString("HTTP/1.1 200 OK"));
            assertThat(response, Matchers.containsString("Hello world"));
        }
    }

    @Test
    public void testOPTIONS() throws Exception
    {
        configureServer(new OptionsHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            os.write(("OPTIONS * HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, Matchers.containsString("HTTP/1.1 200 OK"));
            assertThat(response, Matchers.containsString("Allow: GET"));
        }
    }

    @Test
    public void testGETStar() throws Exception
    {
        configureServer(new OptionsHandler());
        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            os.write(("GET * HTTP/1.1\r\n" +
                "Host: " + _serverURI.getHost() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, Matchers.containsString("HTTP/1.1 400 "));
            assertThat(response, Matchers.not(Matchers.containsString("Allow: ")));
        }
    }

    /*
     * Feed a full header method
     */
    @Test
    public void testFullMethod() throws Exception
    {
        configureServer(new HelloWorldHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
             StacklessLogging ignored = new StacklessLogging(HttpConnection.class))
        {
            client.setSoTimeout(10000);
            LOG.info("expect request is too large, then ISE extra data ...");
            OutputStream os = client.getOutputStream();

            byte[] buffer = new byte[64 * 1024];
            Arrays.fill(buffer, (byte)'A');

            try
            {
                os.write(buffer);
                os.flush();
            }
            catch (Exception e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("write exception", e);
            }

            // Read the response.
            String response = readResponse(client);

            assertThat(response, Matchers.containsString("HTTP/1.1 431 "));
        }
    }

    /*
     * Feed a full header method
     */
    @Test
    public void testFullURI() throws Exception
    {
        configureServer(new HelloWorldHandler());

        int maxHeaderSize = 1000;
        _httpConfiguration.setRequestHeaderSize(maxHeaderSize);

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
             StacklessLogging ignored = new StacklessLogging(HttpConnection.class))
        {
            LOG.info("expect URI is too large");
            OutputStream os = client.getOutputStream();

            // Take into account the initial bytes for the HTTP method.
            byte[] buffer = new byte[5 + maxHeaderSize];
            buffer[0] = 'G';
            buffer[1] = 'E';
            buffer[2] = 'T';
            buffer[3] = ' ';
            buffer[4] = '/';
            Arrays.fill(buffer, 5, buffer.length, (byte)'A');

            os.write(buffer);
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, Matchers.containsString("HTTP/1.1 414 "));
        }
    }

    @Test
    public void testBadURI() throws Exception
    {
        configureServer(new HelloWorldHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            os.write("GET /%xx HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, Matchers.containsString("HTTP/1.1 400 "));
        }
    }

    @Test
    public void testExceptionThrownInHandlerLoop() throws Exception
    {
        configureServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                throw new QuietServletException("TEST handler exception");
            }
        });

        StringBuffer request = new StringBuffer("GET / HTTP/1.0\r\n");
        request.append("Host: localhost\r\n\r\n");

        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        OutputStream os = client.getOutputStream();

        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
        {
            LOG.info("Expecting ServletException: TEST handler exception...");
            os.write(request.toString().getBytes());
            os.flush();

            String response = readResponse(client);
            assertThat(response, Matchers.containsString(" 500 "));
        }
    }

    @Test
    public void testExceptionThrownInHandler() throws Exception
    {
        configureServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                throw new QuietServletException("TEST handler exception");
            }
        });

        StringBuffer request = new StringBuffer("GET / HTTP/1.0\r\n");
        request.append("Host: localhost\r\n\r\n");

        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        OutputStream os = client.getOutputStream();

        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
        {
            LOG.info("Expecting ServletException: TEST handler exception...");
            os.write(request.toString().getBytes());
            os.flush();

            String response = readResponse(client);
            assertThat(response, Matchers.containsString(" 500 "));
        }
    }

    @Test
    public void testInterruptedRequest() throws Exception
    {
        final AtomicBoolean fourBytesRead = new AtomicBoolean(false);
        final AtomicBoolean earlyEOFException = new AtomicBoolean(false);
        configureServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                int contentLength = request.getContentLength();
                ServletInputStream inputStream = request.getInputStream();
                for (int i = 0; i < contentLength; i++)
                {
                    try
                    {
                        inputStream.read();
                    }
                    catch (EofException e)
                    {
                        earlyEOFException.set(true);
                        throw new QuietServletException(e);
                    }
                    if (i == 3)
                        fourBytesRead.set(true);
                }
            }
        });

        StringBuffer request = new StringBuffer("GET / HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("Content-length: 6\n\n");
        request.append("foo");

        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        OutputStream os = client.getOutputStream();

        os.write(request.toString().getBytes());
        os.flush();
        client.shutdownOutput();
        String response = readResponse(client);
        client.close();

        assertThat("response contains 500", response, Matchers.containsString(" 500 "));
        assertThat("The 4th byte (-1) has not been passed to the handler", fourBytesRead.get(), is(false));
        assertThat("EofException has been caught", earlyEOFException.get(), is(true));
    }

    /*
     * Feed a full header method
     */
    @Test
    public void testFullHeader() throws Exception
    {

        configureServer(new HelloWorldHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
             StacklessLogging ignored = new StacklessLogging(HttpConnection.class))
        {
            LOG.info("expect header is too large ...");
            OutputStream os = client.getOutputStream();

            byte[] buffer = new byte[64 * 1024];
            buffer[0] = 'G';
            buffer[1] = 'E';
            buffer[2] = 'T';
            buffer[3] = ' ';
            buffer[4] = '/';
            buffer[5] = ' ';
            buffer[6] = 'H';
            buffer[7] = 'T';
            buffer[8] = 'T';
            buffer[9] = 'P';
            buffer[10] = '/';
            buffer[11] = '1';
            buffer[12] = '.';
            buffer[13] = '0';
            buffer[14] = '\n';
            buffer[15] = 'H';
            buffer[16] = ':';
            Arrays.fill(buffer, 17, buffer.length - 1, (byte)'A');
            // write the request.
            try
            {
                os.write(buffer);
                os.flush();
            }
            catch (Exception e)
            {
                // Ignore exceptions during writing, so long as we can read response below
            }

            // Read the response.
            try
            {
                String response = readResponse(client);
                assertThat(response, Matchers.containsString("HTTP/1.1 431 "));
            }
            catch (Exception e)
            {
                LOG.warn("TODO Early close???");
                // TODO #1832 evaluate why we sometimes get an early close on this test
            }
        }
    }

    /*
     * Feed the server the entire request at once.
     */
    @Test
    public void testRequest1() throws Exception
    {
        configureServer(new HelloWorldHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            os.write(REQUEST1.getBytes());
            os.flush();

            // Read the response.
            String response = readResponse(client);

            // Check the response
            assertEquals(RESPONSE1, response, "response");
        }
    }

    @Test
    public void testFragmentedChunk() throws Exception
    {
        configureServer(new EchoHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            os.write(("GET /R2 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes());
            os.flush();
            Thread.sleep(1000);
            os.write(("5").getBytes());
            Thread.sleep(1000);
            os.write(("\r\n").getBytes());
            os.flush();
            Thread.sleep(1000);
            os.write(("ABCDE\r\n" +
                "0;\r\n\r\n").getBytes());
            os.flush();

            // Read the response.
            String response = readResponse(client);
            assertThat(response, containsString("200"));
        }
    }

    @Test
    public void testTrailingContent() throws Exception
    {
        configureServer(new EchoHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            //@checkstyle-disable-check : IllegalTokenText
            os.write(("GET /R2 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 5\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "ABCDE\r\n" +
                "\r\n"
                //@checkstyle-enable-check : IllegalTokenText
            ).getBytes());
            os.flush();

            // Read the response.
            String response = readResponse(client);
            assertTrue(response.indexOf("200") > 0);
        }
    }

    /*
     * Feed the server fragmentary headers and see how it copes with it.
     */
    @Test
    public void testRequest1Fragments() throws Exception
    {
        configureServer(new HelloWorldHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            // Write a fragment, flush, sleep, write the next fragment, etc.
            os.write(FRAGMENT1.getBytes());
            os.flush();
            Thread.sleep(PAUSE);
            os.write(FRAGMENT2.getBytes());
            os.flush();
            Thread.sleep(PAUSE);
            os.write(FRAGMENT3.getBytes());
            os.flush();

            // Read the response
            String response = readResponse(client);

            // Check the response
            assertEquals(RESPONSE1, response, "response");
        }
    }

    @Test
    public void testRequest2() throws Exception
    {
        configureServer(new EchoHandler());

        byte[] bytes = REQUEST2.getBytes();
        for (int i = 0; i < LOOPS; i++)
        {
            try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
            {
                OutputStream os = client.getOutputStream();

                os.write(bytes);
                os.flush();

                // Read the response
                String response = readResponse(client);

                // Check the response
                assertEquals(RESPONSE2, response, "response " + i);
            }
            catch (IOException e)
            {
                e.printStackTrace();
                _server.dumpStdErr();
                throw e;
            }
        }
    }

    @Test
    @DisabledIfSystemProperty(named = "env", matches = "ci") // TODO: SLOW, needs review
    public void testRequest2Sliced2() throws Exception
    {
        configureServer(new EchoHandler());

        byte[] bytes = REQUEST2.getBytes();
        int splits = bytes.length - REQUEST2_CONTENT.length() + 5;
        for (int i = 0; i < splits; i += 1)
        {
            int[] points = new int[]{i};
            StringBuilder message = new StringBuilder();

            message.append("iteration #").append(i + 1);

            try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
            {
                OutputStream os = client.getOutputStream();

                writeFragments(bytes, points, message, os);

                // Read the response
                String response = readResponse(client);

                // Check the response
                assertEquals(RESPONSE2, response, "response for " + i + " " + message.toString());

                Thread.sleep(10);
            }
        }
    }

    @Test
    @DisabledIfSystemProperty(named = "env", matches = "ci") // TODO: SLOW, needs review
    public void testRequest2Sliced3() throws Exception
    {
        configureServer(new EchoHandler());

        byte[] bytes = REQUEST2.getBytes();
        int splits = bytes.length - REQUEST2_CONTENT.length() + 5;
        for (int i = 0; i < splits; i += 1)
        {
            int[] points = new int[]{i, i + 1};
            StringBuilder message = new StringBuilder();

            message.append("iteration #").append(i + 1);

            try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
            {
                OutputStream os = client.getOutputStream();

                writeFragments(bytes, points, message, os);

                // Read the response
                String response = readResponse(client);

                // Check the response
                assertEquals(RESPONSE2, response, "response for " + i + " " + message.toString());

                Thread.sleep(10);
            }
        }
    }

    @Test // TODO: Parameterize
    public void testFlush() throws Exception
    {
        configureServer(new DataHandler());

        String[] encoding = {"NONE", "UTF-8", "ISO-8859-1", "ISO-8859-2"};
        for (int e = 0; e < encoding.length; e++)
        {
            for (int b = 1; b <= 128; b = b == 1 ? 2 : b == 2 ? 32 : b == 32 ? 128 : 129)
            {
                for (int w = 41; w < 42; w += 4096)
                {
                    for (int c = 0; c < 1; c++)
                    {
                        String test = encoding[e] + "x" + b + "x" + w + "x" + c;
                        try
                        {
                            URL url = new URL(_scheme + "://localhost:" + _serverURI.getPort() + "/?writes=" + w + "&block=" + b + (e == 0 ? "" : ("&encoding=" + encoding[e])) + (c == 0 ? "&chars=true" : ""));

                            InputStream in = (InputStream)url.getContent();
                            String response = IO.toString(in, e == 0 ? null : encoding[e]);

                            assertEquals(b * w, response.length(), test);
                        }
                        catch (Exception x)
                        {
                            System.err.println(test);
                            x.printStackTrace();
                            throw x;
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testBlockingWhileReadingRequestContent() throws Exception
    {
        configureServer(new DataHandler());

        long start = System.currentTimeMillis();
        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            os.write((
                "GET /data?writes=1024&block=256 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "connection: close\r\n" +
                    "content-type: unknown\r\n" +
                    "content-length: 30\r\n" +
                    "\r\n"
            ).getBytes());
            os.flush();
            Thread.sleep(200);
            os.write((
                "\r\n23456890"
            ).getBytes());
            os.flush();
            Thread.sleep(1000);
            os.write((
                "abcdefghij"
            ).getBytes());
            os.flush();
            Thread.sleep(1000);
            os.write((
                "0987654321\r\n"
            ).getBytes());
            os.flush();

            int total = 0;
            int len = 0;

            byte[] buf = new byte[1024 * 64];
            int sleeps = 0;
            while (len >= 0)
            {
                len = is.read(buf);
                if (len > 0)
                {
                    total += len;
                    if ((total / 10240) > sleeps)
                    {
                        sleeps++;
                        Thread.sleep(100);
                    }
                }
            }

            assertTrue(total > (1024 * 256));
            assertTrue(30000L > (System.currentTimeMillis() - start));
        }
    }

    @Test
    public void testBlockingReadBadChunk() throws Exception
    {
        configureServer(new ReadHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            client.setSoTimeout(600000);
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            os.write((
                "GET /data HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: unknown\r\n" +
                    "transfer-encoding: chunked\r\n" +
                    "\r\n"
            ).getBytes());
            os.flush();
            Thread.sleep(50);
            os.write((
                "a\r\n" +
                    "123456890\r\n"
            ).getBytes());
            os.flush();

            Thread.sleep(50);
            os.write((
                "4\r\n" +
                    "abcd\r\n"
            ).getBytes());
            os.flush();

            Thread.sleep(50);
            os.write((
                "X\r\n" +
                    "abcd\r\n"
            ).getBytes());
            os.flush();

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(is));

            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), containsString("EofException"));
            assertThat(response.getContent(), containsString("Early EOF"));
        }
    }

    @Test
    public void testBlockingWhileWritingResponseContent() throws Exception
    {
        configureServer(new DataHandler());

        long start = System.currentTimeMillis();
        int total = 0;
        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            os.write((
                "GET /data?writes=256&block=1024 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "connection: close\r\n" +
                    "content-type: unknown\r\n" +
                    "\r\n"
            ).getBytes());
            os.flush();

            int len = 0;
            byte[] buf = new byte[1024 * 32];
            int sleeps = 0;
            while (len >= 0)
            {
                len = is.read(buf);
                if (len > 0)
                {
                    total += len;
                    if ((total / 10240) > sleeps)
                    {
                        Thread.sleep(200);
                        sleeps++;
                    }
                }
            }

            assertTrue(total > (256 * 1024));
            assertTrue(30000L > (System.currentTimeMillis() - start));
        }
    }

    @Test
    public void testCloseWhileWriteBlocked() throws Exception
    {
        configureServer(new DataHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            os.write((
                "GET /data?encoding=iso-8859-1&writes=100&block=100000 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "connection: close\r\n" +
                    "content-type: unknown\r\n" +
                    "\r\n"
            ).getBytes());
            os.flush();

            // Read the first part of the response
            byte[] buf = new byte[1024 * 8];
            is.read(buf);

            // sleep to ensure server is blocking
            Thread.sleep(500);

            // Close the client
            client.close();
        }

        Thread.sleep(200);
        // check server is still handling requests quickly
        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            client.setSoTimeout(500);
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            os.write(("GET /data?writes=1&block=1024 HTTP/1.1\r\n" +
                "host: localhost:" + _serverURI.getPort() + "\r\n" +
                "connection: close\r\n" +
                "content-type: unknown\r\n" +
                "\r\n"
            ).getBytes());
            os.flush();

            String response = IO.toString(is);
            assertThat(response, startsWith("HTTP/1.1 200 OK"));
        }
    }

    @Test
    public void testBigBlocks() throws Exception
    {
        configureServer(new BigBlockHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            client.setSoTimeout(20000);

            OutputStream os = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));

            os.write((
                "GET /r1 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "\r\n" +
                    "GET /r2 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "connection: close\r\n" +
                    "\r\n"
            ).getBytes());
            os.flush();

            // read the chunked response header
            boolean chunked = false;
            boolean closed = false;
            while (true)
            {
                String line = in.readLine();
                if (line == null || line.length() == 0)
                    break;

                chunked |= "Transfer-Encoding: chunked".equals(line);
                closed |= "Connection: close".equals(line);
            }
            assertTrue(chunked);
            assertFalse(closed);

            // Read the chunks
            int max = Integer.MIN_VALUE;
            while (true)
            {
                String chunk = in.readLine();
                String line = in.readLine();
                if (line.length() == 0)
                    break;
                int len = line.length();
                assertEquals(Integer.valueOf(chunk, 16).intValue(), len);
                if (max < len)
                    max = len;
            }

            // Check that biggest chunk was <= buffer size
            assertEquals(_connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().getOutputBufferSize(), max);

            // read and check the times are < 999ms
            String[] times = in.readLine().split(",");
            for (String t : times)
            {
                assertTrue(Integer.parseInt(t) < 999);
            }

            // read the EOF chunk
            String end = in.readLine();
            assertEquals("0", end);
            end = in.readLine();
            assertEquals(0, end.length());

            // read the non-chunked response header
            chunked = false;
            closed = false;
            while (true)
            {
                String line = in.readLine();
                if (line == null || line.length() == 0)
                    break;

                chunked |= "Transfer-Encoding: chunked".equals(line);
                closed |= "Connection: close".equals(line);
            }
            assertFalse(chunked);
            assertTrue(closed);

            String bigline = in.readLine();
            assertEquals(10 * 128 * 1024, bigline.length());

            // read and check the times are < 999ms
            times = in.readLine().split(",");
            for (String t : times)
            {
                assertTrue(Integer.parseInt(t) < 999, t);
            }

            // check close
            assertNull(in.readLine());
        }
    }

    // Handler that sends big blocks of data in each of 10 writes, and then sends the time it took for each big block.
    protected static class BigBlockHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            byte[] buf = new byte[128 * 1024];
            for (int i = 0; i < buf.length; i++)
            {
                buf[i] = (byte)("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_".charAt(i % 63));
            }

            baseRequest.setHandled(true);
            response.setStatus(200);
            response.setContentType("text/plain");
            ServletOutputStream out = response.getOutputStream();
            long[] times = new long[10];
            for (int i = 0; i < times.length; i++)
            {
                // System.err.println("\nBLOCK "+request.getRequestURI()+" "+i);
                long start = System.currentTimeMillis();
                out.write(buf);
                long end = System.currentTimeMillis();
                times[i] = end - start;
                // System.err.println("Block "+request.getRequestURI()+" "+i+" "+times[i]);
            }
            out.println();
            for (long t : times)
            {
                out.print(t);
                out.print(",");
            }
            out.close();
        }
    }

    @Test
    public void testPipeline() throws Exception
    {
        AtomicInteger served = new AtomicInteger();
        configureServer(new HelloWorldHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                served.incrementAndGet();
                super.handle(target, baseRequest, request, response);
            }
        });

        int pipeline = 64;
        {
            try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
            {
                served.set(0);
                client.setSoTimeout(5000);
                OutputStream os = client.getOutputStream();

                String request = "";

                for (int i = 1; i < pipeline; i++)
                {
                    request +=
                        "GET /data?writes=1&block=16&id=" + i + " HTTP/1.1\r\n" +
                            "host: localhost:" + _serverURI.getPort() + "\r\n" +
                            "user-agent: testharness/1.0 (blah foo/bar)\r\n" +
                            "accept-encoding: nothing\r\n" +
                            "cookie: aaa=1234567890\r\n" +
                            "\r\n";
                }

                request +=
                    "GET /data?writes=1&block=16 HTTP/1.1\r\n" +
                        "host: localhost:" + _serverURI.getPort() + "\r\n" +
                        "user-agent: testharness/1.0 (blah foo/bar)\r\n" +
                        "accept-encoding: nothing\r\n" +
                        "cookie: aaa=bbbbbb\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

                os.write(request.getBytes());
                os.flush();

                LineNumberReader in = new LineNumberReader(new InputStreamReader(client.getInputStream()));

                String line = in.readLine();
                int count = 0;
                while (line != null)
                {
                    if ("HTTP/1.1 200 OK".equals(line))
                        count++;
                    line = in.readLine();
                }
                assertEquals(pipeline, served.get());
                assertEquals(pipeline, count);
            }
        }
    }

    @Test
    public void testRecycledWriters() throws Exception
    {
        configureServer(new EchoHandler());
        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            os.write((
                "POST /echo?charset=utf-8 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: text/plain; charset=utf-8\r\n" +
                    "content-length: 10\r\n" +
                    "\r\n").getBytes(StandardCharsets.ISO_8859_1));

            os.write((
                "123456789\n"
            ).getBytes("utf-8"));

            os.write((
                "POST /echo?charset=utf-8 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: text/plain; charset=utf-8\r\n" +
                    "content-length: 10\r\n" +
                    "\r\n"
            ).getBytes(StandardCharsets.ISO_8859_1));

            os.write((
                "abcdefghZ\n"
            ).getBytes("utf-8"));

            String content = "Wibble";
            byte[] contentB = content.getBytes("utf-8");
            os.write((
                "POST /echo?charset=utf-16 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: text/plain; charset=utf-8\r\n" +
                    "content-length: " + contentB.length + "\r\n" +
                    "connection: close\r\n" +
                    "\r\n"
            ).getBytes(StandardCharsets.ISO_8859_1));
            os.write(contentB);

            os.flush();

            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            IO.copy(is, bout);
            byte[] b = bout.toByteArray();

            //System.err.println("OUTPUT: "+new String(b));
            int i = 0;
            while (b[i] != 'Z')
            {
                i++;
            }
            int state = 0;
            while (state != 4)
            {
                switch (b[i++])
                {
                    case '\r':
                        if (state == 0 || state == 2)
                            state++;
                        continue;
                    case '\n':
                        if (state == 1 || state == 3)
                            state++;
                        continue;

                    default:
                        state = 0;
                }
            }

            String in = new String(b, 0, i, StandardCharsets.UTF_8);
            assertThat(in, containsString("123456789"));
            assertThat(in, containsString("abcdefghZ"));
            assertFalse(in.contains("Wibble"));

            in = new String(b, i, b.length - i, StandardCharsets.UTF_16);
            assertEquals("Wibble\n", in);
        }
    }

    @Test
    public void testHead() throws Exception
    {
        configureServer(new EchoHandler(false));

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            //@checkstyle-disable-check : IllegalTokenText
            os.write((
                "POST /R1 HTTP/1.1\r\n" +
                    "Host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: text/plain; charset=utf-8\r\n" +
                    "content-length: 10\r\n" +
                    "\r\n" +
                    "123456789\n" +

                    "HEAD /R2 HTTP/1.1\r\n" +
                    "Host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: text/plain; charset=utf-8\r\n" +
                    "content-length: 10\r\n" +
                    "\r\n" +
                    "ABCDEFGHI\n" +

                    "POST /R3 HTTP/1.1\r\n" +
                    "Host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: text/plain; charset=utf-8\r\n" +
                    "content-length: 10\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "abcdefghi\n"
                //@checkstyle-enable-check : IllegalTokenText
            ).getBytes(StandardCharsets.ISO_8859_1));

            String in = IO.toString(is);
            assertThat(in, containsString("123456789"));
            assertThat(in, not(containsString("ABCDEFGHI")));
            assertThat(in, containsString("abcdefghi"));
        }
    }

    @Test
    public void testRecycledReaders() throws Exception
    {
        configureServer(new EchoHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            os.write((
                "POST /echo/0?charset=utf-8 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: text/plain; charset=utf-8\r\n" +
                    "content-length: 10\r\n" +
                    "\r\n").getBytes(StandardCharsets.ISO_8859_1));

            os.write((
                "123456789\n"
            ).getBytes("utf-8"));

            os.write((
                "POST /echo/1?charset=utf-8 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: text/plain; charset=utf-8\r\n" +
                    "content-length: 10\r\n" +
                    "\r\n"
            ).getBytes(StandardCharsets.ISO_8859_1));

            os.write((
                "abcdefghi\n"
            ).getBytes(StandardCharsets.UTF_8));

            String content = "Wibble";
            byte[] contentB = content.getBytes(StandardCharsets.UTF_16);
            os.write((
                "POST /echo/2?charset=utf-8 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "content-type: text/plain; charset=utf-16\r\n" +
                    "content-length: " + contentB.length + "\r\n" +
                    "connection: close\r\n" +
                    "\r\n"
            ).getBytes(StandardCharsets.ISO_8859_1));
            os.write(contentB);

            os.flush();

            String in = IO.toString(is);
            assertThat(in, containsString("123456789"));
            assertThat(in, containsString("abcdefghi"));
            assertThat(in, containsString("Wibble"));
        }
    }

    @Test
    public void testBlockedClient() throws Exception
    {
        configureServer(new HelloWorldHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            // Send a request with chunked input and expect 100
            os.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Expect: 100-continue\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "\r\n"
            ).getBytes());

            // Never send a body.
            // HelloWorldHandler does not read content, so 100 is not sent.
            // So close will have to happen anyway, without reset!

            os.flush();

            client.setSoTimeout(2000);
            long start = System.currentTimeMillis();
            String in = IO.toString(is);
            assertTrue(System.currentTimeMillis() - start < 1000);
            assertTrue(in.indexOf("Connection: close") > 0);
            assertTrue(in.indexOf("Hello world") > 0);
        }
    }

    @Test
    public void testCommittedError() throws Exception
    {
        CommittedErrorHandler handler = new CommittedErrorHandler();
        configureServer(handler);

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
             StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
        {
            LOG.info("Expecting exception after commit then could not send 500....");
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            // Send a request
            os.write(("GET / HTTP/1.1\r\n" +
                "Host: localhost:" + _serverURI.getPort() + "\r\n" +
                "\r\n"
            ).getBytes());
            os.flush();

            client.setSoTimeout(2000);
            String in = IO.toString(is);

            assertEquals(-1, is.read()); // Closed by error!

            assertThat(in, containsString("HTTP/1.1 200 OK"));
            assertTrue(in.indexOf("Transfer-Encoding: chunked") > 0);
            assertTrue(in.indexOf("Now is the time for all good men to come to the aid of the party") > 0);
            assertThat(in, Matchers.not(Matchers.containsString("\r\n0\r\n")));

            client.close();
            Thread.sleep(200);

            assertFalse(handler._endp.isOpen());
        }
    }

    public static class CommittedErrorHandler extends AbstractHandler
    {
        public EndPoint _endp;

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            _endp = baseRequest.getHttpChannel().getEndPoint();
            response.setHeader("test", "value");
            response.setStatus(200);
            response.setContentType("text/plain");
            response.getWriter().println("Now is the time for all good men to come to the aid of the party");
            response.getWriter().flush();
            response.flushBuffer();

            throw new ServletException(new Exception("exception after commit"));
        }
    }

    protected static class AvailableHandler extends AbstractHandler
    {
        public Exchanger<Object> _ex = new Exchanger<>();

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.setContentType("text/plain");
            InputStream in = request.getInputStream();
            ServletOutputStream out = response.getOutputStream();

            // this should initially be 0 bytes available.
            int avail = in.available();
            out.println(avail);

            // block for the first character
            String buf = "";
            buf += (char)in.read();

            // read remaining available bytes
            avail = in.available();
            out.println(avail);
            for (int i = 0; i < avail; i++)
            {
                buf += (char)in.read();
            }

            avail = in.available();
            out.println(avail);

            try
            {
                _ex.exchange(null);
                _ex.exchange(null);
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }

            avail = in.available();

            if (avail == 0)
            {
                // handle blocking channel connectors
                buf += (char)in.read();
                avail = in.available();
                out.println(avail + 1);
            }
            else if (avail == 1)
            {
                // handle blocking socket connectors
                buf += (char)in.read();
                avail = in.available();
                out.println(avail + 1);
            }
            else
                out.println(avail);

            while (avail > 0)
            {
                buf += (char)in.read();
                avail = in.available();
            }

            out.println(avail);

            // read remaining no matter what
            int b = in.read();
            while (b >= 0)
            {
                buf += (char)b;
                b = in.read();
            }
            out.println(buf);
            out.close();
        }
    }

    @Test
    public void testAvailable() throws Exception
    {
        AvailableHandler ah = new AvailableHandler();
        configureServer(ah);
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setDelayDispatchUntilContent(false);

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();

            os.write((
                "GET /data?writes=1024&block=256 HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "connection: close\r\n" +
                    "content-type: unknown\r\n" +
                    "content-length: 30\r\n" +
                    "\r\n"
            ).getBytes());
            os.flush();
            Thread.sleep(500);
            os.write((
                "1234567890"
            ).getBytes());
            os.flush();

            ah._ex.exchange(null);

            os.write((
                "abcdefghijklmnopqrst"
            ).getBytes());
            os.flush();
            Thread.sleep(500);
            ah._ex.exchange(null);

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            // skip header
            while (reader.readLine().length() > 0)
            {
                ;
            }
            assertThat(Integer.parseInt(reader.readLine()), Matchers.equalTo(0));
            assertThat(Integer.parseInt(reader.readLine()), Matchers.equalTo(9));
            assertThat(Integer.parseInt(reader.readLine()), Matchers.equalTo(0));
            assertThat(Integer.parseInt(reader.readLine()), Matchers.greaterThan(0));
            assertThat(Integer.parseInt(reader.readLine()), Matchers.equalTo(0));
            assertEquals("1234567890abcdefghijklmnopqrst", reader.readLine());
        }
    }

    @Test
    public void testDualRequest1() throws Exception
    {
        configureServer(new HelloWorldHandler());

        try (Socket client1 = newSocket(_serverURI.getHost(), _serverURI.getPort());
             Socket client2 = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os1 = client1.getOutputStream();
            OutputStream os2 = client2.getOutputStream();

            os1.write(REQUEST1.getBytes());
            os2.write(REQUEST1.getBytes());
            os1.flush();
            os2.flush();

            // Read the response.
            String response1 = readResponse(client1);
            String response2 = readResponse(client2);

            // Check the response
            assertEquals(RESPONSE1, response1, "client1");
            assertEquals(RESPONSE1, response2, "client2");
        }
    }

    /**
     * Read entire response from the client. Close the output.
     *
     * @param client Open client socket.
     * @return The response string.
     * @throws IOException in case of I/O problems
     */
    protected static String readResponse(Socket client) throws IOException
    {

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream())))
        {
            String line;

            while ((line = br.readLine()) != null)
            {
                sb.append(line);
                sb.append('\n');
            }

            return sb.toString();
        }
        catch (IOException e)
        {
            System.err.println(e + " while reading '" + sb + "'");
            throw e;
        }
    }

    protected void writeFragments(byte[] bytes, int[] points, StringBuilder message, OutputStream os) throws IOException, InterruptedException
    {
        int last = 0;

        // Write out the fragments
        for (int j = 0; j < points.length; ++j)
        {
            int point = points[j];

            // System.err.println("write: "+new String(bytes, last, point - last));
            os.write(bytes, last, point - last);
            last = point;
            os.flush();
            Thread.sleep(PAUSE);

            // Update the log message
            message.append(" point #").append(j + 1).append(": ").append(point);
        }

        // Write the last fragment
        // System.err.println("Write: "+new String(bytes, last, bytes.length - last));
        os.write(bytes, last, bytes.length - last);
        os.flush();
        Thread.sleep(PAUSE);
    }

    @Test
    public void testUnreadInput() throws Exception
    {
        configureServer(new NoopHandler());
        final int REQS = 2;
        final String content = "This is a coooooooooooooooooooooooooooooooooo" +
            "ooooooooooooooooooooooooooooooooooooooooooooo" +
            "ooooooooooooooooooooooooooooooooooooooooooooo" +
            "ooooooooooooooooooooooooooooooooooooooooooooo" +
            "ooooooooooooooooooooooooooooooooooooooooooooo" +
            "ooooooooooooooooooooooooooooooooooooooooooooo" +
            "ooooooooooooooooooooooooooooooooooooooooooooo" +
            "ooooooooooooooooooooooooooooooooooooooooooooo" +
            "ooooooooooooooooooooooooooooooooooooooooooooo" +
            "oooooooooooonnnnnnnnnnnnnnnntent";
        final int cl = content.getBytes().length;

        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        final OutputStream out = client.getOutputStream();

        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    byte[] bytes = ("GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: " + cl + "\r\n" +
                        "\r\n" +
                        content).getBytes(StandardCharsets.ISO_8859_1);

                    for (int i = 0; i < REQS; i++)
                    {
                        out.write(bytes, 0, bytes.length);
                    }
                    out.write("GET /last HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }.start();

        String resps = readResponse(client);

        int offset = 0;
        for (int i = 0; i < (REQS + 1); i++)
        {
            int ok = resps.indexOf("HTTP/1.1 200 OK", offset);
            assertThat("resp" + i, ok, greaterThanOrEqualTo(offset));
            offset = ok + 15;
        }
    }

    @Test
    public void testWriteBodyAfterNoBodyResponse() throws Exception
    {
        configureServer(new WriteBodyAfterNoBodyResponseHandler());
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        final OutputStream out = client.getOutputStream();

        out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes());
        out.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes());
        out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));

        String line = in.readLine();
        assertThat(line, containsString(" 304 "));
        while (true)
        {
            line = in.readLine();
            if (line == null)
                throw new EOFException();
            if (line.length() == 0)
                break;

            assertThat(line, not(containsString("Content-Length")));
            assertThat(line, not(containsString("Content-Type")));
            assertThat(line, not(containsString("Transfer-Encoding")));
        }

        line = in.readLine();
        assertThat(line, containsString(" 304 "));
        while (true)
        {
            line = in.readLine();
            if (line == null)
                throw new EOFException();
            if (line.length() == 0)
                break;

            assertThat(line, not(containsString("Content-Length")));
            assertThat(line, not(containsString("Content-Type")));
            assertThat(line, not(containsString("Transfer-Encoding")));
        }

        do
        {
            line = in.readLine();
        }
        while (line != null);
    }

    private static class WriteBodyAfterNoBodyResponseHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(304);
            response.getOutputStream().print("yuck");
            response.flushBuffer();
        }
    }

    public class NoopHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest,
                           HttpServletRequest request, HttpServletResponse response) throws IOException,
            ServletException
        {
            //don't read the input, just send something back
            ((Request)request).setHandled(true);
            response.setStatus(200);
        }
    }

    @Test
    public void testSuspendedPipeline() throws Exception
    {
        SuspendHandler suspend = new SuspendHandler();
        suspend.setSuspendFor(30000);
        suspend.setResumeAfter(1000);
        configureServer(suspend);

        long start = System.currentTimeMillis();
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(5000);
        try
        {
            OutputStream os = client.getOutputStream();

            // write an initial request
            os.write((
                "GET / HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "\r\n"
            ).getBytes());
            os.flush();

            Thread.sleep(200);

            // write an pipelined request
            os.write((
                "GET / HTTP/1.1\r\n" +
                    "host: localhost:" + _serverURI.getPort() + "\r\n" +
                    "connection: close\r\n" +
                    "\r\n"
            ).getBytes());
            os.flush();

            String response = readResponse(client);
            assertThat(response, containsString("RESUMEDHTTP/1.1 200 OK"));
            assertThat((System.currentTimeMillis() - start), greaterThanOrEqualTo(1999L));

            // TODO This test should also check that that the CPU did not spin during the suspend.
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testShutdown() throws Exception
    {
        configureServer(new ReadExactHandler());
        byte[] content = new byte[4096];
        Arrays.fill(content, (byte)'X');

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            // Send two persistent pipelined requests and then shutdown output
            os.write(("GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: " + content.length + "\r\n" +
                "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            os.write(content);
            os.write(("GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: " + content.length + "\r\n" +
                "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            os.write(content);
            os.flush();
            // Thread.sleep(50);
            client.shutdownOutput();

            // Read the two pipelined responses
            HttpTester.Response response = HttpTester.parseResponse(client.getInputStream());
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), containsString("Read " + content.length));

            response = HttpTester.parseResponse(client.getInputStream());
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), containsString("Read " + content.length));

            // Read the close
            assertThat(client.getInputStream().read(), is(-1));
        }
    }

    @Test
    public void testChunkedShutdown() throws Exception
    {
        configureServer(new ReadExactHandler(4096));
        byte[] content = new byte[4096];
        Arrays.fill(content, (byte)'X');

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            // Send two persistent pipelined requests and then shutdown output
            os.write(("GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "1000\r\n").getBytes(StandardCharsets.ISO_8859_1));
            os.write(content);
            os.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.write(("GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "1000\r\n").getBytes(StandardCharsets.ISO_8859_1));
            os.write(content);
            os.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.flush();
            client.shutdownOutput();

            // Read the two pipelined responses
            HttpTester.Response response = HttpTester.parseResponse(client.getInputStream());
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), containsString("Read " + content.length));

            response = HttpTester.parseResponse(client.getInputStream());
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), containsString("Read " + content.length));

            // Read the close
            assertThat(client.getInputStream().read(), is(-1));
        }
    }

    @Test
    public void testSendAsyncContent() throws Exception
    {
        int size = 64 * 1024;
        configureServer(new SendAsyncContentHandler(size));

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            os.write(("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            HttpTester.Response response = HttpTester.parseResponse(client.getInputStream());
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContentBytes().length, is(size));

            // Try again to check previous request completed OK
            os.write(("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            os.flush();
            response = HttpTester.parseResponse(client.getInputStream());
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContentBytes().length, is(size));
        }
    }

    private class SendAsyncContentHandler extends AbstractHandler
    {
        final ByteBuffer content;

        public SendAsyncContentHandler(int size)
        {
            content = BufferUtil.allocate(size);
            Arrays.fill(content.array(), 0, size, (byte)'X');
            content.position(0);
            content.limit(size);
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.setContentType("application/unknown");
            response.setContentLength(content.remaining());
            AsyncContext async = request.startAsync();
            ((HttpOutput)response.getOutputStream()).sendContent(content.slice(), Callback.from(async::complete));
        }
    }
}
