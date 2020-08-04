//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.tests;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.FrameRemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FrameRemoteEndpointTest
{
    private Server server;
    private WebSocketClient client;

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        NativeWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addMapping("/", EchoSocket.class));
        WebSocketUpgradeFilter.configure(contextHandler);

        server.setHandler(contextHandler);

        client = new WebSocketClient();
        server.start();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testFrameRemoteEndpoint() throws Exception
    {
        EventSocket clientSocket = new EventSocket();
        Session session = client.connect(clientSocket, WSURI.toWebsocket(server.getURI())).get(5, TimeUnit.SECONDS);

        FrameRemoteEndpoint frameRemote = session.getFrameRemote();
        assertThrows(IllegalStateException.class, session::getRemote);

        List<WSFrame> frames = Arrays.asList(
            new WSFrame(Frame.Type.TEXT.getOpCode(), BufferUtil.toBuffer("hello"), false, true),
            new WSFrame(Frame.Type.CONTINUATION.getOpCode(), BufferUtil.toBuffer(" wo"), false, true),
            new WSFrame(Frame.Type.CONTINUATION.getOpCode(), BufferUtil.toBuffer("rld!"), true, true));

        for (Frame frame : frames)
        {
            FutureWriteCallback callback = new FutureWriteCallback();
            frameRemote.uncheckedSendFrame(frame, callback);
            callback.block();
        }

        String message = clientSocket.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(message, is("hello world!"));
    }

    public static class WSFrame implements Frame
    {
        private final byte opcode;
        private final ByteBuffer payload;
        private final boolean fin;
        private final byte[] mask;

        public WSFrame(byte opcode, ByteBuffer payload, boolean fin, boolean masked)
        {
            this.opcode = opcode;
            this.payload = payload;
            this.fin = fin;
            this.mask = masked ? newMask() : null;
        }

        @Override
        public byte getOpCode()
        {
            return opcode;
        }

        @Override
        public ByteBuffer getPayload()
        {
            return payload;
        }

        @Override
        public int getPayloadLength()
        {
            return payload == null ? 0 : payload.remaining();
        }

        @Override
        public boolean isFin()
        {
            return fin;
        }

        @Override
        public byte[] getMask()
        {
            return mask;
        }

        private static byte[] newMask()
        {
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            return mask;
        }

        @Override
        public Type getType()
        {
            return Type.from(getOpCode());
        }

        @Override
        public boolean hasPayload()
        {
            return getPayloadLength() > 0;
        }

        @Override
        public boolean isLast()
        {
            return isFin();
        }

        @Override
        public boolean isMasked()
        {
            return getMask() != null;
        }

        @Override
        public boolean isRsv1()
        {
            return false;
        }

        @Override
        public boolean isRsv2()
        {
            return false;
        }

        @Override
        public boolean isRsv3()
        {
            return false;
        }
    }
}
