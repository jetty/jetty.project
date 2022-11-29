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

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ArrayRetainableByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.server.handler.EchoHandler;
import org.eclipse.jetty.server.handler.HelloHandler;
import org.eclipse.jetty.server.internal.HttpConnection;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class HttpServerTestBase extends HttpServerTestFixture
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpServerTestBase.class);

    private static final String REQUEST1_HEADER = """
        POST / HTTP/1.0
        Host: localhost
        Content-Type: text/xml; charset=utf-8
        Connection: close
        Content-Length:\s""";
    private static final String REQUEST1_CONTENT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <nimbus xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xsi:noNamespaceSchemaLocation="nimbus.xsd" version="1.0">
        </nimbus>""";
    private static final String REQUEST1 = REQUEST1_HEADER + REQUEST1_CONTENT.getBytes().length + "\n\n" + REQUEST1_CONTENT;

    private static final String RESPONSE1 = "HTTP/1.1 200 OK\n" +
        "Server: Jetty(" + Server.getVersion() + ")\n" +
        "Content-Type: text/plain;charset=utf-8\n" +
        "Content-Length: 5\n" +
        "\n" +
        "Hello\n";

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
            "</nimbus>\n";
    protected static final String REQUEST2 = REQUEST2_HEADER + REQUEST2_CONTENT.getBytes().length + "\n\n" + REQUEST2_CONTENT;

    protected static final String RESPONSE2 =
        "HTTP/1.1 200 OK\n" +
            "Server: Jetty(" + Server.getVersion() + ")\n" +
            "Content-Type: text/xml; charset=ISO-8859-1\n" +
            "Content-Length: " + REQUEST2_CONTENT.getBytes().length + "\n" +
            "\n" +
            REQUEST2_CONTENT;

    @Test
    public void testSimpleGET() throws Exception
    {
        startServer(new HelloHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = """
                GET / HTTP/1.1
                Host: localhost
                Connection: close
                
                """;
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, containsString("HTTP/1.1 200 OK"));
            assertThat(response, containsString("Hello"));
        }
    }

    @Test
    public void testSimplePOST() throws Exception
    {
        startServer(new EchoHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = """
                POST / HTTP/1.1
                Host: localhost
                Transfer-Encoding: chunked
                Connection: close
                
                0a
                0123456789
                0;
                
                """;
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, containsString("HTTP/1.1 200 OK"));
            assertThat(response, containsString("0123456789"));
        }
    }

    @Test
    public void testOPTIONS() throws Exception
    {
        startServer(new OptionsHandler());

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

            assertThat(response, containsString("HTTP/1.1 200 OK"));
            assertThat(response, containsString("Allow: GET"));
        }
    }

    @Test
    public void testGETStar() throws Exception
    {
        startServer(new OptionsHandler());
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

            assertThat(response, containsString("HTTP/1.1 400 "));
            assertThat(response, Matchers.not(containsString("Allow: ")));
        }
    }

    /*
     * Feed a full header method
     */
    @Test
    public void testFullMethod() throws Exception
    {
        // TODO this test is flakey
        // The failure appears associated with the 431 BadMessageException being thrown

        startServer(new HelloHandler());

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
            catch (Throwable t)
            {
                if (LOG.isDebugEnabled())
                    t.printStackTrace();
            }

            // Read the response.
            String response = readResponse(client);

            assertThat(response, containsString("HTTP/1.1 431 "));
        }
    }

    /*
     * Feed a full header method
     */
    @Test
    public void testFullURI() throws Exception
    {
        startServer(new HelloHandler());

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

            assertThat(response, containsString("HTTP/1.1 414 "));
        }
    }

    @Test
    public void testBadURI() throws Exception
    {
        startServer(new HelloHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            os.write("GET /%xx HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, containsString("HTTP/1.1 400 "));
        }
    }

    @Test
    public void testBadChunk() throws Exception
    {
        startServer(new EchoHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = """
                POST / HTTP/1.1
                Host: localhost
                Transfer-Encoding: chunked
                
                xx;
                
                """;
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, containsString("HTTP/1.1 400 Bad Request"));
            assertThat(response, containsString("Error 400 Early EOF"));
        }
    }

    @Test
    public void testBadChunkCommitted() throws Exception
    {
        startServer(new EchoHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = """
                POST / HTTP/1.1
                Host: localhost
                Transfer-Encoding: chunked
                
                0a;
                1234567890
                xx;
                
                
                """;
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, containsString("HTTP/1.1 200 OK"));
            assertThat(response, containsString("A"));
            assertThat(response, containsString("1234567890"));
            assertThat(response, not(endsWith("\r\n0\r\n\r\n")));
            assertThat(response, not(endsWith("\r\n0;\r\n\r\n")));
            assertThat(response, not(endsWith("\n0\n\n")));
            assertThat(response, not(endsWith("\n0;\n\n")));
        }
    }

    @Test
    public void testExceptionThrownInHandlerLoop() throws Exception
    {
        startServer(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                throw new Exception("TEST handler exception");
            }
        });

        StringBuilder request = new StringBuilder("GET / HTTP/1.0\r\n");
        request.append("Host: localhost\r\n\r\n");

        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        OutputStream os = client.getOutputStream();

        try (StacklessLogging ignored = new StacklessLogging(ContextRequest.class))
        {
            LOG.info("Expecting Exception: TEST handler exception...");
            os.write(request.toString().getBytes());
            os.flush();

            String response = readResponse(client);
            assertThat(response, containsString(" 500 "));
        }
    }

    @Test
    public void testExceptionThrownInHandler() throws Exception
    {
        startServer(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                throw new Exception("TEST handler exception");
            }
        });

        StringBuilder request = new StringBuilder("GET / HTTP/1.0\r\n");
        request.append("Host: localhost\r\n\r\n");

        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        OutputStream os = client.getOutputStream();

        try (StacklessLogging ignored = new StacklessLogging(ContextRequest.class))
        {
            LOG.info("Expecting Exception: TEST handler exception...");
            os.write(request.toString().getBytes());
            os.flush();

            String response = readResponse(client);
            assertThat(response, containsString(" 500 "));
        }
    }

    @Test
    public void testInterruptedRequest() throws Exception
    {
        final AtomicBoolean fourBytesRead = new AtomicBoolean(false);
        final CountDownLatch earlyEOFException = new CountDownLatch(1);
        startServer(new Handler.Processor.Blocking()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback) throws Exception
            {
                long contentLength = request.getLength();
                long read = 0;
                while (read < contentLength)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        try (Blocker.Runnable blocker = Blocker.runnable())
                        {
                            request.demand(blocker);
                            blocker.block();
                        }
                        continue;
                    }

                    if (chunk instanceof Content.Chunk.Error error)
                    {
                        earlyEOFException.countDown();
                        throw IO.rethrow(error.getCause());
                    }

                    if (chunk.hasRemaining())
                    {
                        read += chunk.remaining();
                        chunk.getByteBuffer().clear();
                        chunk.release();
                        if (!fourBytesRead.get() && read >= 4)
                            fourBytesRead.set(true);
                    }

                    if (chunk.isLast())
                    {
                        callback.succeeded();
                        break;
                    }
                }
            }
        });

        String request = "GET / HTTP/1.0\n" + "Host: localhost\n" +
            "Content-length: 6\n\n" +
            "foo";

        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        OutputStream os = client.getOutputStream();

        os.write(request.getBytes());
        os.flush();
        client.shutdownOutput();
        String response = readResponse(client);
        client.close();

        assertThat(response, containsString(" 400 "));
        assertThat(response, containsString("<th>MESSAGE:</th><td>Early EOF</td>"));
        assertThat("The 4th byte (-1) has not been passed to the handler", fourBytesRead.get(), is(false));
        assertTrue(earlyEOFException.await(10, TimeUnit.SECONDS));
    }

    /*
     * Feed a full header method
     */
    @Test
    public void testFullHeader() throws Exception
    {
        startServer(new HelloHandler());

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
                assertThat(response, containsString("HTTP/1.1 431 "));
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
        startServer(new HelloHandler());

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
        startServer(new TestHandler());

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
            Thread.sleep(100);
            os.write(("5").getBytes());
            Thread.sleep(100);
            os.write(("\r\n").getBytes());
            os.flush();
            Thread.sleep(100);
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
        startServer(new TestHandler());

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
        startServer(new HelloHandler());

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
        startServer(new TestHandler());

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
    @Tag("slow")
    public void testRequest2Sliced2() throws Exception
    {
        startServer(new TestHandler());

        byte[] bytes = REQUEST2.getBytes();
        int splits = bytes.length - REQUEST2_CONTENT.length() + 5;
        for (int i = 0; i < splits; i += 1)
        {
            int[] points = new int[]{i};
            StringBuilder message = new StringBuilder();

            message.append("iteration #").append(i).append("/").append(splits - 1);

            try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
            {
                OutputStream os = client.getOutputStream();

                writeFragments(bytes, points, message, os);

                // Read the response
                String response = readResponse(client);

                // Check the response
                assertEquals(RESPONSE2, response, "response for " + i + " " + message);

                Thread.sleep(2);
            }
        }
    }

    @Test
    @Tag("slow")
    public void testRequest2Sliced3() throws Exception
    {
        startServer(new TestHandler());

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
                assertEquals(RESPONSE2, response, "response for " + i + " " + message);

                Thread.sleep(2);
            }
        }
    }

    @Test // TODO: Parameterize
    public void testFlush() throws Exception
    {
        // TODO this test takes to long
        startServer(new DataHandler());

        String[] encoding = {"NONE", "UTF-8", "ISO-8859-1", "ISO-8859-2"};
        for (int e = 0; e < encoding.length; e++)
        {
            for (int b = 1; b <= 128; b = b == 1 ? 2 : b == 2 ? 32 : b == 32 ? 128 : 129)
            {
                for (int w = 41; w < 42; w += 4096)
                {
                    String test = encoding[e] + "x" + b + "x" + w;
                    try
                    {
                        URL url = new URL(_scheme + "://localhost:" + _serverURI.getPort() + "/?writes=" + w + "&block=" + b + (e == 0 ? "" : ("&encoding=" + encoding[e])));

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

    @Test
    public void testBlockingWhileReadingRequestContent() throws Exception
    {
        startServer(new DataHandler());

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
            Thread.sleep(100);
            os.write((
                "\r\n23456890"
            ).getBytes());
            os.flush();
            Thread.sleep(100);
            os.write((
                "abcdefghij"
            ).getBytes());
            os.flush();
            Thread.sleep(100);
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
                        Thread.sleep(10);
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
        startServer(new ReadHandler());

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
            Thread.sleep(10);
            os.write((
                "a\r\n" +
                    "123456890\r\n"
            ).getBytes());
            os.flush();

            Thread.sleep(10);
            os.write((
                "4\r\n" +
                    "abcd\r\n"
            ).getBytes());
            os.flush();

            Thread.sleep(10);
            os.write((
                "X\r\n" +
                    "abcd\r\n"
            ).getBytes());
            os.flush();

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(is));
            assertNotNull(response);
            assertThat(response.getStatus(), is(400));
            assertThat(response.getContent(), containsString("Early EOF"));
        }
    }

    @Test
    public void testBlockingWhileWritingResponseContent() throws Exception
    {
        startServer(new DataHandler());

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
        startServer(new DataHandler());

        try (StacklessLogging ignored = new StacklessLogging(Server.class))
        {
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
                Thread.sleep(2000);
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
    }

    @Test
    public void testBigBlocks() throws Exception
    {
        startServer(new BigBlockHandler());

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
            // TODO currently we are not fragmenting in the core HttpConnection
            // assertEquals(_connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().getOutputBufferSize(), max);

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
    protected static class BigBlockHandler extends Handler.Processor.Blocking
    {
        byte[] buf = new byte[128 * 1024];

        private BigBlockHandler()
        {
            for (int i = 0; i < buf.length; i++)
            {
                buf[i] = (byte)("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_".charAt(i % 63));
            }
        }

        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");

            long[] times = new long[10];
            for (int i = 0; i < times.length; i++)
            {
                long start = System.currentTimeMillis();
                try (Blocker.Callback blocker = Blocker.callback())
                {
                    response.write(false, BufferUtil.toBuffer(buf), blocker);
                    blocker.block();
                }
                long end = System.currentTimeMillis();
                times[i] = end - start;
            }
            StringBuilder out = new StringBuilder();
            out.append("\n");
            for (long t : times)
            {
                out.append(t).append(",");
            }

            response.write(true, BufferUtil.toBuffer(out.toString()), callback);
        }
    }

    @Test
    public void testPipeline() throws Exception
    {
        AtomicInteger served = new AtomicInteger();
        startServer(new HelloHandler("Hello\n")
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                served.incrementAndGet();
                super.process(request, response, callback);
            }
        });

        int pipeline = 64;
        {
            try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
            {
                served.set(0);
                client.setSoTimeout(5000);
                OutputStream os = client.getOutputStream();

                StringBuilder request = new StringBuilder();

                for (int i = 1; i < pipeline; i++)
                {
                    request.append("GET /data?writes=1&block=16&id=")
                        .append(i).append(" HTTP/1.1\r\n")
                        .append("host: localhost:").append(_serverURI.getPort()).append("\r\n")
                        .append("user-agent: testharness/1.0 (blah foo/bar)\r\n")
                        .append("accept-encoding: nothing\r\n")
                        .append("cookie: aaa=1234567890\r\n")
                        .append("\r\n");
                }

                request
                    .append("GET /data?writes=1&block=16 HTTP/1.1\r\n")
                    .append("host: localhost:").append(_serverURI.getPort()).append("\r\n")
                    .append("user-agent: testharness/1.0 (blah foo/bar)\r\n")
                    .append("accept-encoding: nothing\r\n")
                    .append("cookie: aaa=bbbbbb\r\n")
                    .append("Connection: close\r\n")
                    .append("\r\n");

                os.write(request.toString().getBytes());
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
    public void testHead() throws Exception
    {
        startServer(new TestHandler(false));

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
    public void testBlockedClient() throws Exception
    {
        startServer(new HelloHandler());

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
            // HelloHandler does not read content, so 100 is not sent.
            // So close will have to happen anyway, without reset!

            os.flush();

            client.setSoTimeout(2000);
            long start = System.currentTimeMillis();
            String in = IO.toString(is);
            assertTrue(System.currentTimeMillis() - start < 1000);
            assertTrue(in.indexOf("Connection: close") > 0);
            assertTrue(in.indexOf("Hello") > 0);
        }
    }

    @Test
    public void testCommittedError() throws Exception
    {
        CommittedErrorHandler handler = new CommittedErrorHandler();
        startServer(handler);

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
             StacklessLogging ignored = new StacklessLogging(Server.class))
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
            assertThat(in, Matchers.not(containsString("\r\n0\r\n")));

            client.close();
            Thread.sleep(200);

            assertFalse(handler._endp.isOpen());
        }
    }

    public static class CommittedErrorHandler extends Handler.Processor.Blocking
    {
        public EndPoint _endp;

        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            _endp = request.getConnectionMetaData().getConnection().getEndPoint();
            response.getHeaders().put("test", "value");
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
            try (Blocker.Callback blocker = Blocker.callback())
            {
                response.write(false, BufferUtil.toBuffer("Now is the time for all good men to come to the aid of the party"), blocker);
                blocker.block();
            }

            throw new Exception(new Exception("exception after commit"));
        }
    }

    @Test
    public void testDualRequest1() throws Exception
    {
        startServer(new HelloHandler());

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

    protected static String readResponseHeader(Socket client) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream())))
        {
            String line;

            while ((line = br.readLine()) != null)
            {
                sb.append(line);
                sb.append('\n');

                if (StringUtil.isEmpty(line))
                    break;
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
        startServer(new NoopHandler());
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

        new Thread(() ->
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
        }).start();

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
        startServer(new WriteBodyAfterNoBodyResponseHandler());
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

    private static class WriteBodyAfterNoBodyResponseHandler extends Handler.Processor
    {
        @Override
        public void doProcess(Request request, Response response, Callback callback)
        {
            response.setStatus(304);
            response.write(false, BufferUtil.toBuffer("yuck"), callback);
        }
    }

    public static class NoopHandler extends Handler.Processor
    {
        @Override
        public void doProcess(Request request, Response response, Callback callback)
        {
            //don't read the input, just send something back
            response.setStatus(200);
            callback.succeeded();
        }
    }

    @Test
    public void testShutdown() throws Exception
    {
        startServer(new ReadExactHandler());
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
        startServer(new ReadExactHandler(4096));
        byte[] content = new byte[4096];
        Arrays.fill(content, (byte)'X');

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            // Send two persistent pipelined requests and then shutdown output
            os.write(("""
                GET / HTTP/1.1\r
                Host: localhost\r
                Transfer-Encoding: chunked\r
                \r
                1000\r
                """).getBytes(StandardCharsets.ISO_8859_1));
            os.write(content);
            os.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.write(("""
                GET / HTTP/1.1\r
                Host: localhost\r
                Transfer-Encoding: chunked\r
                \r
                1000\r
                """).getBytes(StandardCharsets.ISO_8859_1));
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
    public void testHoldContent() throws Exception
    {
        Queue<Content.Chunk> contents = new ConcurrentLinkedQueue<>();
        final int bufferSize = 1024;
        _connector.getConnectionFactory(HttpConnectionFactory.class).setInputBufferSize(bufferSize);
        CountDownLatch closed = new CountDownLatch(1);
        startServer(new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback) throws Exception
            {
                request.getConnectionMetaData().getConnection().addEventListener(new Connection.Listener()
                {
                    @Override
                    public void onClosed(Connection connection)
                    {
                        closed.countDown();
                    }
                });
                while (true)
                {
                    Content.Chunk chunk = request.read();

                    if (chunk == null)
                    {
                        try (Blocker.Runnable blocker = Blocker.runnable())
                        {
                            request.demand(blocker);
                            blocker.block();
                            continue;
                        }
                    }

                    if (chunk.hasRemaining())
                        contents.add(chunk);

                    if (chunk.isLast())
                        break;
                }

                response.setStatus(200);
                callback.succeeded();
            }
        });

        byte[] chunk = new byte[bufferSize / 2];
        Arrays.fill(chunk, (byte)'X');

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();
            BufferedOutputStream out = new BufferedOutputStream(os, bufferSize);
            out.write(("""
                POST / HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                Transfer-Encoding: chunked\r
                \r
                """).getBytes(StandardCharsets.ISO_8859_1));

            // single chunk
            out.write((Integer.toHexString(chunk.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(chunk);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            // double chunk (will overflow)
            out.write((Integer.toHexString(chunk.length * 2) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(chunk);
            out.write(chunk);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            // single chunk and end chunk
            out.write((Integer.toHexString(chunk.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(chunk);
            out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            // check the response
            HttpTester.Response response = HttpTester.parseResponse(client.getInputStream());
            assertNotNull(response);
            assertThat(response.getStatus(), is(200));
        }

        assertTrue(closed.await(10, TimeUnit.SECONDS));

        long total = contents.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(total, equalTo(chunk.length * 4L));

        RetainableByteBufferPool rbbp = _connector.getBean(RetainableByteBufferPool.class);
        if (rbbp instanceof ArrayRetainableByteBufferPool pool)
        {
            long buffersBeforeRelease = pool.getAvailableDirectByteBufferCount() + pool.getAvailableHeapByteBufferCount();
            contents.forEach(Content.Chunk::release);
            long buffersAfterRelease = pool.getAvailableDirectByteBufferCount() + pool.getAvailableHeapByteBufferCount();
            assertThat(buffersAfterRelease, greaterThan(buffersBeforeRelease));
            assertThat(pool.getAvailableDirectMemory() + pool.getAvailableHeapMemory(), greaterThanOrEqualTo(chunk.length * 4L));
        }
        else
        {
            assertThat(rbbp, instanceOf(ArrayRetainableByteBufferPool.class));
        }
    }

    public static class TestHandler extends EchoHandler
    {
        boolean _mustHaveContent;

        public TestHandler()
        {
            this(true);
        }

        public TestHandler(boolean content)
        {
            _mustHaveContent = content;
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            AtomicBoolean hasContent = new AtomicBoolean();
            Request.Wrapper wrapper = new Request.Wrapper(request)
            {
                @Override
                public Content.Chunk read()
                {
                    Content.Chunk c = super.read();
                    if (c != null && c.hasRemaining())
                        hasContent.set(true);
                    return c;
                }
            };

            super.process(wrapper, response, Callback.from(() ->
            {
                if (_mustHaveContent && !hasContent.get())
                    callback.failed(new IllegalStateException("No Test Content"));
                else
                    callback.succeeded();
            }, callback::failed));
        }
    }

    @Test
    public void testProcessingAfterCompletion() throws Exception
    {
        AtomicReference<String> result = new AtomicReference<>();
        Handler.Wrapper wrapper = new Handler.Wrapper()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                request.setAttribute("test", "value");
                super.process(request, response, callback);
                result.set(String.valueOf(request.getAttribute("test")));
            }
        };

        wrapper.setHandler(new HelloHandler());
        startServer(wrapper);

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = """
                GET / HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                \r
                """;
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, containsString("HTTP/1.1 200 OK"));
            assertThat(response, containsString("Hello"));

            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(result::get, equalTo("value"));
        }
    }
}
