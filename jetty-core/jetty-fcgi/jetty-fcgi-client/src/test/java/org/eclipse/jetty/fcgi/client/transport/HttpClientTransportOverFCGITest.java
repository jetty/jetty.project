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

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTransportOverFCGITest
{
    private ServerSocket server;
    private HttpClient client;

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new ServerSocket(0);
        client = new HttpClient(new HttpClientTransportOverFCGI("/"));
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(client);
        server.close();
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

                // Read the request records until the empty STDIN record.
                int request;
                while (true)
                {
                    byte[] header = new byte[8];
                    input.readFully(header);
                    int type = header[1] & 0xFF;
                    request = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                    int contentLength = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
                    int paddingLength = header[6] & 0xFF;
                    input.readFully(new byte[contentLength + paddingLength]);
                    if (type == FCGI.FrameType.STDIN.code && contentLength == 0)
                        break;
                }

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

    private static byte[] createRecord(FCGI.FrameType type, int request, byte[] content)
    {
        // Pad to a multiple of 8 bytes, like php-fpm does.
        int padding = (8 - content.length % 8) % 8;
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
