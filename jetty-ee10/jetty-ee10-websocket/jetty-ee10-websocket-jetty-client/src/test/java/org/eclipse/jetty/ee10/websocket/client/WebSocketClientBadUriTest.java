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

package org.eclipse.jetty.ee10.websocket.client;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.ee10.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.toolchain.test.jupiter.TestTrackerExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(TestTrackerExtension.class)
public class WebSocketClientBadUriTest
{
    @WebSocket
    public static class OpenTrackingSocket
    {
        public CountDownLatch openLatch = new CountDownLatch(1);

        @OnWebSocketConnect
        public void onOpen(Session session)
        {
            openLatch.countDown();
        }

        public void assertNotOpened()
        {
            assertThat("Open Latch", openLatch.getCount(), greaterThanOrEqualTo(1L));
        }
    }

    public static Stream<Arguments> data()
    {
        return Stream.of(
            // @formatter:off
            // - not using right scheme
            Arguments.of("http://localhost"),
            Arguments.of("https://localhost"),
            Arguments.of("file://localhost"),
            Arguments.of("content://localhost"),
            Arguments.of("jar://localhost"),
            // - non-absolute uri
            Arguments.of("/mysocket"),
            Arguments.of("/sockets/echo"),
            Arguments.of("#echo"),
            Arguments.of("localhost:8080/echo")
            // @formatter:on
        );
    }

    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testBadURI(String rawUri) throws Exception
    {
        URI uri = URI.create(rawUri);

        OpenTrackingSocket clientSocket = new OpenTrackingSocket();

        try
        {
            client.connect(clientSocket, uri); // should toss exception

            fail("Expected IllegalArgumentException");
        }
        catch (IllegalArgumentException e)
        {
            // expected path
            clientSocket.assertNotOpened();
        }
    }
}
