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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LargeHeaderTest
{
    private static final Logger LOG = LoggerFactory.getLogger(LargeHeaderTest.class);
    private static final String EXPECTED_ERROR_TEXT = "<h2>HTTP ERROR 500 Response Header Fields Too Large</h2>";
    private Server server;

    @BeforeEach
    public void setup() throws Exception
    {
        server = new Server();

        HttpConfiguration config = new HttpConfiguration();
        config.setMaxResponseHeaderSize(-1);
        HttpConnectionFactory http = new HttpConnectionFactory(config);

        ServerConnector connector = new ServerConnector(server, http);
        connector.setPort(0);
        connector.setIdleTimeout(15000);
        server.addConnector(connector);

        server.setHandler(new Handler.Abstract()
        {
            final String largeHeaderValue;

            {
                byte[] bytes = new byte[8 * 1024];
                Arrays.fill(bytes, (byte)'X');
                largeHeaderValue = "LargeHeaderOver8k-" + new String(bytes, UTF_8) + "_Z_";
            }

            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String idCount = request.getHeaders().get("X-Count");
                LOG.debug("X-Count: {} [handle]", idCount);

                response.getHeaders().put(HttpHeader.CONTENT_TYPE.toString(), MimeTypes.Type.TEXT_HTML.toString());
                response.getHeaders().put("LongStr", largeHeaderValue);

                String responseBody = "<html><h1>FOO</h1></html>";

                Callback topCallback = new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        LOG.debug("X-Count: {} [callback.succeeded]", idCount);
                        callback.succeeded();
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        LOG.debug("X-Count: {} [callback.failed] {}", idCount, this);
                        callback.failed(x);
                    }
                };
                response.write(true, BufferUtil.toBuffer(responseBody, UTF_8), topCallback);
                LOG.debug("X-Count: {} [handle-completed]", idCount);
                return true;
            }
        });

        server.start();
    }

    @AfterEach
    public void teardown()
    {
        LifeCycle.stop(server);
    }

    private static String readResponse(Socket socket, int xCount, InputStream input) throws IOException
    {
        ByteArrayOutputStream readBytes = new ByteArrayOutputStream();
        int bufferSize = 65535;
        byte[] buffer = new byte[bufferSize];
        int lenRead;
        int lenTotal = 0;

        LOG.debug("X-Count: {} - Reading Response from {}->{}", xCount, socket.getLocalSocketAddress(), socket.getRemoteSocketAddress());

        while (true)
        {
            lenRead = input.read(buffer, 0, bufferSize);
            if (lenRead < 0)
                break;
            readBytes.write(buffer, 0, lenRead);
            lenTotal += lenRead;
        }

        LOG.debug("X-Count: {} - Read {} bytes of Response from {}->{}", xCount, lenTotal, socket.getLocalAddress(), socket.getRemoteSocketAddress());
        return readBytes.toString(UTF_8);
    }

    @Test
    public void testLargeHeader() throws Throwable
    {
        URI serverURI = server.getURI();
        String rawRequest = "GET / HTTP/1.1\r\n" +
            "Host: " + serverURI.getAuthority() + "\r\n" +
            "\r\n";

        try (Socket client = new Socket(serverURI.getHost(), serverURI.getPort());
             OutputStream output = client.getOutputStream();
             InputStream input = client.getInputStream())
        {
            output.write(rawRequest.getBytes(UTF_8));
            output.flush();

            String rawResponse = readResponse(client, 1, input);
            assertThat(rawResponse, containsString(" 500 "));
        }
    }

    @Test
    public void testLargeHeaderNewConnectionsConcurrent() throws Throwable
    {
        ExecutorService executorService = Executors.newCachedThreadPool();

        URI serverURI = server.getURI();
        String rawRequest = "GET / HTTP/1.1\r\n" +
            "Host: " + serverURI.getAuthority() + "\r\n" +
            "Connection: close\r\n" +
            "X-Count: %d\r\n" + // just so I can track them in wireshark
            "\r\n";

        final int iterations = 500;
        final int timeout = 16;
        Throwable issues = new Throwable();
        AtomicInteger counter = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        AtomicInteger count500 = new AtomicInteger(0);
        AtomicInteger countOther = new AtomicInteger(0);
        AtomicInteger countFailure = new AtomicInteger(0);
        AtomicInteger countEmpty = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++)
        {
            tasks.add(() ->
            {
                int count = counter.incrementAndGet();

                try (Socket client = new Socket(serverURI.getHost(), serverURI.getPort());
                     OutputStream output = client.getOutputStream();
                     InputStream input = client.getInputStream())
                {
                    LOG.debug("X-Count: {} - Send Request - {}->{}", count, client.getLocalAddress(), client.getRemoteSocketAddress());

                    output.write(rawRequest.formatted(count).getBytes(UTF_8));
                    output.flush();

                    // String rawResponse = readResponse(client, count, input);
                    String rawResponse = IO.toString(input, UTF_8);
                    if (rawResponse.isEmpty())
                    {
                        LOG.warn("X-Count: {} - Empty Raw Response", count);
                        countEmpty.incrementAndGet();
                        return null;
                    }

                    HttpTester.Response response = HttpTester.parseResponse(rawResponse);
                    if (response == null)
                    {
                        LOG.warn("X-Count: {} - Null HttpTester.Response", count);
                        countEmpty.incrementAndGet();
                    }
                    else if (response.getStatus() == 500)
                    {
                        // expected result
                        count500.incrementAndGet();
                        long contentLength = response.getLongField(HttpHeader.CONTENT_LENGTH);
                        String responseBody = response.getContent();
                        assertThat((long)responseBody.length(), is(contentLength));
                    }
                    else
                    {
                        LOG.warn("X-Count: {} - Unexpected Status Code: {}", count, response.getStatus());
                        countOther.incrementAndGet();
                    }
                }
                catch (Throwable t)
                {
                    issues.addSuppressed(t);
                    countFailure.incrementAndGet();
                }
                return null;
            });
        }

        List<Future<Void>> futures = executorService.invokeAll(tasks, timeout, TimeUnit.SECONDS);

        if (issues.getSuppressed().length > 0)
        {
            throw issues;
        }

        for (Future<Void> future : futures)
            future.get(2, TimeUnit.SECONDS);

        executorService.shutdown();
        assertTrue(executorService.awaitTermination(timeout * 2, TimeUnit.SECONDS));
        assertEquals(iterations, count500.get(), () ->
        {
            return """
                All tasks did not fail as expected.
                Iterations: %d
                Count (500 response status) [expected]: %d
                Count (empty response): %d
                Count (throwables): %d
                Count (other status codes): %d
                """.formatted(iterations, count500.get(), countEmpty.get(), countFailure.get(), countOther.get());
        });
    }

    @Test
    public void testLargeHeaderNewConnectionsSequential() throws Throwable
    {
        URI serverURI = server.getURI();
        String rawRequest = "GET / HTTP/1.1\r\n" +
            "Host: " + serverURI.getAuthority() + "\r\n" +
            "X-Count: %d\r\n" + // just so I can track them in wireshark
            "\r\n";

        final int iterations = 500;
        AtomicInteger count500 = new AtomicInteger(0);
        AtomicInteger countEmpty = new AtomicInteger(0);
        AtomicInteger countOther = new AtomicInteger(0);
        AtomicInteger countFailure = new AtomicInteger(0);

        for (int count = 0; count < iterations; count++)
        {
            try (Socket client = new Socket(serverURI.getHost(), serverURI.getPort());
                 OutputStream output = client.getOutputStream();
                 InputStream input = client.getInputStream())
            {
                output.write(rawRequest.formatted(count).getBytes(UTF_8));
                output.flush();

                long start = NanoTime.now();
                String rawResponse = readResponse(client, count, input);
                if (NanoTime.secondsSince(start) >= 1)
                    LOG.warn("X-Count: {} - Slow Response", count);

                if (rawResponse.isEmpty())
                {
                    LOG.warn("X-Count: {} - Empty Raw Response", count);
                    countEmpty.incrementAndGet();
                    break;
                }
                HttpTester.Response response = HttpTester.parseResponse(rawResponse);
                int status = response.getStatus();
                if (status == 500)
                {
                    long contentLength = response.getLongField(HttpHeader.CONTENT_LENGTH);
                    String responseBody = response.getContent();
                    assertThat((long)responseBody.length(), is(contentLength));
                    assertThat(responseBody, containsString(EXPECTED_ERROR_TEXT));
                    count500.incrementAndGet();
                }
                else
                {
                    LOG.warn("X-Count: {} - Unexpected Status Code: {}>", count, status);
                    countOther.incrementAndGet();
                    break;
                }
            }
            catch (Throwable t)
            {
                LOG.warn("X-Count: {} - ERROR", count, t);
                countFailure.incrementAndGet();
                break;
            }
        }

        assertEquals(iterations, count500.get(), () ->
            """
                All tasks did not fail as expected.
                Iterations: %d
                Count (500 response status) [expected]: %d
                Count (empty responses): %d
                Count (throwables): %d
                Count (other status codes): %d
                """.formatted(iterations, count500.get(), countEmpty.get(), countFailure.get(), countOther.get()));
    }
}
