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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class HttpClientExtraResponseBytesTest
{
    private HttpClient client;

    private void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(client);
    }

    public static Stream<Arguments> responses()
    {
        String extra = "EXTRA";
        List<Arguments> list = List.of(
            Arguments.of("""
                HTTP/1.1 200 OK\r
                Content-Length: 0\r
                \r
                %s""".formatted(extra),
                ""),
            Arguments.of("""
                HTTP/1.1 200 OK\r
                Content-Length: 5\r
                \r
                hello%s""".formatted(extra),
                "hello"),
            Arguments.of("""
                HTTP/1.1 200 OK\r
                Transfer-Encoding: chunked\r
                \r
                5\r
                hello\r
                0\r
                \r
                %s""".formatted(extra),
                "hello"),
            Arguments.of("""
                HTTP/1.1 100 Continue\r
                \r
                HTTP/1.1 200 OK\r
                Content-Length: 5\r
                \r
                hello%s""".formatted(extra),
                "hello")
        );
        return Stream.of(false, true)
            .flatMap(async -> list.stream().map(args -> Arguments.of(args.get()[0], args.get()[1], async)));
    }

    @ParameterizedTest
    @MethodSource("responses")
    public void testExtraResponseBytesClosesConnection(String response, String expectedContent, boolean asyncContent) throws Exception
    {
        startClient();

        try (ServerSocket server = new ServerSocket())
        {
            server.bind(new InetSocketAddress("localhost", 0));
            int port = server.getLocalPort();

            Request request = client.newRequest("localhost", port)
                .timeout(5, TimeUnit.SECONDS);
            if (asyncContent)
            {
                // Demand content asynchronously, so that the response
                // is completed from HttpReceiverOverHTTP.read().
                request.onResponseContentAsync((r, chunk, demander) -> demander.run());
            }
            CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request).send();

            try (Socket socket = server.accept())
            {
                socket.setSoTimeout(5000);
                consumeRequestHeaders(socket);

                // Write the response and the extra bytes in a single write,
                // so that the client likely reads them in the same buffer.
                OutputStream output = socket.getOutputStream();
                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.flush();

                ContentResponse contentResponse = completable.get(5, TimeUnit.SECONDS);
                assertEquals(200, contentResponse.getStatus());
                assertEquals(expectedContent, contentResponse.getContentAsString());

                // The client must close the connection, rather than pooling it.
                assertEquals(-1, socket.getInputStream().read());
            }

            AbstractConnectionPool pool = (AbstractConnectionPool)client.resolveDestination(request).getConnectionPool();
            await().atMost(5, TimeUnit.SECONDS).until(pool::getConnectionCount, is(0));

            // A subsequent request must use a new connection.
            CompletableFuture<ContentResponse> completable2 = new CompletableResponseListener(client.newRequest("localhost", port)
                .timeout(5, TimeUnit.SECONDS)).send();
            try (Socket socket = server.accept())
            {
                consumeRequestHeaders(socket);
                OutputStream output = socket.getOutputStream();
                output.write("""
                    HTTP/1.1 200 OK\r
                    Content-Length: 0\r
                    \r
                    """.getBytes(StandardCharsets.UTF_8));
                output.flush();
                assertEquals(200, completable2.get(5, TimeUnit.SECONDS).getStatus());
            }
        }
    }

    private void consumeRequestHeaders(Socket socket) throws IOException
    {
        InputStream input = socket.getInputStream();
        int crlfs = 0;
        while (true)
        {
            int read = input.read();
            if (read < 0)
                throw new IOException("Unexpected EOF");
            if (read == '\r' || read == '\n')
                ++crlfs;
            else
                crlfs = 0;
            if (crlfs == 4)
                break;
        }
    }
}
