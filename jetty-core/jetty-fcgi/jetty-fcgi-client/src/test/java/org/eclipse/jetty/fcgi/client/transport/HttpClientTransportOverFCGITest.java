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

package org.eclipse.jetty.fcgi.client.transport;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.client.transport.internal.HttpConnectionOverFCGI;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTransportOverFCGITest
{
    private final AtomicInteger fillablesEntered = new AtomicInteger();
    private final AtomicInteger fillablesExited = new AtomicInteger();
    private ArrayByteBufferPool.Tracking clientBufferPool;
    private ServerSocket server;
    private HttpClient client;

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new ServerSocket(0);
        ClientConnector clientConnector = new ClientConnector();
        clientBufferPool = new ArrayByteBufferPool.Tracking();
        clientConnector.setByteBufferPool(clientBufferPool);
        client = new HttpClient(new HttpClientTransportOverFCGI(clientConnector, "/")
        {
            @Override
            protected org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Destination destination, Promise<Connection> promise)
            {
                return new HttpConnectionOverFCGI(endPoint, destination, promise)
                {
                    @Override
                    public void onFillable()
                    {
                        // Track the reads from the network.
                        fillablesEntered.incrementAndGet();
                        try
                        {
                            super.onFillable();
                        }
                        finally
                        {
                            fillablesExited.incrementAndGet();
                        }
                    }
                };
            }
        });
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        try
        {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Client Leaks: " + clientBufferPool.dumpLeaks(), clientBufferPool.getLeaks().size(), is(0)));
        }
        finally
        {
            LifeCycle.stop(client);
            server.close();
        }
    }

    @Test
    public void testEndRequestInSeparateNetworkRead() throws Exception
    {
        String content = "hello";
        CountDownLatch contentLatch = new CountDownLatch(1);
        CompletableFuture<Void> serverFuture = CompletableFuture.runAsync(() ->
        {
            try (Socket socket = server.accept())
            {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                OutputStream output = socket.getOutputStream();

                int request = readRequest(input);

                // Write the STDOUT record, so that it is the only
                // record in the client's network buffer.
                byte[] stdout = ("Content-Type: text/plain\r\n\r\n" + content).getBytes(StandardCharsets.US_ASCII);
                output.write(createRecord(FCGI.FrameType.STDOUT, request, stdout));
                output.flush();

                // Wait for the client to have consumed the STDOUT record.
                assertTrue(contentLatch.await(5, TimeUnit.SECONDS));

                // Write the END_REQUEST record (appStatus=0, protocolStatus=REQUEST_COMPLETE).
                output.write(createRecord(FCGI.FrameType.END_REQUEST, request, new byte[8]));
                output.flush();

                // Wait for the client to close the connection.
                while (input.read() >= 0)
                {
                    // Discard.
                }
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        });

        ContentResponse response = client.newRequest("localhost", server.getLocalPort())
            .path("/index.php")
            .onResponseContent((r, b) -> contentLatch.countDown())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), is(content));

        client.stop();
        serverFuture.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testExtraBytesAfterEndRequestClosesConnection() throws Exception
    {
        String content = "hello";
        CompletableFuture<Void> serverFuture = CompletableFuture.runAsync(() ->
        {
            try (Socket socket = server.accept())
            {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                OutputStream output = socket.getOutputStream();

                int request = readRequest(input);

                // Write the whole response followed by an unexpected
                // record, so that they are all in the same network buffer.
                byte[] stdout = ("Content-Type: text/plain\r\n\r\n" + content).getBytes(StandardCharsets.US_ASCII);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                bytes.write(createRecord(FCGI.FrameType.STDOUT, request, stdout));
                bytes.write(createRecord(FCGI.FrameType.END_REQUEST, request, new byte[8]));
                bytes.write(createRecord(FCGI.FrameType.STDOUT, request, "unexpected".getBytes(StandardCharsets.US_ASCII)));
                output.write(bytes.toByteArray());
                output.flush();

                // The client must close the connection.
                while (input.read() >= 0)
                {
                    // Discard.
                }
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        });

        ContentResponse response = client.newRequest("localhost", server.getLocalPort())
            .path("/index.php")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), is(content));

        // The connection must be closed by the client, not pooled.
        serverFuture.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testEndRequestPaddingInSeparateNetworkRead() throws Exception
    {
        CountDownLatch paddingLatch = new CountDownLatch(1);
        CompletableFuture<Void> serverFuture = CompletableFuture.runAsync(() ->
        {
            try (Socket socket = server.accept())
            {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                OutputStream output = socket.getOutputStream();

                // Write the first response, with the END_REQUEST
                // padding missing, so that it is read separately.
                int request = readRequest(input);
                byte[] response = createResponse(request, "first", 8);
                output.write(response, 0, response.length - 8);
                output.flush();

                // Write the END_REQUEST padding alone.
                assertTrue(paddingLatch.await(5, TimeUnit.SECONDS));
                output.write(new byte[8]);
                output.flush();

                // The second request must be sent on the same connection.
                request = readRequest(input);
                output.write(createResponse(request, "second", 0));
                output.flush();

                // Wait for the client to close the connection.
                while (input.read() >= 0)
                {
                    // Discard.
                }
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        });

        ContentResponse response = client.newRequest("localhost", server.getLocalPort())
            .path("/index.php")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), is("first"));

        // Wait for the client to read the END_REQUEST padding alone.
        await().atMost(5, TimeUnit.SECONDS).until(() -> fillablesEntered.get() == fillablesExited.get());
        int fillables = fillablesExited.get();
        paddingLatch.countDown();
        await().atMost(5, TimeUnit.SECONDS).until(() -> fillablesExited.get() > fillables);

        response = client.newRequest("localhost", server.getLocalPort())
            .path("/index.php")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), is("second"));

        client.stop();
        serverFuture.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testEndRequestPaddingWithNextResponse() throws Exception
    {
        CompletableFuture<Void> serverFuture = CompletableFuture.runAsync(() ->
        {
            try (Socket socket = server.accept())
            {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                OutputStream output = socket.getOutputStream();

                // Write the first response, with the END_REQUEST padding missing.
                int request = readRequest(input);
                byte[] response = createResponse(request, "first", 8);
                output.write(response, 0, response.length - 8);
                output.flush();

                // The second request must be sent on the same connection.
                request = readRequest(input);

                // Write the END_REQUEST padding of the first response
                // together with the second response, in the same write.
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                bytes.write(new byte[8]);
                bytes.write(createResponse(request, "second", 0));
                output.write(bytes.toByteArray());
                output.flush();

                // Wait for the client to close the connection.
                while (input.read() >= 0)
                {
                    // Discard.
                }
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        });

        ContentResponse response = client.newRequest("localhost", server.getLocalPort())
            .path("/index.php")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), is("first"));

        response = client.newRequest("localhost", server.getLocalPort())
            .path("/index.php")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), is("second"));

        client.stop();
        serverFuture.get(5, TimeUnit.SECONDS);
    }

    private static byte[] createResponse(int request, String content, int endRequestPadding) throws IOException
    {
        // A STDOUT record with the headers and the content, then an END_REQUEST record
        // (appStatus=0, protocolStatus=REQUEST_COMPLETE) with the given padding.
        byte[] stdout = ("Content-Type: text/plain\r\n\r\n" + content).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(createRecord(FCGI.FrameType.STDOUT, request, stdout));
        bytes.write(createRecord(FCGI.FrameType.END_REQUEST, request, new byte[8], endRequestPadding));
        return bytes.toByteArray();
    }

    private static int readRequest(DataInputStream input) throws IOException
    {
        // Read the request records until the empty STDIN record.
        while (true)
        {
            byte[] header = new byte[8];
            input.readFully(header);
            int type = header[1] & 0xFF;
            int request = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            int contentLength = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
            int paddingLength = header[6] & 0xFF;
            input.readFully(new byte[contentLength + paddingLength]);
            if (type == FCGI.FrameType.STDIN.code && contentLength == 0)
                return request;
        }
    }

    private static byte[] createRecord(FCGI.FrameType type, int request, byte[] content)
    {
        // Pad to a multiple of 8 bytes, like php-fpm does.
        return createRecord(type, request, content, (8 - content.length % 8) % 8);
    }

    private static byte[] createRecord(FCGI.FrameType type, int request, byte[] content, int padding)
    {
        byte[] record = new byte[8 + content.length + padding];
        record[0] = 1;
        record[1] = (byte)type.code;
        record[2] = (byte)(request >> 8);
        record[3] = (byte)request;
        record[4] = (byte)(content.length >> 8);
        record[5] = (byte)content.length;
        record[6] = (byte)padding;
        System.arraycopy(content, 0, record, 8, content.length);
        return record;
    }
}
