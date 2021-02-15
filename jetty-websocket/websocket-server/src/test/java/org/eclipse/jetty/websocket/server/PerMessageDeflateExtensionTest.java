//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.server;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.common.util.Sha1Sum;
import org.eclipse.jetty.websocket.server.helper.CaptureSocket;
import org.eclipse.jetty.websocket.server.helper.EchoServlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class PerMessageDeflateExtensionTest
{
    public static Stream<Arguments> modes()
    {
        List<Scenario> modes = new ArrayList<>();

        for (Sizes size : Sizes.values())
        {
            modes.add(new Scenario("Normal HTTP/WS", false, "ws", size, -1));
            modes.add(new Scenario("Encrypted HTTPS/WSS", true, "wss", size, -1));
            int altInputBufSize = 15 * 1024;
            modes.add(new Scenario("Normal HTTP/WS", false, "ws", size, altInputBufSize));
            modes.add(new Scenario("Encrypted HTTPS/WSS", true, "wss", size, altInputBufSize));
        }

        return modes.stream().map(Arguments::of);
    }

    private SimpleServletServer server;

    private void startServer(Scenario scenario) throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
        server.enableSsl(scenario.sslMode);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        server.stop();
    }

    /**
     * Default configuration for permessage-deflate
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("modes")
    public void testPerMessageDeflateDefault(Scenario scenario) throws Exception
    {
        startServer(scenario);

        assumeTrue(server.getWebSocketServletFactory().getExtensionFactory().isAvailable("permessage-deflate"),
            "Server has permessage-deflate registered");

        assertThat("server scheme", server.getServerUri().getScheme(), is(scenario.scheme));

        int binBufferSize = (int)(scenario.msgSize.size * 1.5);

        WebSocketPolicy serverPolicy = server.getWebSocketServletFactory().getPolicy();

        // Ensure binBufferSize is sane (not smaller then other buffers)
        binBufferSize = Math.max(binBufferSize, serverPolicy.getMaxBinaryMessageSize());
        binBufferSize = Math.max(binBufferSize, serverPolicy.getMaxBinaryMessageBufferSize());
        binBufferSize = Math.max(binBufferSize, scenario.inputBufferSize);

        serverPolicy.setMaxBinaryMessageSize(binBufferSize);
        serverPolicy.setMaxBinaryMessageBufferSize(binBufferSize);

        WebSocketClient client = new WebSocketClient(server.getSslContextFactory());
        WebSocketPolicy clientPolicy = client.getPolicy();
        clientPolicy.setMaxBinaryMessageSize(binBufferSize);
        clientPolicy.setMaxBinaryMessageBufferSize(binBufferSize);
        if (scenario.inputBufferSize > 0)
        {
            clientPolicy.setInputBufferSize(scenario.inputBufferSize);
        }

        try
        {
            client.start();
            // Make sure the read times out if there are problems with the implementation
            client.setMaxIdleTimeout(TimeUnit.SECONDS.toMillis(15));

            CaptureSocket clientSocket = new CaptureSocket();
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.addExtensions("permessage-deflate");
            request.setSubProtocols("echo");

            Future<Session> fut = client.connect(clientSocket, server.getServerUri(), request);

            // Wait for connect
            Session session = fut.get(30, TimeUnit.SECONDS);

            assertThat("Response.extensions", getNegotiatedExtensionList(session), containsString("permessage-deflate"));

            // Create message
            byte[] msg = new byte[scenario.msgSize.size];
            Random rand = new Random();
            rand.setSeed(8080);
            rand.nextBytes(msg);

            // Calculate sha1
            String sha1 = Sha1Sum.calculate(msg);

            // Client sends first message
            session.getRemote().sendBytes(ByteBuffer.wrap(msg));

            String echoMsg = clientSocket.messages.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Echo'd Message", echoMsg, is("binary[sha1=" + sha1 + "]"));
        }
        finally
        {
            client.stop();
        }
    }

    private String getNegotiatedExtensionList(Session session)
    {
        StringBuilder actual = new StringBuilder();
        actual.append('[');

        boolean delim = false;
        for (ExtensionConfig ext : session.getUpgradeResponse().getExtensions())
        {
            if (delim)
                actual.append(", ");
            actual.append(ext.getName());
            delim = true;
        }
        actual.append(']');

        return actual.toString();
    }

    public enum Sizes
    {
        TINY(10),
        SMALL(1024),
        MEDIUM(10 * 1024),
        LARGE(100 * 1024),
        HUGE(1024 * 1024);

        private int size;

        Sizes(int size)
        {
            this.size = size;
        }
    }

    public static class Scenario
    {
        public final String mode;
        public final boolean sslMode;
        public final String scheme;
        public final Sizes msgSize;
        public final int inputBufferSize;

        public Scenario(String mode, boolean sslMode, String scheme, Sizes msgSize, int bufferSize)
        {
            this.mode = mode;
            this.sslMode = sslMode;
            this.scheme = scheme;
            this.msgSize = msgSize;
            this.inputBufferSize = bufferSize;
        }

        @Override
        public String toString()
        {
            return String.format("%s (%s) (Input Buffer Size: %,d bytes)", mode, scheme, msgSize.size);
        }
    }
}
