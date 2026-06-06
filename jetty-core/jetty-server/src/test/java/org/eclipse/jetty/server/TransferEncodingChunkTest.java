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

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class TransferEncodingChunkTest
{
    private Server server;
    private ServerConnector connector;

    private void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testChunkMaxLengthFlushThenContent() throws Exception
    {
        int chunkMaxLength = 1024;
        byte[] content = new byte[2 * chunkMaxLength];
        ThreadLocalRandom.current().nextBytes(content);
        byte[] encodedContent = Base64.getEncoder().encode(content);
        startServer((Handler)new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Send headers with Transfer-Encoding: chunked.
                try (Blocker.Callback blocker = Blocker.callback())
                {
                    response.write(false, blocker);
                    blocker.block();
                }

                // Send a content that is larger than the chunk max length.
                response.write(true, ByteBuffer.wrap(encodedContent), callback);
                return true;
            }
        });

        // Limit the chunk length.
        connector.getConnectionFactory(HttpConnectionFactory.class)
            .setTransferEncodingChunkMaxLength(chunkMaxLength);

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            String request = """
                GET / HTTP/1.1
                Host: localhost
                
                """;
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            client.setSoTimeout(500);

            // We really want to check the structure of
            // the response so we don't use HttpTester.

            InputStream input = client.getInputStream();
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
                        // Check if we are at the end of the response.
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
            byte[] responseBytes = new byte[reads.stream().mapToInt(ba -> ba.length).sum()];
            int offset = 0;
            for (byte[] read : reads)
            {
                System.arraycopy(read, 0, responseBytes, offset, read.length);
                offset += read.length;
            }
            String responseString = new String(responseBytes, StandardCharsets.UTF_8);

            // The response must contain chunks of chunkMaxLength.
            int count = encodedContent.length / chunkMaxLength;
            int index = -1;
            for (int i = 0; i < count; ++i)
            {
                index = responseString.indexOf("\r\n%x\r\n".formatted(chunkMaxLength), index + 1);
                assertThat(index, greaterThan(0));
            }
            // Check that there are no extra bytes after the response end.
            try
            {
                int read = input.read(b);
                fail("unexpected read of %d bytes ".formatted(read));
            }
            catch (SocketTimeoutException ignored)
            {
            }

            // Verify that the content is the same that was sent.
            HttpTester.Response response = HttpTester.parseResponse(responseString);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            byte[] decodedContent = Base64.getDecoder().decode(response.getContent());
            assertArrayEquals(content, decodedContent);
        }
    }

    @Test
    public void testChunkMaxLengthContentWithTrailers() throws Exception
    {
        int chunkMaxLength = 512;
        byte[] content = new byte[2 * chunkMaxLength];
        ThreadLocalRandom.current().nextBytes(content);
        byte[] encodedContent = Base64.getEncoder().encode(content);
        startServer((Handler)new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Setting the trailers implies using Transfer-Encoding: chunked.
                HttpFields trailers = HttpFields.build().put("Force-Chunking", "Z");
                response.setTrailersSupplier(() -> trailers);
                response.write(true, ByteBuffer.wrap(encodedContent), callback);
                return true;
            }
        });

        // Limit the chunk length.
        connector.getConnectionFactory(HttpConnectionFactory.class)
            .setTransferEncodingChunkMaxLength(chunkMaxLength);

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            String request = """
                GET / HTTP/1.1
                Host: localhost
                
                """;
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            client.setSoTimeout(500);

            // We really want to check the structure of
            // the response so we don't use HttpTester.

            InputStream input = client.getInputStream();
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
                        // Check if we are at the end of the response.
                        // Use the trailer value to find the response end.
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
            byte[] responseBytes = new byte[reads.stream().mapToInt(ba -> ba.length).sum()];
            int offset = 0;
            for (byte[] read : reads)
            {
                System.arraycopy(read, 0, responseBytes, offset, read.length);
                offset += read.length;
            }
            String responseString = new String(responseBytes, StandardCharsets.UTF_8);

            // The response must contain chunks of chunkMaxLength.
            int count = encodedContent.length / chunkMaxLength;
            int index = -1;
            for (int i = 0; i < count; ++i)
            {
                index = responseString.indexOf("\r\n%x\r\n".formatted(chunkMaxLength), index + 1);
                assertThat(index, greaterThan(0));
            }
            // Check that there are no extra bytes after the response end.
            try
            {
                int read = input.read(b);
                fail("unexpected read of %d bytes ".formatted(read));
            }
            catch (SocketTimeoutException ignored)
            {
            }

            // Verify that the content is the same that was sent.
            HttpTester.Response response = HttpTester.parseResponse(responseString);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            byte[] decodedContent = Base64.getDecoder().decode(response.getContent());
            assertArrayEquals(content, decodedContent);
        }
    }
}
