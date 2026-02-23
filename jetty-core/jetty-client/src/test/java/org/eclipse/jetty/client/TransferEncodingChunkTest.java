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

package org.eclipse.jetty.client;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class TransferEncodingChunkTest
{
    private static final Logger LOG = LoggerFactory.getLogger(TransferEncodingChunkTest.class);

    private HttpClient client;

    private void startClient(int chunkMaxLength) throws Exception
    {
        HttpClientTransportOverHTTP transport = new HttpClientTransportOverHTTP();
        transport.setTransferEncodingChunkMaxLength(chunkMaxLength);
        client = new HttpClient(transport);
        client.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
    }

    @Test
    public void testChunkMaxLengthFlushThenContent() throws Exception
    {
        int chunkMaxLength = 1024;
        startClient(chunkMaxLength);

        try (ServerSocket server = new ServerSocket(0))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("server listening on {}", server.getLocalSocketAddress());

            byte[] content = new byte[2 * chunkMaxLength];
            ThreadLocalRandom.current().nextBytes(content);
            byte[] encodedContent = Base64.getEncoder().encode(content);

            CountDownLatch responseLatch = new CountDownLatch(1);
            AsyncRequestContent asyncContent = new AsyncRequestContent();
            client.newRequest("localhost", server.getLocalPort())
                .body(asyncContent)
                .timeout(5, TimeUnit.SECONDS)
                .send(r ->
                {
                    assertTrue(r.isSucceeded());
                    assertEquals(HttpStatus.OK_200, r.getResponse().getStatus());
                    responseLatch.countDown();
                });

            // Avoid that the headers and the chunk are sent in the same write.
            Thread.sleep(500);
            asyncContent.write(true, ByteBuffer.wrap(encodedContent), Callback.NOOP);

            Socket socket = server.accept();
            socket.setSoTimeout(500);
            InputStream input = socket.getInputStream();
            byte[] b = new byte[512];
            List<byte[]> reads = new ArrayList<>();
            while (true)
            {
                int read = input.read(b);
                if (read > 0)
                {
                    reads.add(Arrays.copyOfRange(b, 0, read));
                    if (read >= 5)
                    {
                        // Check if we are at the end of the request.
                        int i = read - 5;
                        if (b[i] == '0' && b[i + 1] == '\r' && b[i + 2] == '\n' && b[i + 3] == '\r' && b[i + 4] == '\n')
                            break;
                    }
                }
                else if (read < 0)
                {
                    throw new EOFException();
                }
            }
            byte[] requestBytes = new byte[reads.stream().mapToInt(ba -> ba.length).sum()];
            int offset = 0;
            for (byte[] read : reads)
            {
                System.arraycopy(read, 0, requestBytes, offset, read.length);
                offset += read.length;
            }
            String requestString = new String(requestBytes, StandardCharsets.UTF_8);

            // The request must contain chunks of chunkMaxLength.
            int count = encodedContent.length / chunkMaxLength;
            int index = -1;
            for (int i = 0; i < count; ++i)
            {
                index = requestString.indexOf("\r\n%x\r\n".formatted(chunkMaxLength), index + 1);
                assertThat(index, greaterThan(0));
            }
            // Check that there are no extra bytes after the request end.
            try
            {
                int read = input.read(b);
                fail("unexpected read of %d bytes ".formatted(read));
            }
            catch (SocketTimeoutException ignored)
            {
            }

            // Verify that the content is the same that was sent.
            HttpTester.Request request = HttpTester.parseRequest(requestString);
            byte[] decodedContent = Base64.getDecoder().decode(request.getContent());
            assertArrayEquals(content, decodedContent);

            OutputStream output = socket.getOutputStream();
            output.write("""
                HTTP/1.1 200 OK
                Content-Length: 0
                
                """.getBytes(StandardCharsets.UTF_8));
            output.flush();

            assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testChunkMaxLengthContentWithTrailers() throws Exception
    {
        int chunkMaxLength = 512;
        startClient(chunkMaxLength);

        try (ServerSocket server = new ServerSocket(0))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("server listening on {}", server.getLocalSocketAddress());

            byte[] content = new byte[2 * chunkMaxLength];
            ThreadLocalRandom.current().nextBytes(content);
            byte[] encodedContent = Base64.getEncoder().encode(content);

            // Setting the trailers implies using Transfer-Encoding: chunked.
            HttpFields trailers = HttpFields.build().put("Force-Chunking", "Z");
            CountDownLatch responseLatch = new CountDownLatch(1);
            client.newRequest("localhost", server.getLocalPort())
                .body(new BytesRequestContent(encodedContent))
                .trailersSupplier(() -> trailers)
                .timeout(5, TimeUnit.SECONDS)
                .send(r ->
                {
                    assertTrue(r.isSucceeded());
                    assertEquals(HttpStatus.OK_200, r.getResponse().getStatus());
                    responseLatch.countDown();
                });

            Socket socket = server.accept();
            socket.setSoTimeout(500);
            InputStream input = socket.getInputStream();
            byte[] b = new byte[512];
            List<byte[]> reads = new ArrayList<>();
            while (true)
            {
                int read = input.read(b);
                if (read > 0)
                {
                    reads.add(Arrays.copyOfRange(b, 0, read));
                    if (read >= 5)
                    {
                        // Check if we are at the end of the request.
                        int i = read - 5;
                        if (b[i] == 'Z' && b[i + 1] == '\r' && b[i + 2] == '\n' && b[i + 3] == '\r' && b[i + 4] == '\n')
                            break;
                    }
                }
                else if (read < 0)
                {
                    throw new EOFException();
                }
            }
            byte[] requestBytes = new byte[reads.stream().mapToInt(ba -> ba.length).sum()];
            int offset = 0;
            for (byte[] read : reads)
            {
                System.arraycopy(read, 0, requestBytes, offset, read.length);
                offset += read.length;
            }
            String requestString = new String(requestBytes, StandardCharsets.UTF_8);

            // The request must contain chunks of chunkMaxLength.
            int count = encodedContent.length / chunkMaxLength;
            int index = -1;
            for (int i = 0; i < count; ++i)
            {
                index = requestString.indexOf("\r\n%x\r\n".formatted(chunkMaxLength), index + 1);
                assertThat(index, greaterThan(0));
            }
            // Check that there are no extra bytes after the request end.
            try
            {
                int read = input.read(b);
                fail("unexpected read of %d bytes ".formatted(read));
            }
            catch (SocketTimeoutException ignored)
            {
            }

            // Verify that the content is the same that was sent.
            HttpTester.Request request = HttpTester.parseRequest(requestString);
            byte[] decodedContent = Base64.getDecoder().decode(request.getContent());
            assertArrayEquals(content, decodedContent);

            OutputStream output = socket.getOutputStream();
            output.write("""
                HTTP/1.1 200 OK
                Content-Length: 0
                
                """.getBytes(StandardCharsets.UTF_8));
            output.flush();

            assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        }
    }
}
