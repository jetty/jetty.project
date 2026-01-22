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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class HttpClientBadURITest extends AbstractHttpClientServerTest
{
    @Test
    public void testBadURIEarlyClose() throws Exception
    {
        try (ServerSocket serverSocket = new ServerSocket(0))
        {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() ->
            {
                try (Socket socket = serverSocket.accept())
                {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    reader.readLine();

                    OutputStream out = socket.getOutputStream();
                    String response = "HTTP/1.1 400 Bad Request\r\n" +
                        "Connection: close\r\n" +
                        "Content-Length: 0\r\n" +
                        "\r\n";
                    out.write(response.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    // Socket closes before client finishes writing.
                }
                catch (Exception ignored)
                {
                }
            });
            serverThread.start();

            startClient(new NormalScenario());

            ContentResponse response = client.newRequest("localhost", port)
                .path("/foo%2Fbar")
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());

            serverThread.join(5000);
        }
    }

    @Test
    public void testVariousBadURIPatternsEarlyClose() throws Exception
    {
        String[] badPaths = {
            "/foo%2Fbar",
            "/foo//bar",
            "/foo/..;/bar",
            "/foo/%2e%2e;param/bar"
        };

        for (String badPath : badPaths)
        {
            try (ServerSocket serverSocket = new ServerSocket(0))
            {
                int port = serverSocket.getLocalPort();

                Thread serverThread = new Thread(() ->
                {
                    try (Socket socket = serverSocket.accept())
                    {
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        reader.readLine();

                        OutputStream out = socket.getOutputStream();
                        String response = "HTTP/1.1 400 Bad Request\r\n" +
                            "Connection: close\r\n" +
                            "Content-Length: 0\r\n" +
                            "\r\n";
                        out.write(response.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    catch (Exception ignored)
                    {
                    }
                });
                serverThread.start();

                startClient(new NormalScenario());

                ContentResponse response = client.newRequest("localhost", port)
                    .path(badPath)
                    .timeout(5, TimeUnit.SECONDS)
                    .send();

                assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus(),
                    "Expected 400 for path: " + badPath);

                serverThread.join(5000);
                disposeClient();
            }
        }
    }

    @Test
    public void testBadURIProperClose() throws Exception
    {
        try (ServerSocket serverSocket = new ServerSocket(0))
        {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() ->
            {
                try (Socket socket = serverSocket.accept())
                {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty())
                    {
                        // Consume all headers.
                    }

                    OutputStream out = socket.getOutputStream();
                    String response = "HTTP/1.1 400 Bad Request\r\n" +
                        "Connection: close\r\n" +
                        "Content-Length: 0\r\n" +
                        "\r\n";
                    out.write(response.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                catch (Exception e)
                {
                    fail("Server should not fail: " + e.getMessage());
                }
            });
            serverThread.start();

            startClient(new NormalScenario());

            ContentResponse response = client.newRequest("localhost", port)
                .path("/foo%2Fbar")
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());

            serverThread.join(5000);
        }
    }
}
