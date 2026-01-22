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
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HttpClientBadURITest extends AbstractHttpClientServerTest
{
    public static Stream<Arguments> earlyCloseScenarios()
    {
        return Stream.of(
            // Status codes where HttpStatus.hasNoBody() returns true
            Arguments.of(204, "No Content", null),
            Arguments.of(304, "Not Modified", null),
            // Status codes requiring Content-Length: 0
            Arguments.of(400, "Bad Request", "0"),
            Arguments.of(403, "Forbidden", "0"),
            Arguments.of(404, "Not Found", "0"),
            Arguments.of(500, "Internal Server Error", "0")
        );
    }

    @ParameterizedTest
    @MethodSource("earlyCloseScenarios")
    public void testEarlyClose(int status, String reason, String contentLength) throws Exception
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
                    StringBuilder response = new StringBuilder();
                    response.append("HTTP/1.1 ").append(status).append(" ").append(reason).append("\r\n");
                    response.append("Connection: close\r\n");
                    if (contentLength != null)
                        response.append("Content-Length: ").append(contentLength).append("\r\n");
                    response.append("\r\n");
                    out.write(response.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                catch (Exception ignored)
                {
                }
            });
            serverThread.start();

            startClient(new NormalScenario());

            ContentResponse response = client.newRequest("localhost", port)
                .path("/test")
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(status, response.getStatus());

            serverThread.join(5000);
        }
    }

    public static Stream<Arguments> badPaths()
    {
        return Stream.of(
            Arguments.of("/foo%2Fbar"),
            Arguments.of("/foo//bar"),
            Arguments.of("/foo/..;/bar"),
            Arguments.of("/foo/%2e%2e;param/bar")
        );
    }

    @ParameterizedTest
    @MethodSource("badPaths")
    public void testBadPathEarlyClose(String path) throws Exception
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
                .path(path)
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());

            serverThread.join(5000);
        }
    }
}
